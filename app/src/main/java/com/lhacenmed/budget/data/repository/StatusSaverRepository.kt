package com.lhacenmed.budget.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.model.StatusSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatusSaverRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("status_saver", Context.MODE_PRIVATE)

    // ── URI persistence ───────────────────────────────────────────────────────

    fun getSavedUri(): Uri? = prefs.getString(KEY_URI, null)?.toUri()

    fun persistUri(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        prefs.edit { putString(KEY_URI, uri.toString()) }
    }

    fun clearUri() = prefs.edit { remove(KEY_URI) }

    // ── Cache ─────────────────────────────────────────────────────────────────

    /**
     * Returns the last successfully scanned list from SharedPreferences.
     * Synchronous and fast — safe to call on the main thread during VM init.
     */
    fun getCachedStatuses(): List<StatusItem> {
        val json = prefs.getString(KEY_CACHE, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                StatusItem(
                    uri     = obj.getString("uri").toUri(),
                    name    = obj.getString("name"),
                    isVideo = obj.getBoolean("isVideo"),
                    source  = StatusSource.valueOf(obj.getString("source"))
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Persists [items] to SharedPreferences so the next cold start is instant.
     * Called after every successful SAF scan.
     */
    internal fun saveCache(items: List<StatusItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("uri",     item.uri.toString())
                put("name",    item.name)
                put("isVideo", item.isVideo)
                put("source",  item.source.name)
            })
        }
        prefs.edit { putString(KEY_CACHE, array.toString()) }
    }

    private fun clearCache() = prefs.edit { remove(KEY_CACHE) }

    // ── Path resolution for FileObserver ─────────────────────────────────────

    /**
     * Resolves the real filesystem paths of each app's `.Statuses` directory
     * from the granted SAF [treeUri], so they can be watched by [android.os.FileObserver].
     *
     * SAF document IDs for primary storage follow the pattern:
     *   `primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses`
     * which maps to:
     *   `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/.Statuses`
     *
     * Returns an empty list if no `.Statuses` folders are found or the paths
     * cannot be resolved (e.g. secondary storage with unknown volume name).
     */
    suspend fun resolveStatusFolderPaths(treeUri: Uri): List<String> = withContext(Dispatchers.IO) {
        try {
            val root  = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            val paths = mutableListOf<String>()

            listOf(PACKAGE_WHATSAPP to StatusSource.WHATSAPP,
                PACKAGE_WHATSAPP_BUSINESS to StatusSource.WHATSAPP_BUSINESS
            ).forEach { (pkg, _) ->
                val appDir        = root.findFile(pkg) ?: return@forEach
                val statusesFolder = findStatusesFolder(appDir, depth = 0) ?: return@forEach
                val realPath      = documentUriToPath(statusesFolder.uri) ?: return@forEach
                paths.add(realPath)
            }
            paths
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Converts a SAF document URI to its real filesystem path.
     *
     * External storage document IDs are formatted as `volume:relative/path`.
     * "primary" maps to `/storage/emulated/0`; other volumes use `/storage/<volumeId>`.
     */
    private fun documentUriToPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getDocumentId(uri)
        val parts = docId.split(":")
        if (parts.size != 2) null
        else if (parts[0] == "primary") "/storage/emulated/0/${parts[1]}"
        else "/storage/${parts[0]}/${parts[1]}"
    } catch (_: Exception) { null }

    // ── Read statuses ─────────────────────────────────────────────────────────

    /**
     * Full scan of both [PACKAGE_WHATSAPP] and [PACKAGE_WHATSAPP_BUSINESS] under [treeUri].
     *
     * Both app subtrees are scanned in parallel via [async] — wall-clock time is
     * determined by the slower of the two scans rather than their sum.
     * Dispatched to [Dispatchers.IO]. Saves result to cache on success.
     */
    suspend fun getStatuses(treeUri: Uri): List<StatusItem> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()

            // Launch both subtree scans concurrently
            val waDeferred  = async { root.findFile(PACKAGE_WHATSAPP)?.let          { readFromAppRoot(it, StatusSource.WHATSAPP)          } ?: emptyList() }
            val bizDeferred = async { root.findFile(PACKAGE_WHATSAPP_BUSINESS)?.let { readFromAppRoot(it, StatusSource.WHATSAPP_BUSINESS) } ?: emptyList() }

            val all = waDeferred.await() + bizDeferred.await()
            if (all.isNotEmpty()) saveCache(all)
            all
        } catch (_: Exception) {
            clearUri()
            clearCache()
            emptyList()
        }
    }

    /**
     * Partial scan — only the folder belonging to [source].
     * Used by [StatusViewModel.refresh] for parallel manual pull-to-refresh.
     */
    suspend fun getStatusesForSource(treeUri: Uri, source: StatusSource): List<StatusItem> =
        withContext(Dispatchers.IO) {
            try {
                val root   = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
                val pkg    = if (source == StatusSource.WHATSAPP) PACKAGE_WHATSAPP else PACKAGE_WHATSAPP_BUSINESS
                val appDir = root.findFile(pkg) ?: return@withContext emptyList()
                readFromAppRoot(appDir, source)
            } catch (_: Exception) { emptyList() }
        }

    /**
     * Finds the `.Statuses` folder within an app's media directory,
     * then reads and returns all valid status files from it.
     */
    private fun readFromAppRoot(appDir: DocumentFile, source: StatusSource): List<StatusItem> {
        val statusesFolder = findStatusesFolder(appDir, depth = 0) ?: return emptyList()
        return readStatusFiles(statusesFolder, source)
    }

    /**
     * Recursively searches for a folder named ".Statuses" up to [MAX_SCAN_DEPTH] levels deep.
     * Returns the folder if found, null otherwise.
     */
    private fun findStatusesFolder(dir: DocumentFile, depth: Int): DocumentFile? {
        if (depth > MAX_SCAN_DEPTH) return null
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                if (file.name == STATUSES_FOLDER_NAME) return file
                val found = findStatusesFolder(file, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun readStatusFiles(folder: DocumentFile, source: StatusSource): List<StatusItem> =
        folder.listFiles()
            .filter { it.isFile && it.name != null }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                when {
                    name.isImage() -> StatusItem(file.uri, name, isVideo = false, source = source)
                    name.isVideo() -> StatusItem(file.uri, name, isVideo = true,  source = source)
                    else           -> null
                }
            }
            .sortedByDescending { it.name }

    // ── Save to gallery ───────────────────────────────────────────────────────

    suspend fun saveStatus(item: StatusItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(item.uri)
                ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (item.isVideo)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (item.isVideo) "Movies/StatusSaver" else "Pictures/StatusSaver")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val outUri = context.contentResolver.insert(collection, values)
                    ?: return@withContext false
                context.contentResolver.openOutputStream(outUri)?.use { input.copyTo(it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(outUri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES
                        else Environment.DIRECTORY_PICTURES
                    ), "StatusSaver"
                ).also { it.mkdirs() }
                File(dir, item.name).outputStream().use { input.copyTo(it) }
            }
            input.close()
            true
        } catch (_: Exception) { false }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.isImage() = endsWith(".jpg", true) || endsWith(".jpeg", true) ||
            endsWith(".png", true) || endsWith(".webp", true)

    private fun String.isVideo() = endsWith(".mp4", true) || endsWith(".3gp", true) ||
            endsWith(".mkv", true)

    companion object {
        private const val KEY_URI                   = "tree_uri"
        private const val KEY_CACHE                 = "statuses_cache"
        private const val STATUSES_FOLDER_NAME      = ".Statuses"
        private const val MAX_SCAN_DEPTH            = 4
        private const val PACKAGE_WHATSAPP          = "com.whatsapp"
        private const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        /**
         * Picker initial URI hint pointing at Android/media/.
         *
         * Must be a `document/` URI (not `tree/`) — that is what
         * [Intent.EXTRA_INITIAL_URI] requires for [Intent.ACTION_OPEN_DOCUMENT_TREE].
         * A `tree/` URI is silently ignored by the picker and it falls back
         * to its default root, which is why the picker was not opening here.
         *
         * primary%3AAndroid%2Fmedia = URL-encoded "primary:Android/media"
         */
        @Suppress("SpellCheckingInspection")
        val ANDROID_MEDIA_ROOT_URI: Uri = (
                "content://com.android.externalstorage.documents/document/" +
                        "primary%3AAndroid%2Fmedia"
                ).toUri()
    }
}
