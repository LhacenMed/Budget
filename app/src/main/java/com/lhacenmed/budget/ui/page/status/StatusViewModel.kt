package com.lhacenmed.budget.ui.page.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhacenmed.budget.data.model.StatusItem
import com.lhacenmed.budget.data.model.StatusSource
import com.lhacenmed.budget.data.repository.StatusSaverRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val hasPermission: Boolean        = false,
    val whatsapp: AppStatuses         = AppStatuses(),
    val business: AppStatuses         = AppStatuses(),
    val isLoading: Boolean            = false,
    val isRefreshingWhatsapp: Boolean = false,
    val isRefreshingBusiness: Boolean = false,
    val savingUri: Uri?               = null,
    val message: String?              = null,
    // Held here so MediaPreviewPage can read it via the shared VM instance
    // without needing to pass a StatusItem through nav arguments
    val previewItem: StatusItem?      = null
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val repository: StatusSaverRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StatusUiState())
    val state = _state.asStateFlow()

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
     * Pull-to-refresh for a single app's folder.
     * Only re-traverses that app's SAF subtree, leaving the other source untouched.
     */
    fun refresh(source: StatusSource) = viewModelScope.launch {
        val treeUri = repository.getSavedUri() ?: return@launch

        _state.update {
            if (source == StatusSource.WHATSAPP) it.copy(isRefreshingWhatsapp = true)
            else                                 it.copy(isRefreshingBusiness = true)
        }

        val fresh = repository.getStatusesForSource(treeUri, source)
        val freshStatuses = AppStatuses(
            images = fresh.filter { !it.isVideo },
            videos = fresh.filter {  it.isVideo }
        )

        _state.update { s ->
            if (source == StatusSource.WHATSAPP)
                s.copy(whatsapp = freshStatuses, isRefreshingWhatsapp = false)
            else
                s.copy(business = freshStatuses, isRefreshingBusiness = false)
        }

        // Persist merged cache so the next cold start reflects the refresh
        val wa  = _state.value.whatsapp.images + _state.value.whatsapp.videos
        val biz = _state.value.business.images + _state.value.business.videos
        repository.saveCache(wa + biz)
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

    /** First-time load (no cache): shows spinner, then populates state. */
    private fun fullLoad(treeUri: Uri) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, hasPermission = true) }
        val all = repository.getStatuses(treeUri)
        applyItems(all, isLoading = false)
    }

    /** Background refresh after serving cached data — no spinner shown. */
    private suspend fun silentFullRefresh(treeUri: Uri) {
        val all = repository.getStatuses(treeUri)
        if (all.isNotEmpty()) applyItems(all, isLoading = false)
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
}
