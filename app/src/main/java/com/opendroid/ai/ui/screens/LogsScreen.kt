package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: HistoryViewModel,
    onNavigateToActionLearning: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Audit Log", "Action Errors")

    val history by viewModel.taskHistory.collectAsState()
    val actionErrors by viewModel.unknownActions.collectAsState()
    var selectedPlanForMacro by remember { mutableStateOf<String?>(null) }
    var macroName by remember { mutableStateOf("") }
    var macroSaveError by remember { mutableStateOf<String?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    if (showClearConfirmation) {
        // The trash icon used to wipe with a single tap: one accidental
        // touch permanently deleted the entire audit log — the trust
        // record this screen exists to show. Confirm first, naming
        // exactly what the active tab will lose (CrashLogScreen's
        // pattern for the same action).
        val clearingAuditLog = selectedTab == 0
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Text(if (clearingAuditLog) "Delete the entire audit log?" else "Delete all action errors?")
            },
            text = {
                Text(
                    if (clearingAuditLog) {
                        "This removes every task-history entry from this device. It cannot be undone."
                    } else {
                        "This removes every recorded action error from this device. It cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (clearingAuditLog) {
                        viewModel.clearTaskHistory()
                    } else {
                        viewModel.clearUnknownActions()
                    }
                    showClearConfirmation = false
                }) {
                    Text("Delete", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    val saveableLogIds = remember(history) {
        history.groupBy { it.planId }
            .filter { (planId, entries) ->
                planId.isNotBlank() && planId != "n/a" && entries.isNotEmpty() && entries.all { it.success }
            }
            .mapNotNull { (_, entries) ->
                entries.minWithOrNull(compareBy<TaskHistoryEntity> { it.timestamp }.thenBy { it.id })?.id
            }
            .toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AUDIT LOG",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                actions = {
                    val hasLogs = if (selectedTab == 0) history.isNotEmpty() else actionErrors.isNotEmpty()
                    if (hasLogs) {
                        IconButton(
                            onClick = { showClearConfirmation = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear logs",
                                tint = AccentRed
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkBackground,
                contentColor = TextPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AccentNeonGreen
                    )
                },
                divider = {
                    Divider(color = BorderColor)
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) TextPrimary else TextSecondary
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (selectedTab == 0) {
                    AuditLogTab(
                        viewModel = viewModel,
                        saveableLogIds = saveableLogIds,
                        onSaveAsMacro = { log ->
                            selectedPlanForMacro = log.planId
                            macroName = "${log.description.take(40).ifBlank { "Completed task" }} macro"
                            macroSaveError = null
                        }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // B5: the learning loop lives one tap from the raw log.
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, AccentOrange.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                                .clickable { onNavigateToActionLearning() },
                            colors = CardDefaults.cardColors(containerColor = CardBackground)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = "Action Learning",
                                    tint = AccentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ACTION LEARNING",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = AccentOrange
                                    )
                                    Text(
                                        text = "Review these invented actions — teach what each should do, convert to a macro, or dismiss.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Open Action Learning",
                                    tint = TextSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (actionErrors.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(actionErrors) { error ->
                                    UnknownActionCard(error = error)
                                }
                            }
                        } else {
                            EmptyStateView(
                                title = "All systems fully aligned",
                                subtitle = "OpenDroid's Repair Engine has not encountered any unrecognized commands.",
                                icon = Icons.Default.CheckCircle,
                                iconColor = AccentNeonGreen
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedPlanForMacro != null) {
        AlertDialog(
            onDismissRequest = {
                selectedPlanForMacro = null
                macroSaveError = null
            },
            title = { Text("Save completed task as macro") },
            text = {
                Column {
                    Text(
                        "Only successful steps will be recorded. Credential, API-key, and token values are removed.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = macroName,
                        onValueChange = { macroName = it },
                        label = { Text("Macro name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    macroSaveError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, color = AccentRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveCompletedTaskAsMacro(
                            planId = selectedPlanForMacro!!,
                            name = macroName
                        ) { error ->
                            if (error == null) {
                                selectedPlanForMacro = null
                                macroSaveError = null
                            } else {
                                macroSaveError = error
                            }
                        }
                    },
                    enabled = macroName.isNotBlank()
                ) {
                    Text("Save macro")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPlanForMacro = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EmptyStateView(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color = TextSecondary
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(AppTheme.colors.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun UnknownActionCard(error: UnknownActionEntity) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    val statusColor = when (error.fixStatus) {
        "AUTO_FIXED" -> AppTheme.colors.success
        "REPLANNED" -> AppTheme.colors.accentInfo
        "FAILED" -> AccentRed
        else -> AccentNeonGreen
    }

    val statusText = when (error.fixStatus) {
        "AUTO_FIXED" -> "AUTO-FIXED"
        "REPLANNED" -> "REPLANNED"
        "FAILED" -> "FAILED"
        else -> error.fixStatus
    }

    val explanation = when (error.fixStatus) {
        "AUTO_FIXED" -> "Successfully auto-corrected by OpenDroid's Repair Engine."
        "REPLANNED" -> "Dynamically replanned and bypassed the unrecognized command."
        "FAILED" -> "Unrecognized system command failed execution."
        else -> "System anomaly tracked."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                statusColor.copy(alpha = 0.25f),
                RoundedCornerShape(18.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(14.dp)
                // D8: one merged stop per error entry — badge, time,
                // attempted action, goal and (when expanded) the repair
                // explanation read in order. No contentDescription is set
                // on purpose: it would shadow the expanded detail.
                .semantics(mergeDescendants = true) {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        fontFamily = JetBrainsMonoFamily
                    )
                }

                Text(
                    text = dateFormat.format(Date(error.timestamp)),
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = JetBrainsMonoFamily
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Unrecognized: ${error.attemptedAction}",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AccentNeonGreen
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "Goal: ${error.goal}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text(
                        text = "System Status Details:",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = explanation,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontFamily = JetBrainsMonoFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppTheme.colors.surfaceSunken)
                            .padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand info",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

