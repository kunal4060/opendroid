package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.memory.AuditLog
import com.opendroid.ai.core.memory.AuditOutcome
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit Log tab (B4): the trust-facing viewer over the real `task_history`
 * records the agent loop writes. Entries can be filtered by outcome
 * (success / failed / denied) and action type, searched by text, and tapped
 * for the full stored record plus the step list of the plan they belong to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogTab(
    viewModel: HistoryViewModel,
    saveableLogIds: Set<Long>,
    onSaveAsMacro: (TaskHistoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.auditEntries.collectAsState()
    val filter by viewModel.auditFilter.collectAsState()
    val actionTypes by viewModel.auditActionTypes.collectAsState()
    val totalCount by viewModel.auditTotalCount.collectAsState()
    val successCount by viewModel.auditSuccessCount.collectAsState()
    val failureCount by viewModel.auditFailureCount.collectAsState()
    val deniedCount by viewModel.auditDeniedCount.collectAsState()

    var selectedEntry by remember { mutableStateOf<TaskHistoryEntity?>(null) }

    val groupedEntries = remember(entries) {
        AuditLog.groupByDay(entries, System.currentTimeMillis())
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header: real counts from the store, plus the honesty notes. The log
        // only ever shows rows the execution chain actually wrote; nothing is
        // backfilled, and the coverage note names the paths not recorded yet.
        Text(
            text = "$totalCount actions · $successCount succeeded · $failureCount failed · $deniedCount denied",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Sensitive values are removed when actions are recorded.",
            fontSize = 11.sp,
            color = TextSecondary
        )
        Text(
            text = "Covers actions the agent ran and plans you denied at the approval prompt. " +
                "Actions run inside macros, routines or the MCP tool server are not recorded here yet.",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = filter.query,
            onValueChange = { viewModel.setAuditQuery(it) },
            placeholder = { Text("Search actions or descriptions", fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutcomeChip(
                label = "All",
                selected = filter.outcome == null,
                onClick = { viewModel.setAuditOutcome(null) }
            )
            OutcomeChip(
                label = "Success",
                selected = filter.outcome == AuditOutcome.SUCCESS,
                onClick = { viewModel.setAuditOutcome(AuditOutcome.SUCCESS) }
            )
            OutcomeChip(
                label = "Failed",
                selected = filter.outcome == AuditOutcome.FAILED,
                onClick = { viewModel.setAuditOutcome(AuditOutcome.FAILED) }
            )
            OutcomeChip(
                label = "Denied",
                selected = filter.outcome == AuditOutcome.DENIED,
                onClick = { viewModel.setAuditOutcome(AuditOutcome.DENIED) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        ActionTypeDropdown(
            actionTypes = actionTypes,
            selected = filter.actionType,
            onSelect = { viewModel.setAuditActionType(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            totalCount == 0 -> {
                EmptyStateView(
                    title = "No actions recorded yet",
                    subtitle = "Every step OpenDroid executes — and every plan you deny — is archived here.",
                    icon = Icons.Default.Info
                )
            }
            entries.isEmpty() -> {
                EmptyStateView(
                    title = "No entries match these filters",
                    subtitle = "Try a different outcome, action type, or search text.",
                    icon = Icons.Default.Search
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    groupedEntries.forEach { (dayLabel, dayEntries) ->
                        item(key = "day-$dayLabel") {
                            Text(
                                text = dayLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                // D8: day groups are headings for heading
                                // navigation in TalkBack.
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .semantics { heading() }
                            )
                        }
                        items(dayEntries, key = { it.id }) { entry ->
                            AuditEntryCard(
                                entry = entry,
                                onClick = { selectedEntry = entry },
                                onSaveAsMacro = if (entry.id in saveableLogIds) {
                                    { onSaveAsMacro(entry) }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        AuditEntryDetailSheet(
            entry = entry,
            viewModel = viewModel,
            onDismiss = { selectedEntry = null }
        )
    }
}

@Composable
private fun OutcomeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTypeDropdown(
    actionTypes: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected ?: "All action types",
            onValueChange = {},
            readOnly = true,
            label = { Text("Action type", fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All action types") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            actionTypes.forEach { actionType ->
                DropdownMenuItem(
                    text = { Text(actionType, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    onClick = {
                        onSelect(actionType)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AuditOutcomeBadge(outcome: AuditOutcome, modifier: Modifier = Modifier) {
    val (label, color) = when (outcome) {
        AuditOutcome.SUCCESS -> "SUCCESS" to AccentCyan
        AuditOutcome.FAILED -> "FAILED" to AccentRed
        AuditOutcome.DENIED -> "DENIED" to AccentOrange
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun AuditEntryCard(
    entry: TaskHistoryEntity,
    onClick: () -> Unit,
    onSaveAsMacro: (() -> Unit)? = null
) {
    val outcome = AuditLog.outcomeOf(entry)
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val borderColor = when (outcome) {
        AuditOutcome.SUCCESS -> AccentCyan
        AuditOutcome.FAILED -> AccentRed
        AuditOutcome.DENIED -> AccentOrange
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(14.dp)
                // D8: one merged stop per entry — outcome badge, time,
                // description, module and the denied-explainer read in
                // order (no contentDescription: it would shadow them).
                // The visible "Save as macro" button is merged away, so
                // it is re-exposed as a custom action.
                .semantics(mergeDescendants = true) {
                    customActions = buildList {
                        if (onSaveAsMacro != null) {
                            add(
                                CustomAccessibilityAction("Save completed task as macro") {
                                    onSaveAsMacro()
                                    true
                                }
                            )
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuditOutcomeBadge(outcome)
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = entry.description,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Module: ${entry.actionType}",
                fontSize = 11.sp,
                color = AccentPurple,
                fontFamily = FontFamily.Monospace
            )

            if (outcome == AuditOutcome.DENIED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "You denied this at the approval prompt — it was never executed.",
                    fontSize = 11.sp,
                    color = AccentOrange
                )
            }

            onSaveAsMacro?.let { save ->
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = save,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save completed task as macro", fontSize = 11.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditEntryDetailSheet(
    entry: TaskHistoryEntity,
    viewModel: HistoryViewModel,
    onDismiss: () -> Unit
) {
    val outcome = AuditLog.outcomeOf(entry)
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    var planEntries by remember(entry.planId) { mutableStateOf<List<TaskHistoryEntity>>(emptyList()) }
    val hasRealPlan = entry.planId.isNotBlank() && entry.planId != "n/a"

    LaunchedEffect(entry.planId) {
        if (hasRealPlan) {
            viewModel.loadPlanEntries(entry.planId) { loaded -> planEntries = loaded }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuditOutcomeBadge(outcome)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = entry.actionType,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentPurple,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = entry.description,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            AuditDetailRow("Time", dateFormat.format(Date(entry.timestamp)))
            AuditDetailRow("Step ID", entry.stepId)
            if (hasRealPlan) {
                AuditDetailRow("Plan ID", entry.planId)
            }

            if (entry.paramsJson.isNotBlank() && entry.paramsJson != "{}") {
                Spacer(modifier = Modifier.height(8.dp))
                AuditDetailBlock("Parameters", entry.paramsJson, TextPrimary)
            }

            entry.resultData?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                AuditDetailBlock("Execution Result Data", result, AccentCyan)
            }

            entry.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                AuditDetailBlock(
                    label = if (outcome == AuditOutcome.DENIED) "Denial" else "Diagnostic Error Log",
                    body = error,
                    bodyColor = if (outcome == AuditOutcome.DENIED) AccentOrange else AccentRed
                )
            }

            if (hasRealPlan && planEntries.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "All steps in this plan",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                planEntries.forEach { planEntry ->
                    val planOutcome = AuditLog.outcomeOf(planEntry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AuditOutcomeBadge(planOutcome)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = planEntry.description,
                                fontSize = 12.sp,
                                color = if (planEntry.id == entry.id) TextPrimary else TextSecondary,
                                fontWeight = if (planEntry.id == entry.id) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = planEntry.actionType,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AuditDetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            fontSize = 11.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AuditDetailBlock(label: String, body: String, bodyColor: Color) {
    Text(text = label, fontSize = 11.sp, color = TextSecondary)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = body,
        fontSize = 11.sp,
        color = bodyColor,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(DarkBackground)
            .padding(8.dp)
    )
}
