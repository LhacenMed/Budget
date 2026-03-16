package com.lhacenmed.budget.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import com.lhacenmed.budget.data.local.AppDatabase
import com.lhacenmed.budget.data.local.PendingSpendingItem
import com.lhacenmed.budget.data.model.BudgetContribution
import com.lhacenmed.budget.data.model.SpendingItem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@RequiresApi(Build.VERSION_CODES.O)
@Singleton
class SpendingRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val db: AppDatabase
) {
    private val pending get() = db.pendingItemDao()
    private val cache   get() = db.cacheDao()

    // ── Cache reads (instant, offline-safe) ───────────────────────────────────

    suspend fun getCachedSnapshot(): Pair<List<SpendingItem>, List<BudgetContribution>> =
        cache.readSpending() to cache.readContributions()

    // ── Network fetches (update cache on success) ─────────────────────────────

    suspend fun getAllSpending(): List<SpendingItem> =
        supabase.from("spending_items").select().decodeList<SpendingItem>()
            .also { cache.replaceSpending(it) }

    suspend fun getContributions(): List<BudgetContribution> =
        supabase.from("budget_contributions")
            .select { order("created_at", Order.DESCENDING) }
            .decodeList<BudgetContribution>()
            .also { cache.replaceContributions(it) }

    // ── Mutations ─────────────────────────────────────────────────────────────

    suspend fun addItem(item: SpendingItem) {
        val localId = pending.insert(item.toPending()).toInt()
        runCatching {
            supabase.from("spending_items").insert(item)
            pending.deleteById(localId)
        }
    }

    suspend fun syncPending() {
        pending.getAll().forEach { p ->
            runCatching {
                supabase.from("spending_items").insert(p.toSpendingItem())
                pending.deleteById(p.localId)
            }
        }
    }

    suspend fun pendingCount(): Int = pending.count()

    suspend fun deleteItem(id: String) =
        supabase.from("spending_items").delete { filter { eq("id", id) } }

    suspend fun addContribution(contribution: BudgetContribution) =
        supabase.from("budget_contributions").insert(contribution)

    // ── Mappers ───────────────────────────────────────────────────────────────

    private fun SpendingItem.toPending() = PendingSpendingItem(
        date        = date,
        shopper     = shopper,
        name        = name,
        quantity    = quantity,
        price       = price,
        description = description,
        createdAt   = ZonedDateTime.now().toString()
    )

    private fun PendingSpendingItem.toSpendingItem() = SpendingItem(
        date        = date,
        shopper     = shopper,
        name        = name,
        quantity    = quantity,
        price       = price,
        description = description
    )
}
