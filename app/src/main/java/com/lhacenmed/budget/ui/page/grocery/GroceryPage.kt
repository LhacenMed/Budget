package com.lhacenmed.budget.ui.page.grocery

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lhacenmed.budget.data.local.GroceryItem
import com.lhacenmed.budget.ui.common.format
import com.lhacenmed.budget.ui.component.BudgetBottomSheet
import java.time.LocalDate

// ── Content ───────────────────────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GroceryContent(
    items: List<GroceryItem>,
    shopperName: String,
    isAuthenticated: Boolean,
    padding: PaddingValues,
    onToggle: (GroceryItem) -> Unit,
    onDelete: (Int) -> Unit,
    onEdit: (GroceryItem) -> Unit,
    onAddToSpendings: (List<ParsedSpending>) -> Unit
) {
    val today = LocalDate.now().toString()
    val pending = remember(items, today) { items.filter { it.checkedDate != today } }
    val done    = remember(items, today) { items.filter { it.checkedDate == today } }

    var showConfirmSheet by remember { mutableStateOf(false) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("No groceries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize().padding(padding),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Pending section ───────────────────────────────────────────────────
        item {
            Text(
                "To do",
                style    = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (pending.isEmpty()) {
            item {
                Text(
                    "All done!",
                    style  = MaterialTheme.typography.bodyMedium,
                    color  = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        } else {
            items(pending, key = { it.id }) { item ->
                GroceryItemCard(
                    item      = item,
                    isChecked = false,
                    onToggle  = { onToggle(item) },
                    onEdit    = { onEdit(item) },
                    onDelete  = { onDelete(item.id) }
                )
            }
        }

        // ── Done section (only when there are done items) ─────────────────────
        if (done.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Done",
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Only show button if authenticated AND at least one done item is parseable
                    val parseable = done.mapNotNull { parseGroceryName(it.name) }
                    if (isAuthenticated && parseable.isNotEmpty()) {
                        TextButton(
                            onClick      = { showConfirmSheet = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Add to Spendings", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            items(done, key = { it.id }) { item ->
                GroceryItemCard(
                    item      = item,
                    isChecked = true,
                    onToggle  = { onToggle(item) },
                    onEdit    = { onEdit(item) },
                    onDelete  = { onDelete(item.id) }
                )
            }
        }
    }

    if (showConfirmSheet) {
        val parsed = done.mapNotNull { parseGroceryName(it.name) }
        AddToSpendingsSheet(
            shopperName = shopperName,
            items       = parsed,
            onDismiss   = { showConfirmSheet = false },
            onConfirm   = { onAddToSpendings(parsed); showConfirmSheet = false }
        )
    }
}

// ── Item Card ─────────────────────────────────────────────────────────────────

@Composable
private fun GroceryItemCard(
    item: GroceryItem,
    isChecked: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier          = Modifier.padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isChecked, onCheckedChange = { onToggle() })
            Text(
                text           = item.name,
                modifier       = Modifier.weight(1f).padding(start = 4.dp),
                style          = MaterialTheme.typography.bodyLarge,
                color          = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Add to Spendings confirmation sheet ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToSpendingsSheet(
    shopperName: String,
    items: List<ParsedSpending>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BudgetBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add to Spendings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "The following items will be added to today's spendings by $shopperName:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.forEach { item ->
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.name, fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium)
                            item.quantity?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("${item.price.format()} dh", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }

            val total = items.sumOf { it.price.toDouble() }.toFloat()
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Total", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Text("${total.format()} dh", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall)
            }

            Button(
                onClick  = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add ${items.size} item${if (items.size > 1) "s" else ""} to Spendings") }
        }
    }
}

// ── Grocery Item Sheet (shared for Add & Edit) ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryItemSheet(
    title: String = "Add Grocery",
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }

    val submit = {
        if (name.isNotBlank()) {
            onConfirm(name)
            onDismiss()
        }
    }

    BudgetBottomSheet(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: format as \"Name. Qty. Price.\" to auto-add to spendings later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value           = name,
            onValueChange   = { name = it },
            label           = { Text("Item name") },
            placeholder     = { Text("Tomato. 2kg. 12.") },
            singleLine      = true,
            modifier        = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick  = { submit() },
            modifier = Modifier.fillMaxWidth(),
            enabled  = name.isNotBlank()
        ) { Text(title) }
    }
}
