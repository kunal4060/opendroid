package com.opendroid.ai.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.usage.UsageRange
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.UsageDashboardState
import com.opendroid.ai.ui.viewmodel.UsageRowUi
import com.opendroid.ai.ui.viewmodel.UsageViewModel
import java.util.Locale

/**
 * B3 token / cost dashboard. Every number shown comes from the persisted
 * metering aggregates (recorded at the provider choke point); costs are
 * estimates from the price table and are labelled as such.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    viewModel: UsageViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<UsageRowUi?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TOKEN & COST USAGE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                }
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.range == range,
                            onClick = { viewModel.setRange(range) },
                            label = { Text(range.label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            item { UsageTotalsCard(state) }

            if (!state.hasData) {
                item {
                    Text(
                        text = "No usage recorded in this range yet. Token counts appear here after the assistant makes model calls.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }

            items(state.rows, key = { "${it.provider}|${it.model}" }) { row ->
                UsageModelCard(row = row, onEditPrice = { editingRow = row })
            }

            item {
                Text(
                    text = "Costs are estimates: recorded token counts × the price table " +
                        "(${state.priceTableSource}; table dated ${state.priceTableAsOf}). " +
                        "Tap \"Edit price\" on a model to set your own rate. Models without a " +
                        "known price show \"—\" instead of a guess. Streamed replies and " +
                        "on-device models are metered by counting text, so their counts are " +
                        "estimates. On-device and self-hosted (Ollama) models run on your own " +
                        "hardware and cost nothing.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            item {
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    enabled = state.hasData,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset statistics")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset usage statistics?") },
            text = {
                Text(
                    "All recorded token and cost totals will be permanently zeroed. " +
                        "Your edited prices are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetStats()
                        showResetConfirm = false
                    }
                ) { Text("Reset", color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }

    editingRow?.let { row ->
        RateEditorDialog(
            row = row,
            onDismiss = { editingRow = null },
            onSave = { input, output ->
                viewModel.saveRateOverride(row.provider, row.model, input, output)
                editingRow = null
            },
            onClearOverride = {
                viewModel.clearRateOverride(row.provider, row.model)
                editingRow = null
            }
        )
    }
}

@Composable
private fun UsageTotalsCard(state: UsageDashboardState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentNeonGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "TOTAL — ${state.range.label.uppercase(Locale.US)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentNeonGreen
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatLine("Requests", formatCount(state.totalRequests))
            StatLine("Prompt tokens", formatCount(state.totalPromptTokens))
            StatLine("Completion tokens", formatCount(state.totalCompletionTokens))
            if (state.totalUnsplitTokens > 0) {
                StatLine("Tokens without a reported split", formatCount(state.totalUnsplitTokens))
            }
            StatLine("Total tokens", formatCount(state.totalTokens))
            StatLine(
                "Estimated cost",
                state.totalCostUsd?.let { total ->
                    // A priced-subset sum presented as THE total
                    // understates spend: mark it partial whenever any
                    // paid row in range has no known price.
                    formatUsd(total) + if (state.hasUnpricedPaidRows) " *" else ""
                } ?: "—"
            )
            if (state.hasUnpricedPaidRows) {
                Text(
                    text = "* Partial total: ${state.unpricedPaidRowCount} model(s) in this range " +
                        "have no known price and are excluded. Tap \"Edit price\" on a model to price it.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun UsageModelCard(row: UsageRowUi, onEditPrice: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = row.provider,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentNeonGreen
            )
            Text(
                text = row.model,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatLine("Requests", formatCount(row.requests))
            StatLine("Prompt tokens", formatCount(row.promptTokens))
            StatLine("Completion tokens", formatCount(row.completionTokens))
            if (row.unsplitTokens > 0) {
                StatLine("Tokens without a reported split", formatCount(row.unsplitTokens))
            }
            StatLine("Total tokens", formatCount(row.totalTokens))
            when {
                row.isFreeLocal -> StatLine("Estimated cost", "Free — runs on your hardware")
                row.costUsd != null -> StatLine(
                    "Estimated cost",
                    formatUsd(row.costUsd) + if (row.costExcludesUnsplit) " *" else ""
                )
                else -> StatLine("Estimated cost", "— (no price known)")
            }
            if (row.costExcludesUnsplit) {
                Text(
                    text = "* Excludes ${formatCount(row.unsplitTokens)} tokens reported without a prompt/completion split — their mix is unknown, so they are not priced.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            if (row.estimatedRequests > 0) {
                Text(
                    text = "${formatCount(row.estimatedRequests)} of ${formatCount(row.requests)} requests were metered by counting (streamed or on-device), so their counts are estimates.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            if (row.hasOverride) {
                Text(
                    text = "Using your edited price.",
                    fontSize = 11.sp,
                    color = AccentNeonGreen
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onEditPrice) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit price",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit price", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = TextSecondary)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )
    }
}

@Composable
private fun RateEditorDialog(
    row: UsageRowUi,
    onDismiss: () -> Unit,
    onSave: (inputPer1M: Double, outputPer1M: Double) -> Unit,
    onClearOverride: () -> Unit
) {
    var inputText by remember(row) {
        mutableStateOf(row.appliedRate?.inputPer1MUsd?.let { trimRate(it) } ?: "")
    }
    var outputText by remember(row) {
        mutableStateOf(row.appliedRate?.outputPer1MUsd?.let { trimRate(it) } ?: "")
    }
    val input = inputText.toDoubleOrNull()
    val output = outputText.toDoubleOrNull()
    val valid = input != null && output != null && input >= 0.0 && output >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price for ${row.model}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${row.provider} · USD per 1M tokens. Your price replaces the built-in table for this model.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Input (prompt) $ / 1M") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = outputText,
                    onValueChange = { outputText = it },
                    label = { Text("Output (completion) $ / 1M") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSave(input!!, output!!) },
                enabled = valid
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (row.hasOverride) {
                    TextButton(onClick = onClearOverride) {
                        Text("Remove my price", color = AccentRed)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

private fun formatUsd(value: Double): String =
    if (value > 0.0 && value < 0.01) {
        String.format(Locale.US, "$%.4f", value)
    } else {
        String.format(Locale.US, "$%.2f", value)
    }

private fun trimRate(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
