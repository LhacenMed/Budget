package com.lhacenmed.budget.ui.page.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhacenmed.budget.data.model.BudgetContribution
import com.lhacenmed.budget.data.model.SpendingItem
import com.lhacenmed.budget.data.repository.SpendingRepository
import com.lhacenmed.budget.data.util.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
data class HomeUiState(
    val days: List<String> = emptyList(),
    val selectedDay: String = LocalDate.now().toString(),
    val items: List<SpendingItem> = emptyList(),
    val allSpending: List<SpendingItem> = emptyList(),
    val contributions: List<BudgetContribution> = emptyList(),
    val totalBudget: Float = 0f,
    val totalSpent: Float = 0f,
    val currentUserName: String = "",
    val currentUserEmail: String = "",
    val isOnline: Boolean = true,
    val isRefreshing: Boolean = false,
    val pendingCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val remaining get() = totalBudget - totalSpent
    val daySpent  get() = items.sumOf { it.price.toDouble() }.toFloat()
}

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SpendingRepository,
    private val supabase: SupabaseClient,
    private val connectivity: ConnectivityObserver
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    init {
        loadCurrentUser()
        viewModelScope.launch {
            // 1. Instant cache — UI populated before any network call
            val (cached, cachedContributions) = repository.getCachedSnapshot()
            if (cached.isNotEmpty() || cachedContributions.isNotEmpty()) {
                applyData(cached, cachedContributions)
            }

            // 2. Read current connectivity state once (no subscription yet).
            //    If online, drain any pending items that were queued while offline.
            //    This is the fix: syncPending must run on every online startup, not
            //    just on offline→online transitions observed after the app is running.
            val isOnlineNow = connectivity.isOnline.first()
            _state.update { it.copy(isOnline = isOnlineNow) }
            if (isOnlineNow) repository.syncPending()

            // 3. Background network fetch
            fetchFromNetwork()
        }
        observeConnectivity()
    }

    fun refresh() = viewModelScope.launch { fetchFromNetwork() }

    fun selectDay(date: String) {
        _state.update { it.copy(selectedDay = date, items = it.allSpending.itemsFor(date)) }
    }

    fun addItem(name: String, quantity: String?, price: Float, description: String?) = viewModelScope.launch {
        val item = SpendingItem(
            date        = _state.value.selectedDay,
            shopper     = _state.value.currentUserName,
            name        = name,
            quantity    = quantity,
            price       = price,
            description = description?.takeIf { it.isNotBlank() }
        )
        runCatching { repository.addItem(item) }
            .onSuccess { if (_state.value.isOnline) refresh() else updatePendingCount() }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    fun addContribution(amount: Float) = viewModelScope.launch {
        val contribution = BudgetContribution(contributor = _state.value.currentUserName, amount = amount)
        runCatching { repository.addContribution(contribution) }
            .onSuccess { refresh() }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    fun deleteItem(id: String) = viewModelScope.launch {
        runCatching { repository.deleteItem(id) }
            .onSuccess { refresh() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun loadCurrentUser() {
        val user = supabase.auth.currentUserOrNull() ?: return
        val name = user.userMetadata
            ?.get("display_name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
            ?: "Me"
        _state.update { it.copy(currentUserName = name, currentUserEmail = user.email.orEmpty()) }
    }

    private fun observeConnectivity() = viewModelScope.launch {
        var previousOnline: Boolean? = null
        connectivity.isOnline.collect { online ->
            _state.update { it.copy(isOnline = online) }
            when {
                online && previousOnline == false -> { repository.syncPending(); refresh() }
                !online -> updatePendingCount()
            }
            previousOnline = online
        }
    }

    private suspend fun fetchFromNetwork() {
        val hasCache = _state.value.allSpending.isNotEmpty() || _state.value.contributions.isNotEmpty()
        _state.update { if (hasCache) it.copy(isRefreshing = true) else it.copy(isLoading = true) }

        runCatching<Pair<List<SpendingItem>, List<BudgetContribution>>> {
            coroutineScope {
                val s = async { repository.getAllSpending() }
                val c = async { repository.getContributions() }
                s.await() to c.await()
            }
        }.onSuccess { (spending, contributions) ->
            applyData(spending, contributions)
        }.onFailure { e ->
            _state.update { it.copy(isLoading = false, isRefreshing = false, error = e.message) }
        }
    }

    private fun applyData(spending: List<SpendingItem>, contributions: List<BudgetContribution>) {
        val today = LocalDate.now().toString()
        val days  = (listOf(today) + spending.map { it.date }.distinct().sortedDescending()).distinct()
        _state.update { current ->
            current.copy(
                days          = days,
                allSpending   = spending,
                contributions = contributions,
                totalBudget   = contributions.sumOf { it.amount.toDouble() }.toFloat(),
                totalSpent    = spending.sumOf { it.price.toDouble() }.toFloat(),
                items         = spending.itemsFor(current.selectedDay),
                isLoading     = false,
                isRefreshing  = false,
                error         = null
            )
        }
        updatePendingCount()
    }

    private fun updatePendingCount() = viewModelScope.launch {
        _state.update { it.copy(pendingCount = repository.pendingCount()) }
    }
}

private fun List<SpendingItem>.itemsFor(date: String) =
    filter { it.date == date }.sortedBy { it.createdAt }
