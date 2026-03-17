package com.lhacenmed.budget.ui.page.grocery

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhacenmed.budget.data.local.GroceryItem
import com.lhacenmed.budget.data.model.SpendingItem
import com.lhacenmed.budget.data.repository.GroceryRepository
import com.lhacenmed.budget.data.repository.SpendingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class GroceryViewModel @Inject constructor(
    private val groceryRepository: GroceryRepository,
    private val spendingRepository: SpendingRepository,
    supabase: SupabaseClient
) : ViewModel() {

    private val userId = supabase.auth.currentUserOrNull()?.id.orEmpty()

    val shopperName: String = run {
        val user = supabase.auth.currentUserOrNull() ?: return@run "Me"
        user.userMetadata
            ?.get("display_name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
            ?: "Me"
    }

    val items: StateFlow<List<GroceryItem>> = groceryRepository.getItems(userId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addItem(name: String) = viewModelScope.launch {
        groceryRepository.addItem(userId, name.trim())
    }

    fun toggleItem(item: GroceryItem) = viewModelScope.launch {
        groceryRepository.toggleItem(item)
    }

    fun updateItem(id: Int, name: String) = viewModelScope.launch {
        groceryRepository.updateName(id, name.trim())
    }

    fun deleteItem(id: Int) = viewModelScope.launch {
        groceryRepository.delete(id)
    }

    fun addToSpendings(parsed: List<ParsedSpending>) = viewModelScope.launch {
        val today = LocalDate.now().toString()
        parsed.forEach { p ->
            spendingRepository.addItem(
                SpendingItem(
                    date     = today,
                    shopper  = shopperName,
                    name     = p.name,
                    quantity = p.quantity,
                    price    = p.price
                )
            )
        }
    }
}

data class ParsedSpending(
    val name: String,
    val quantity: String?,
    val price: Float
)

fun parseGroceryName(name: String): ParsedSpending? {
    val parts = name.split(",").map { it.trim() }.filter { it.isNotBlank() }
    return when (parts.size) {
        2    -> parts[1].toFloatOrNull()?.let { price ->
            ParsedSpending(name = parts[0], quantity = null, price = price)
        }
        3    -> parts[2].toFloatOrNull()?.let { price ->
            ParsedSpending(name = parts[0], quantity = parts[1], price = price)
        }
        else -> null
    }
}
