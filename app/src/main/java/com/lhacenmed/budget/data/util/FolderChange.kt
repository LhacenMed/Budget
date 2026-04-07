package com.lhacenmed.budget.data.util

import com.lhacenmed.budget.data.model.StatusSource

/**
 * Typed inotify event emitted by [StatusFolderObserver].
 *
 * [Added] and [Removed] carry the filename so the consumer can apply
 * incremental state mutations with zero I/O.
 * [Recheck] signals a batch or untracked change — consumer should fall
 * back to a full re-scan (debounced).
 */
sealed class FolderChange {
    /** A new media file appeared in the watched directory. */
    data class Added(val source: StatusSource, val fileName: String) : FolderChange()
    /** A media file was deleted or moved out of the watched directory. */
    data class Removed(val source: StatusSource, val fileName: String) : FolderChange()
    /** Batch or unclassified change — schedule a full re-scan. */
    data object Recheck : FolderChange()
}
