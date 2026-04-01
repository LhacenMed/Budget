package com.lhacenmed.budget.ui.page.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.model.StatusSource
import com.lhacenmed.budget.data.repository.StatusSaverRepository
import com.lhacenmed.budget.data.util.StatusFolderObserver
import com.lhacenmed.budget.util.PreferenceUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
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
     * Real filesystem paths of the `.Statuses` directories, resolved once from the
     * SAF tree URI. Populated by [resolvePaths]; used by [startObserving].
     */
    private var observerPaths: List<String> = emptyList()

    /**
     * Whether the status tab is currently in the foreground.
     * Tracked so [resolvePaths] can auto-start observation if the tab is already
     * active by the time path resolution completes.
     */
    private var isTabActive = false

    /** Coroutine collecting [StatusFolderObserver.changes]. Null when tab is inactive. */
    private var observerJob: Job? = null

    companion object {
        /**
         * Debounce window applied to raw inotify events.
         * WhatsApp writes a file in multiple steps (temp name → rename), so several
         * events fire per status. Debouncing coalesces them into a single re-scan.
         */
        private const val OBSERVER_DEBOUNCE_MS = 500L
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
                    // Resolve observer paths and silently refresh in parallel
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
        // Resolve new paths then load; previous observer (if any) is still stopped
        // until setActive(true) is called again by the UI
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
     * Called by the UI when the status tab becomes active or inactive.
     *
     * Active  → starts [StatusFolderObserver] collection so any inotify event on
     *           a `.Statuses` directory triggers an immediate silent re-scan.
     * Inactive → cancels the observer to release the inotify watch descriptors.
     */
    fun setActive(active: Boolean) {
        isTabActive = active
        if (active) startObserving() else stopObserving()
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
     * Resolves the real filesystem paths of `.Statuses` directories from [treeUri].
     * Once resolved, starts observing immediately if the tab is currently active.
     */
    private fun resolvePaths(treeUri: Uri) = viewModelScope.launch {
        observerPaths = repository.resolveStatusFolderPaths(treeUri)
        if (isTabActive) startObserving()
    }

    /**
     * Starts collecting [StatusFolderObserver.changes].
     * Each inotify event is debounced before triggering a silent re-scan, so rapid
     * multi-event writes (e.g. WhatsApp's temp-file-then-rename sequence) produce
     * exactly one re-scan.
     *
     * No-ops if the observer is already running or if no paths have been resolved yet.
     */
    private fun startObserving() {
        if (observerJob?.isActive == true || observerPaths.isEmpty()) return
        observerJob = viewModelScope.launch {
            StatusFolderObserver(observerPaths)
                .changes
                .debounce(OBSERVER_DEBOUNCE_MS)
                .collect {
                    repository.getSavedUri()?.let { silentFullRefresh(it) }
                }
        }
    }

    private fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
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
