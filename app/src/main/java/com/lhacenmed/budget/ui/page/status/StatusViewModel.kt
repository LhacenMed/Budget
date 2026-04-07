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
import kotlinx.coroutines.async
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
     * Populated by [resolvePaths]; consumed by [startObserving] and [handleAdded].
     */
    private var watchedFolders: List<WatchedFolder> = emptyList()

    /**
     * Coroutine collecting [StatusFolderObserver.changes].
     * Started once paths are resolved and kept alive for the VM's full lifetime —
     * observer is never paused between tab switches.
     */
    private var observerJob: Job? = null

    companion object {
        /**
         * Debounce window applied to [FolderChange.Recheck] events only.
         * [FolderChange.Added] and [FolderChange.Removed] are applied immediately with zero delay.
         */
        private const val RECHECK_DEBOUNCE_MS = 500L
    }

    init {
        // Restore persisted source visibility before serving any content
        _state.update { it.copy(visibleSources = PreferenceUtil.visibleStatusSources.value) }

        val cached = repository.getCachedStatuses()
        if (cached.isNotEmpty()) {
            // Serve cache immediately — zero loading delay on cold start.
            applyItems(cached, isLoading = false)
            repository.getSavedUri()?.let { uri ->
                viewModelScope.launch {
                    // Resolve observer paths and silently refresh in parallel.
                    // startObserving() is called inside resolvePaths once folders are ready.
                    val pathsJob   = launch { resolvePaths(uri) }
                    val refreshJob = launch { silentFullRefresh(uri) }
                    pathsJob.join()
                    refreshJob.join()
                }
            }
        } else {
            repository.getSavedUri()?.let { uri ->
                resolvePaths(uri)
                fullLoad(uri)
            }
        }
    }

    fun onPermissionGranted(uri: Uri?) {
        if (uri == null) return
        repository.persistUri(uri)
        // Stop any existing observer before resolving new paths
        stopObserving()
        resolvePaths(uri)
        fullLoad(uri)
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
     * Both app source scans run in parallel via [async].
     */
    fun refresh() = viewModelScope.launch {
        val treeUri = repository.getSavedUri() ?: return@launch
        _state.update { it.copy(isRefreshing = true) }

        val waDeferred  = async { repository.getStatusesForSource(treeUri, StatusSource.WHATSAPP) }
        val bizDeferred = async { repository.getStatusesForSource(treeUri, StatusSource.WHATSAPP_BUSINESS) }

        val all = waDeferred.await() + bizDeferred.await()
        applyItems(all, isLoading = false)
        if (all.isNotEmpty()) repository.saveCache(all)
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
     * Resolves [WatchedFolder] handles from [treeUri], then immediately starts the
     * inotify observer for the VM's full lifetime regardless of which tab is active.
     */
    private fun resolvePaths(treeUri: Uri) = viewModelScope.launch {
        watchedFolders = repository.resolveWatchedFolders(treeUri)
        startObserving()
    }

    /**
     * Starts the [StatusFolderObserver] and routes each [FolderChange] to the
     * appropriate handler:
     *
     * - [FolderChange.Added]   → [handleAdded]   — O(1) URI construction, prepended to state
     * - [FolderChange.Removed] → [handleRemoved] — item filtered from state by name
     * - [FolderChange.Recheck] → debounced full re-scan (batch/untracked events only)
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

            // Full re-scan for batch/untracked events — debounced
            changes
                .filter { it is FolderChange.Recheck }
                .debounce(RECHECK_DEBOUNCE_MS)
                .collect { repository.getSavedUri()?.let { silentFullRefresh(it) } }
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

    /** First-time load (no cache): shows spinner, then populates state. */
    private fun fullLoad(treeUri: Uri) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, hasPermission = true) }
        val all = repository.getStatuses(treeUri)
        applyItems(all, isLoading = false)
    }

    /** Background re-scan — no spinner, applies result regardless of empty/non-empty. */
    private suspend fun silentFullRefresh(treeUri: Uri) {
        val all = repository.getStatuses(treeUri)
        applyItems(all, isLoading = false)
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
