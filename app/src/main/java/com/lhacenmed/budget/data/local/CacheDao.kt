package com.lhacenmed.budget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lhacenmed.budget.data.model.BudgetContribution
import com.lhacenmed.budget.data.model.SpendingItem

@Dao
interface CacheDao {

    // ── Spending ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM cached_spending_items ORDER BY createdAt ASC")
    suspend fun readSpending(): List<SpendingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpending(items: List<SpendingItem>)

    @Query("DELETE FROM cached_spending_items")
    suspend fun clearSpending()

    @Transaction
    suspend fun replaceSpending(items: List<SpendingItem>) {
        clearSpending()
        insertSpending(items)
    }

    // ── Contributions ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM cached_contributions ORDER BY createdAt DESC")
    suspend fun readContributions(): List<BudgetContribution>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContributions(items: List<BudgetContribution>)

    @Query("DELETE FROM cached_contributions")
    suspend fun clearContributions()

    @Transaction
    suspend fun replaceContributions(items: List<BudgetContribution>) {
        clearContributions()
        insertContributions(items)
    }
}
