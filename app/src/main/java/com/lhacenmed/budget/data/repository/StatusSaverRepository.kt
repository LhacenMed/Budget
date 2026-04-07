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
import com.lhacenmed.budget.data.util.WatchedFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

    // ── Folder doc ID cache ───────────────────────────────────────────────────
    //
    // After the first SAF traversal we persist each app's `.Statuses` folder
    // document ID. On every subsequent launch we reconstruct [WatchedFolder]s
    // from these IDs with zero binder IPC — pure string arithmetic on the
    // cached value and the tree URI.

    private fun getFolderDocId(source: StatusSource): String? =
        prefs.getString(folderDocIdKey(source), null)

    private fun saveFolderDocId(source: StatusSource, docId: String) =
        prefs.edit { putString(folderDocIdKey(source), docId) }

    /** Clears only the folder doc IDs — called when a new tree URI is granted. */
    fun clearFolderDocIds() = prefs.edit {
        remove(KEY_DOC_ID_WA)
        remove(KEY_DOC_ID_BIZ)
    }

    private fun folderDocIdKey(source: StatusSource) =
        if (source == StatusSource.WHATSAPP) KEY_DOC_ID_WA else KEY_DOC_ID_BIZ

    // ── Status list cache ─────────────────────────────────────────────────────

    /**
     * Returns the last successfully scanned list from SharedPreferences.
     * Synchronous — safe to call on the main thread during VM init.
     */
    fun getCachedStatuses(): List<StatusItem> {
        val json = prefs.getString(KEY_CACHE, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                StatusItem(
                    uri          = obj.getString("uri").toUri(),
                    name         = obj.getString("name"),
                    isVideo      = obj.getBoolean("isVideo"),
                    source       = StatusSource.valueOf(obj.getString("source")),
                    lastModified = obj.optLong("lastModified", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Persists [items] to SharedPreferences so the next cold start shows
     * content instantly with no loading delay.
     */
    internal fun saveCache(items: List<StatusItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("uri",          item.uri.toString())
                put("name",         item.name)
                put("isVideo",      item.isVideo)
                put("source",       item.source.name)
                put("lastModified", item.lastModified)
            })
        }
        prefs.edit { putString(KEY_CACHE, array.toString()) }
    }

    private fun clearCache() = prefs.edit { remove(KEY_CACHE) }

    // ── Folder resolution ─────────────────────────────────────────────────────

    /**
     * Resolves [WatchedFolder] handles for each app's `.Statuses` directory.
     *
     * **Fast path** (every launch after the first): reads persisted folder doc IDs
     * from SharedPreferences and constructs [WatchedFolder]s with zero SAF I/O.
     *
     * **Slow path** (first-ever launch or after permission is re-granted): performs
     * a one-time recursive SAF traversal to locate the `.Statuses` directories.
     * Discovered doc IDs are immediately persisted so the fast path is taken on
     * every subsequent boot.
     */
    suspend fun resolveWatchedFolders(treeUri: Uri): List<WatchedFolder> =
        withContext(Dispatchers.IO) {
            val waDocId  = getFolderDocId(StatusSource.WHATSAPP)
            val bizDocId = getFolderDocId(StatusSource.WHATSAPP_BUSINESS)

            // Fast path: reconstruct handles from cached doc IDs, zero IPC
            if (waDocId != null || bizDocId != null) {
                return@withContext buildList {
                    waDocId?.toWatchedFolder(treeUri, StatusSource.WHATSAPP)?.let(::add)
                    bizDocId?.toWatchedFolder(treeUri, StatusSource.WHATSAPP_BUSINESS)?.let(::add)
                }
            }

            // Slow path: first launch — traverse and persist discovered doc IDs
            traverseAndResolve(treeUri)
        }

    /**
     * Full recursive SAF traversal to locate `.Statuses` directories.
     * Persists discovered folder doc IDs immediately so [resolveWatchedFolders]
     * uses the fast path on every subsequent launch.
     */
    private fun traverseAndResolve(treeUri: Uri): List<WatchedFolder> = try {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        buildList {
            listOf(
                PACKAGE_WHATSAPP          to StatusSource.WHATSAPP,
                PACKAGE_WHATSAPP_BUSINESS to StatusSource.WHATSAPP_BUSINESS
            ).forEach { (pkg, source) ->
                val appDir       = root.findFile(pkg) ?: return@forEach
                val statusFolder = findStatusesFolder(appDir, depth = 0) ?: return@forEach
                val folderDocId  = DocumentsContract.getDocumentId(statusFolder.uri)
                val realPath     = documentUriToPath(statusFolder.uri) ?: return@forEach
                saveFolderDocId(source, folderDocId)
                add(WatchedFolder(realPath, source, folderDocId, treeUri))
            }
        }
    } catch (_: Exception) { emptyList() }

    // ── Status reading ────────────────────────────────────────────────────────

    /**
     * Fast status listing using in-memory [WatchedFolder] handles.
     *
     * Issues a single [ContentResolver.query] per folder ([buildChildDocumentsUriUsingTree])
     * instead of one binder IPC per file as [DocumentFile.listFiles] does.
     * Cost: O(1) IPC per folder regardless of how many files are present.
     *
     * Used by pull-to-refresh and the background boot refresh.
     * Saves the result to cache automatically when non-empty.
     */
    suspend fun getStatusesFast(folders: List<WatchedFolder>): List<StatusItem> =
        withContext(Dispatchers.IO) {
            folders.flatMap { folder ->
                queryStatusItems(folder.treeUri, folder.folderDocId, folder.source)
            }.also { if (it.isNotEmpty()) saveCache(it) }
        }

    /**
     * Full scan for both WhatsApp variants.
     * Uses cached folder doc IDs when available; falls back to SAF traversal per-source.
     *
     * Primarily used by [getStatuses] when [getStatusesFast] is not yet available
     * (i.e., before [resolveWatchedFolders] has been called).
     */
    suspend fun getStatuses(treeUri: Uri): List<StatusItem> = withContext(Dispatchers.IO) {
        try {
            buildList {
                listOf(
                    PACKAGE_WHATSAPP          to StatusSource.WHATSAPP,
                    PACKAGE_WHATSAPP_BUSINESS to StatusSource.WHATSAPP_BUSINESS
                ).forEach { (pkg, source) ->
                    val cachedDocId = getFolderDocId(source)
                    if (cachedDocId != null) {
                        // Fast path: doc ID already known
                        addAll(queryStatusItems(treeUri, cachedDocId, source))
                    } else {
                        // Slow path: traverse this source, cache its doc ID for next time
                        val root   = DocumentFile.fromTreeUri(context, treeUri) ?: return@forEach
                        val appDir = root.findFile(pkg) ?: return@forEach
                        addAll(scanAppDir(appDir, source, treeUri))
                    }
                }
            }.also { if (it.isNotEmpty()) saveCache(it) }
        } catch (_: Exception) {
            clearUri()
            clearCache()
            clearFolderDocIds()
            emptyList()
        }
    }

    /**
     * Constructs a [StatusItem] for a single newly-detected file with zero I/O —
     * pure [DocumentsContract] string math on [WatchedFolder.folderDocId] + [fileName].
     *
     * Uses [System.currentTimeMillis] as [StatusItem.lastModified] so real-time additions
     * sort to the top of the list immediately.
     *
     * Used by the real-time [com.lhacenmed.budget.data.util.FolderChange.Added] handler.
     * Returns null for unrecognised extensions (e.g. `.tmp` temp files).
     */
    fun resolveSingleStatus(folder: WatchedFolder, fileName: String): StatusItem? {
        if (!fileName.isImage() && !fileName.isVideo()) return null
        val childUri = DocumentsContract.buildDocumentUriUsingTree(
            folder.treeUri,
            "${folder.folderDocId}/$fileName"
        )
        return StatusItem(
            uri          = childUri,
            name         = fileName,
            isVideo      = fileName.isVideo(),
            source       = folder.source,
            lastModified = System.currentTimeMillis()
        )
    }

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

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Issues a single [ContentResolver.query] for all children of [folderDocId].
     *
     * Queries [DocumentsContract.Document.COLUMN_LAST_MODIFIED] so items can be
     * sorted by creation date (newest first) without any additional I/O.
     *
     * [DocumentsContract.buildChildDocumentsUriUsingTree] + [ContentResolver.query]
     * costs **one binder IPC** regardless of file count.
     * [DocumentFile.listFiles] costs **one IPC per file** — O(N) vs O(1).
     */
    private fun queryStatusItems(
        treeUri:     Uri,
        folderDocId: String,
        source:      StatusSource
    ): List<StatusItem> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        ) ?: return emptyList()

        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name         = c.getString(1) ?: continue
                    val mime         = c.getString(2) ?: continue
                    val lastModified = c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (!name.isImage() && !name.isVideo()) continue
                    val docId = c.getString(0)
                    val uri   = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    add(StatusItem(uri, name, name.isVideo(), source, lastModified))
                }
            }.sortedByDescending { it.lastModified }
        }
    }

    /**
     * SAF traversal fallback: locates `.Statuses` inside [appDir], persists its doc ID,
     * then lists files via [queryStatusItems] (single IPC, not one per file).
     */
    private fun scanAppDir(
        appDir:  DocumentFile,
        source:  StatusSource,
        treeUri: Uri
    ): List<StatusItem> {
        val folder = findStatusesFolder(appDir, depth = 0) ?: return emptyList()
        val docId  = DocumentsContract.getDocumentId(folder.uri)
        saveFolderDocId(source, docId)
        return queryStatusItems(treeUri, docId, source)
    }

    /**
     * Recursively searches for a folder named [STATUSES_FOLDER_NAME] up to
     * [MAX_SCAN_DEPTH] levels deep. Only called on first launch per source.
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

    /**
     * Converts a SAF document URI to its real filesystem path for [android.os.FileObserver].
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

    /**
     * Reconstructs a [WatchedFolder] from a cached folder doc ID.
     * Returns null if the doc ID cannot be resolved to a real filesystem path —
     * this is pure string arithmetic with zero IPC.
     */
    private fun String.toWatchedFolder(treeUri: Uri, source: StatusSource): WatchedFolder? {
        val docUri   = DocumentsContract.buildDocumentUriUsingTree(treeUri, this)
        val realPath = documentUriToPath(docUri) ?: return null
        return WatchedFolder(realPath, source, this, treeUri)
    }

    private fun String.isImage() = endsWith(".jpg", true) || endsWith(".jpeg", true) ||
            endsWith(".png", true) || endsWith(".webp", true)

    private fun String.isVideo() = endsWith(".mp4", true) || endsWith(".3gp", true) ||
            endsWith(".mkv", true)

    companion object {
        private const val KEY_URI        = "tree_uri"
        private const val KEY_CACHE      = "statuses_cache"
        private const val KEY_DOC_ID_WA  = "folder_doc_id_wa"
        private const val KEY_DOC_ID_BIZ = "folder_doc_id_biz"

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
