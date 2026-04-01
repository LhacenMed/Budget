package com.lhacenmed.budget.ui.page.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.model.StatusSource
import com.lhacenmed.budget.data.repository.StatusSaverRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Statuses for a single app, split by media type. */
data class AppStatuses(
    val images: List<StatusItem> = emptyList(),
    val videos: List<StatusItem> = emptyList()
)

data class StatusUiState(
    val hasPermission: Boolean             = false,
    val whatsapp: AppStatuses              = AppStatuses(),
    val business: AppStatuses             = AppStatuses(),
    /** Which app sources are currently shown. Never empty — VM enforces at least one. */
    val visibleSources: Set<StatusSource>  = setOf(StatusSource.WHATSAPP, StatusSource.WHATSAPP_BUSINESS),
    val isLoading: Boolean                 = false,
    val isRefreshing: Boolean              = false,
    val savingUri: Uri?                    = null,
    val message: String?                   = null,
    // Held here so MediaPreviewPage can read it via the shared VM instance
    // without needing to pass a StatusItem through nav arguments
    val previewItem: StatusItem?           = null
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

    private var livePollingJob: Job? = null

    companion object {
        /** Interval between background re-scans while the status tab is active. */
        private const val LIVE_POLL_INTERVAL_MS = 20_000L
    }

    init {
        val cached = repository.getCachedStatuses()
        if (cached.isNotEmpty()) {
            // Serve cache immediately — zero loading delay on cold start.
            applyItems(cached, isLoading = false)
            // Silently refresh in the background; UI updates when done.
            repository.getSavedUri()?.let { uri ->
                viewModelScope.launch { silentFullRefresh(uri) }
            }
        } else {
            repository.getSavedUri()?.let { fullLoad(it) }
        }
    }

    fun onPermissionGranted(uri: Uri?) {
        if (uri == null) return
        repository.persistUri(uri)
        fullLoad(uri)
    }

    fun openPreview(item: StatusItem)  = _state.update { it.copy(previewItem = item) }
    fun closePreview()                 = _state.update { it.copy(previewItem = null) }
    fun clearMessage()                 = _state.update { it.copy(message = null)     }

    /**
     * Toggles a source's visibility. Enforces that at least one source stays visible —
     * attempting to remove the last visible source is a no-op.
     */
    fun toggleSource(source: StatusSource) {
        _state.update { s ->
            val next = s.visibleSources.toMutableSet()
            if (source in next && next.size > 1) next.remove(source) else next.add(source)
            s.copy(visibleSources = next)
        }
    }

    /**
     * Called by the UI when the status tab becomes active or inactive.
     * Active  → starts a periodic silent re-scan that detects new statuses automatically.
     * Inactive → cancels the poll to avoid unnecessary background I/O.
     */
    fun setActive(active: Boolean) {
        if (active) startLivePolling() else stopLivePolling()
    }

    /** Manual pull-to-refresh — full scan, shows the refresh indicator. */
    fun refresh() = viewModelScope.launch {
        val treeUri = repository.getSavedUri() ?: return@launch
        _state.update { it.copy(isRefreshing = true) }
        val all = repository.getStatuses(treeUri)
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

    private fun startLivePolling() {
        if (livePollingJob?.isActive == true) return
        livePollingJob = viewModelScope.launch {
            while (true) {
                delay(LIVE_POLL_INTERVAL_MS)
                repository.getSavedUri()?.let { silentFullRefresh(it) }
            }
        }
    }

    private fun stopLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = null
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
        stopLivePolling()
    }
}
