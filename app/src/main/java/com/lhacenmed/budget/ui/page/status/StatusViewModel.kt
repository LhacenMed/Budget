package com.lhacenmed.budget.ui.page.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.model.StatusSource
import com.lhacenmed.budget.data.repository.StatusSaverRepository
import com.lhacenmed.budget.data.util.FolderChange
import com.lhacenmed.budget.data.util.StatusFolderObserver
import com.lhacenmed.budget.data.util.WatchedFolder
import com.lhacenmed.budget.util.PreferenceUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Statuses for a single app, split by media type. */
data class AppStatuses(
    val images: List<StatusItem> = emptyList(),
    val videos: List<StatusItem> = emptyList()
)

data class StatusUiState(
    val hasPermission: Boolean            = false,
    val whatsapp: AppStatuses             = AppStatuses(),
    val business: AppStatuses             = AppStatuses(),
    /** Which app sources are currently shown. Never empty — VM enforces at least one. */
    val visibleSources: Set<StatusSource> = setOf(StatusSource.WHATSAPP, StatusSource.WHATSAPP_BUSINESS),
    val isLoading: Boolean                = false,
    val isRefreshing: Boolean             = false,
    val savingUri: Uri?                   = null,
    val message: String?                  = null,
    // Held here so MediaPreviewPage can read it via the shared VM instance
    // without needing to pass a StatusItem through nav arguments
    val previewItem: StatusItem?          = null
) {
    /** Merged images from all visible sources, sorted newest-first. */
    val images: List<StatusItem> get() {
        val wa  = if (StatusSource.WHATSAPP          in visibleSources) whatsapp.images else emptyList()
        val biz = if (StatusSource.WHATSAPP_BUSINESS in visibleSources) business.images else emptyList()
        return (wa + biz).sortedByDescending { it.name }
    }

    /** Merged videos from all visible sources, sorted newest-first. */
    val videos: List<StatusItem> get() {
        val wa  = if (StatusSource.WHATSAPP          in visibleSources) whatsapp.videos else emptyList()
        val biz = if (StatusSource.WHATSAPP_BUSINESS in visibleSources) business.videos else emptyList()
        return (wa + biz).sortedByDescending { it.name }
    }
}

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val repository: StatusSaverRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StatusUiState())
    val state = _state.asStateFlow()

    /**
     * Resolved handles for each app's `.Statuses` directory.
     * Populated by [boot]; consumed by [startObserving], [handleAdded], and [refresh].
     */
    private var watchedFolders: List<WatchedFolder> = emptyList()

    /**
     * Coroutine collecting [StatusFolderObserver.changes].
     * Started once inside [boot] and kept alive for the VM's full lifetime —
     * the observer is never paused between tab switches.
     */
    private var observerJob: Job? = null

    companion object {
        /**
         * Debounce window applied to [FolderChange.Recheck] events only.
         * [FolderChange.Added] and [FolderChange.Removed] are applied immediately.
         */
        private const val RECHECK_DEBOUNCE_MS = 500L
    }

    init {
        // Restore persisted source visibility before serving any content
        _state.update { it.copy(visibleSources = PreferenceUtil.visibleStatusSources.value) }

        repository.getSavedUri()?.let { savedUri ->
            val cached = repository.getCachedStatuses()

            if (cached.isNotEmpty()) {
                // Serve cache instantly — zero UI delay on warm start
                applyItems(cached, isLoading = false)
            } else {
                // No cache yet: show spinner while boot() does the initial load
                _state.update { it.copy(isLoading = true, hasPermission = true) }
            }

            viewModelScope.launch { boot(savedUri) }
        }
    }

    fun onPermissionGranted(uri: Uri?) {
        if (uri == null) return
        repository.persistUri(uri)
        // Clear cached folder doc IDs — a new tree URI may point to a different structure
        repository.clearFolderDocIds()
        stopObserving()
        _state.update { it.copy(isLoading = true, hasPermission = true) }
        viewModelScope.launch { boot(uri) }
    }

    fun openPreview(item: StatusItem)  = _state.update { it.copy(previewItem = item) }
    fun closePreview()                 = _state.update { it.copy(previewItem = null) }
    fun clearMessage()                 = _state.update { it.copy(message = null)     }

    /**
     * Toggles a source's visibility. Enforces that at least one source stays visible —
     * attempting to remove the last visible source is a no-op.
     * Persists the new selection to DataStore so it survives app restarts.
     */
    fun toggleSource(source: StatusSource) {
        _state.update { s ->
            val next = s.visibleSources.toMutableSet()
            if (source in next && next.size > 1) next.remove(source) else next.add(source)
            s.copy(visibleSources = next)
        }
        PreferenceUtil.setVisibleStatusSources(_state.value.visibleSources)
    }

    /**
     * Manual pull-to-refresh.
     *
     * Uses the in-memory [watchedFolders] handles for a fast refresh —
     * one [ContentResolver.query] IPC per folder with no SAF traversal.
     */
    fun refresh() = viewModelScope.launch {
        val treeUri = repository.getSavedUri() ?: return@launch
        _state.update { it.copy(isRefreshing = true) }

        val all = if (watchedFolders.isNotEmpty()) {
            // Fast path: one IPC per folder, no directory traversal
            repository.getStatusesFast(watchedFolders)
        } else {
            // Fallback: folders not yet resolved — use full scan
            repository.getStatuses(treeUri)
        }

        applyItems(all, isLoading = false)
        _state.update { it.copy(isRefreshing = false) }
    }

    fun saveStatus(item: StatusItem) = viewModelScope.launch {
        _state.update { it.copy(savingUri = item.uri) }
        val success = repository.saveStatus(item)
        _state.update { it.copy(
            savingUri = null,
            message   = if (success) "Saved to gallery" else "Failed to save"
        )}
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Single entry point for all startup and permission-grant flows:
     *
     * 1. **Resolve folders** — reads cached folder doc IDs from SharedPrefs (zero IPC
     *    on every launch after the first); falls back to a one-time SAF traversal
     *    on first-ever launch or after permission is re-granted.
     * 2. **Start inotify observer** — immediately after folders are known.
     * 3. **Background fast refresh** — catches any statuses added or removed while
     *    the app was closed; costs one [ContentResolver.query] IPC per folder.
     */
    private suspend fun boot(treeUri: Uri) {
        val folders = repository.resolveWatchedFolders(treeUri)

        if (folders.isEmpty()) {
            _state.update { it.copy(hasPermission = false, isLoading = false) }
            return
        }

        watchedFolders = folders
        startObserving()

        // Catch any changes that occurred while the app was closed.
        // getStatusesFast → one ContentResolver.query IPC per folder, saves cache.
        val all = repository.getStatusesFast(folders)
        applyItems(all, isLoading = false)
    }

    /**
     * Starts the [StatusFolderObserver] and routes each [FolderChange] to the
     * appropriate handler:
     *
     * - [FolderChange.Added]   → [handleAdded]   — O(1) URI construction, prepended to state
     * - [FolderChange.Removed] → [handleRemoved] — item filtered from state by name
     * - [FolderChange.Recheck] → debounced fast re-scan (batch/untracked events only)
     *
     * No-ops if the observer is already running or if no paths have been resolved yet.
     */
    @OptIn(FlowPreview::class)
    private fun startObserving() {
        if (observerJob?.isActive == true || watchedFolders.isEmpty()) return
        observerJob = viewModelScope.launch {
            val changes = StatusFolderObserver(watchedFolders)
                .changes
                .shareIn(this, SharingStarted.Eagerly)

            // Incremental updates — immediate, zero I/O
            launch {
                changes.collect { change ->
                    when (change) {
                        is FolderChange.Added   -> handleAdded(change)
                        is FolderChange.Removed -> handleRemoved(change)
                        FolderChange.Recheck    -> Unit
                    }
                }
            }

            // Full re-scan for batch/untracked events — debounced, uses fast path
            changes
                .filter { it is FolderChange.Recheck }
                .debounce(RECHECK_DEBOUNCE_MS)
                .collect {
                    val folders = watchedFolders
                    if (folders.isNotEmpty()) {
                        val all = repository.getStatusesFast(folders)
                        applyItems(all, isLoading = false)
                    }
                }
        }
    }

    private fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
    }

    /**
     * Prepends a single newly-detected status to the in-memory list with zero I/O.
     *
     * The [repository] constructs the content URI from [WatchedFolder.folderDocId] +
     * filename — pure [android.provider.DocumentsContract] string math, no SAF traversal.
     *
     * Duplicate guard: WhatsApp fires both CLOSE_WRITE and MOVED_TO for the same file
     * (temp-file → rename sequence). The name-equality check silently drops the second event.
     */
    private fun handleAdded(change: FolderChange.Added) {
        val folder = watchedFolders.find { it.source == change.source } ?: return
        val item   = repository.resolveSingleStatus(folder, change.fileName) ?: return
        _state.update { s ->
            when (item.source) {
                StatusSource.WHATSAPP -> {
                    val wa = s.whatsapp
                    if (item.isVideo) {
                        if (wa.videos.any { it.name == item.name }) return@update s
                        s.copy(whatsapp = wa.copy(videos = listOf(item) + wa.videos))
                    } else {
                        if (wa.images.any { it.name == item.name }) return@update s
                        s.copy(whatsapp = wa.copy(images = listOf(item) + wa.images))
                    }
                }
                StatusSource.WHATSAPP_BUSINESS -> {
                    val biz = s.business
                    if (item.isVideo) {
                        if (biz.videos.any { it.name == item.name }) return@update s
                        s.copy(business = biz.copy(videos = listOf(item) + biz.videos))
                    } else {
                        if (biz.images.any { it.name == item.name }) return@update s
                        s.copy(business = biz.copy(images = listOf(item) + biz.images))
                    }
                }
            }
        }
    }

    /** Removes a deleted/moved-out status from the in-memory list by name. */
    private fun handleRemoved(change: FolderChange.Removed) {
        _state.update { s ->
            when (change.source) {
                StatusSource.WHATSAPP -> {
                    val wa = s.whatsapp
                    s.copy(whatsapp = wa.copy(
                        images = wa.images.filter { it.name != change.fileName },
                        videos = wa.videos.filter { it.name != change.fileName }
                    ))
                }
                StatusSource.WHATSAPP_BUSINESS -> {
                    val biz = s.business
                    s.copy(business = biz.copy(
                        images = biz.images.filter { it.name != change.fileName },
                        videos = biz.videos.filter { it.name != change.fileName }
                    ))
                }
            }
        }
    }

    private fun applyItems(all: List<StatusItem>, isLoading: Boolean) {
        val waItems  = all.filter { it.source == StatusSource.WHATSAPP }
        val bizItems = all.filter { it.source == StatusSource.WHATSAPP_BUSINESS }
        _state.update { it.copy(
            isLoading     = isLoading,
            hasPermission = all.isNotEmpty() || repository.getSavedUri() != null,
            whatsapp      = AppStatuses(
                images = waItems.filter  { s -> !s.isVideo },
                videos = waItems.filter  { s ->  s.isVideo }
            ),
            business      = AppStatuses(
                images = bizItems.filter { s -> !s.isVideo },
                videos = bizItems.filter { s ->  s.isVideo }
            )
        )}
    }

    override fun onCleared() {
        super.onCleared()
        stopObserving()
    }
}
