package com.lhacenmed.budget.ui.page.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lhacenmed.budget.data.model.DisplaySpendingItem
import com.lhacenmed.budget.ui.common.format

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeContent(
    state: HomeUiState,
    padding: PaddingValues,
    listState: LazyListState,
    onDelete: (DisplaySpendingItem) -> Unit,
    onRetry: (Int) -> Unit,
    onAddFunds: () -> Unit,
    onRefresh: () -> Unit,
    onNavigateToAuth: () -> Unit
) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    Column(Modifier.padding(padding).fillMaxSize()) {
        if (!state.isAuthenticated) {
            AuthPlaceholder(
                message = "Sign in to track your spendings and sync with others.",
                onNavigateToAuth = onNavigateToAuth
            )
            return@Column
        }

        if (!state.isOnline || state.pendingCount > 0) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    text     = if (!state.isOnline) "You're offline — items will sync when reconnected"
                    else "${state.pendingCount} item(s) syncing…",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        BudgetSummaryRow(daySpent = state.daySpent, remaining = state.remaining, onAddFunds = onAddFunds)
        DayContent(
            modifier     = Modifier.weight(1f),
            items        = state.items,
            listState    = listState,
            isRefreshing = state.isRefreshing,
            onDelete     = onDelete,
            onRetry      = onRetry,
            onRefresh    = onRefresh
        )
    }
}

@Composable
private fun BudgetSummaryRow(daySpent: Float, remaining: Float, onAddFunds: () -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.weight(1f),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's Spending", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text("${daySpent.format()} dh", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Card(
            onClick  = onAddFunds,
            modifier = Modifier.weight(1f),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Remaining", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "${remaining.format()} dh",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = if (remaining < 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text("Tap to add funds", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayContent(
    modifier: Modifier,
    items: List<DisplaySpendingItem>,
    listState: LazyListState,
    isRefreshing: Boolean,
    onDelete: (DisplaySpendingItem) -> Unit,
    onRetry: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = modifier) {
        Text(
            "Spendings",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh    = onRefresh,
            modifier     = Modifier.weight(1f)
        ) {
            LazyColumn(
                state               = listState,
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (items.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No spendings yet. Tap + to add.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(items, key = { it.stableKey }) { item ->
                        SpendingItemCard(
                            item     = item,
                            onDelete = { onDelete(item) },
                            onRetry  = item.localId?.let { { onRetry(it) } }
                        )
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Day total", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Text("${items.sumOf { it.price.toDouble() }.format()} dh",
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.titleMedium,
                    color      = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SpendingItemCard(
    item:    DisplaySpendingItem,
    onDelete: () -> Unit,
    onRetry:  (() -> Unit)?   // non-null only for pending items
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = if (item.isPending)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        else
            CardDefaults.cardColors()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically) {
                    Text(
                        text       = item.name,
                        fontWeight = FontWeight.Medium,
                        color      = if (item.isPending)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    if (!item.quantity.isNullOrBlank()) {
                        Text(item.quantity, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!item.description.isNullOrBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Shopper line — pending items show "Pending" badge inline
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("by ${item.shopper}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                    if (item.isPending) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                "Pending",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Text(
                "${item.price.format()} dh",
                fontWeight = FontWeight.SemiBold,
                color      = if (item.isPending)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.onSurface
            )

            // Retry button — only for pending items
            if (item.isPending && onRetry != null) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint               = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Delete — always present (cancels pending send, or deletes synced)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun AuthPlaceholder(message: String, onNavigateToAuth: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToAuth) {
            Text("Sign In / Register")
        }
    }
}

private fun Double.format() = this.toFloat().format()
