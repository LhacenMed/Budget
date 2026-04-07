package com.lhacenmed.budget.data.util

import android.os.Build
import android.os.FileObserver
import com.lhacenmed.budget.data.model.StatusSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Watches one or more real filesystem directories via Linux inotify ([FileObserver]).
 *
 * Emits a typed [FolderChange] per event — allowing the consumer to decide
 * whether to apply an incremental update ([FolderChange.Added] / [FolderChange.Removed])
 * or fall back to a full re-scan ([FolderChange.Recheck]).
 *
 * Why this works with SAF:
 *   The SAF external-storage provider is a content layer on top of the same inodes
 *   the kernel watches. Converting a SAF document URI to its real path gives us
 *   a path inotify can observe directly, even though the tree URI itself cannot be
 *   passed to [android.database.ContentObserver].
 *
 * Lifecycle: cold flow — inotify watches register on collection and release on
 * cancellation. Each collector gets its own independent set of watchers.
 */
class StatusFolderObserver(private val folders: List<WatchedFolder>) {

    private companion object {
        private const val WATCH_MASK =
            FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO    or
                    FileObserver.DELETE      or
                    FileObserver.MOVED_FROM
    }

    val changes: Flow<FolderChange> = callbackFlow {
        val observers = folders.map { folder ->
            makeObserver(folder.realPath) { event, fileName ->
                trySend(mapToChange(event, fileName, folder.source))
            }.also { it.startWatching() }
        }
        awaitClose { observers.forEach { it.stopWatching() } }
    }

    private fun makeObserver(path: String, onEvent: (Int, String?) -> Unit): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(path), WATCH_MASK) {
                override fun onEvent(event: Int, file: String?) = onEvent(event, file)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(path, WATCH_MASK) {
                override fun onEvent(event: Int, file: String?) = onEvent(event, file)
            }
        }

    /**
     * Maps a raw inotify event + filename to a typed [FolderChange].
     *
     * Null filename → [FolderChange.Recheck] (directory-level event with no path).
     * Compound event bits → falls to [FolderChange.Recheck] (safe conservative fallback).
     */
    private fun mapToChange(event: Int, fileName: String?, source: StatusSource): FolderChange {
        if (fileName == null) return FolderChange.Recheck
        return when (event and FileObserver.ALL_EVENTS) {
            FileObserver.CLOSE_WRITE,
            FileObserver.MOVED_TO   -> FolderChange.Added(source, fileName)
            FileObserver.DELETE,
            FileObserver.MOVED_FROM -> FolderChange.Removed(source, fileName)
            else                    -> FolderChange.Recheck
        }
    }
}
