package com.lhacenmed.budget.data.util

import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Watches one or more real filesystem directories via Linux inotify ([FileObserver]).
 * Emits [Unit] whenever a file is written, deleted, or moved in any watched directory.
 *
 * Why this works with SAF:
 *   The SAF external-storage provider is a content layer on top of the same inodes
 *   the kernel watches. Converting a SAF document URI to its real path
 *   (primary:Android/media/... → /storage/emulated/0/Android/media/...) gives us
 *   a path inotify can observe directly, even though the tree URI itself cannot be
 *   passed to [android.database.ContentObserver].
 *
 * Lifecycle: this is a cold flow — inotify watches are registered on collection and
 * released on cancellation. Each collector gets independent watchers.
 */
class StatusFolderObserver(private val paths: List<String>) {

    private companion object {
        // Events that mean a status appeared or disappeared
        private const val WATCH_MASK =
            FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO    or
                    FileObserver.DELETE      or
                    FileObserver.MOVED_FROM
    }

    val changes: Flow<Unit> = callbackFlow {
        val observers: List<FileObserver> = paths.map { path ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(File(path), WATCH_MASK) {
                    override fun onEvent(event: Int, file: String?) { trySend(Unit) }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(path, WATCH_MASK) {
                    override fun onEvent(event: Int, file: String?) { trySend(Unit) }
                }
            }.also { it.startWatching() }
        }
        awaitClose { observers.forEach { it.stopWatching() } }
    }
}
