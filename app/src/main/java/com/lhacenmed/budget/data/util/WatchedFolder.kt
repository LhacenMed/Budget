package com.lhacenmed.budget.data.util

import android.net.Uri
import com.lhacenmed.budget.data.model.StatusSource

/**
 * Fully-resolved handle to a single app's `.Statuses` directory.
 *
 * Carries both the real filesystem path (consumed by [android.os.FileObserver] / inotify)
 * and the SAF coordinates needed to construct child content URIs without any I/O —
 * pure [android.provider.DocumentsContract] string math.
 *
 * @param realPath    Absolute filesystem path  — e.g. `/storage/emulated/0/…/.Statuses`
 * @param source      Which WhatsApp variant owns this folder.
 * @param folderDocId SAF document ID of this folder — e.g. `primary:Android/media/…/.Statuses`
 * @param treeUri     The granted SAF tree URI used as the base when building child URIs.
 */
data class WatchedFolder(
    val realPath:    String,
    val source:      StatusSource,
    val folderDocId: String,
    val treeUri:     Uri
)
