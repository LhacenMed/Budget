package com.lhacenmed.budget.data.model

/**
 * Unified display model merging synced [SpendingItem] and offline-queued
 * [com.lhacenmed.budget.data.local.PendingSpendingItem] into one type for the day list.
 *
 * Key discriminator: [isPending]
 *   false → [serverId] is the Supabase row id, [localId] is irrelevant
 *   true  → [localId] is the Room primary key used for retry/cancel
 */
data class DisplaySpendingItem(
    val localId:     Int?    = null,   // Room localId — non-null when isPending = true
    val serverId:    String  = "",     // Supabase id  — non-empty when isPending = false
    val date:        String,
    val shopper:     String,
    val name:        String,
    val quantity:    String? = null,
    val price:       Float,
    val description: String? = null,
    val isPending:   Boolean = false
) {
    /** Stable key for LazyColumn — pending items use their local id, synced use server id. */
    val stableKey: String get() = if (isPending) "pending_$localId" else serverId
}
