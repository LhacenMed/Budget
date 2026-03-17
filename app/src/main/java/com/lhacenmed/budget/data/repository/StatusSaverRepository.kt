package com.lhacenmed.budget.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lhacenmed.budget.data.model.StatusItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // ── Read statuses ─────────────────────────────────────────────────────────

    /**
     * Returns all status items from [treeUri].
     *
     * The user may have granted access to any level of the WhatsApp folder:
     *   - Exactly the `.Statuses` folder → read files directly
     *   - A parent folder (e.g. WhatsApp root, accounts folder, etc.) → scan
     *     up to 3 levels deep for a folder named ".Statuses"
     *
     * This handles both path layouts:
     *   Old: WhatsApp/Media/.Statuses
     *   New: WhatsApp/accounts/{anyNumber}/Media/.Statuses
     */
    fun getStatuses(treeUri: Uri): List<StatusItem> = try {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val statusesFolder = findStatusesFolder(root, depth = 0) ?: root
        readStatusFiles(statusesFolder)
    } catch (_: Exception) {
        clearUri()
        emptyList()
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
                // Recurse into subdirectories
                val found = findStatusesFolder(file, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun readStatusFiles(folder: DocumentFile): List<StatusItem> =
        folder.listFiles()
            .filter { it.isFile && it.name != null }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                when {
                    name.isImage() -> StatusItem(file.uri, name, isVideo = false)
                    name.isVideo() -> StatusItem(file.uri, name, isVideo = true)
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
        private const val KEY_URI             = "tree_uri"
        private const val STATUSES_FOLDER_NAME = ".Statuses"
        private const val MAX_SCAN_DEPTH      = 3

        /**
         * Picker hint: starts the folder picker at the WhatsApp root.
         * Works for both old and new (multi-account) layouts — the user
         * navigates from here to whichever .Statuses folder they need,
         * or simply grants the root and we auto-discover .Statuses inside.
         */
        val WHATSAPP_ROOT_URI: Uri = (
                "content://com.android.externalstorage.documents/tree/" +
                        "primary%3AAndroid%2Fmedia%2Fcom.whatsapp"
                ).toUri()
    }
}
