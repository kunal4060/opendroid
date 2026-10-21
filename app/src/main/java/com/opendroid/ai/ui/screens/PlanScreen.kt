package com.opendroid.ai.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.templates.TemplateExport
import com.opendroid.ai.core.templates.TemplateFormat
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.ui.templates.TemplateFileIo
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.components.PlanStepCard
import com.opendroid.ai.ui.components.StepDisplayState
import com.opendroid.ai.ui.components.getDisplayState
import com.opendroid.ai.ui.viewmodel.PlanViewModel
import com.opendroid.ai.ui.viewmodel.TemplateShareViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plan-level status presentation — the same token roles as the
 * step-level Part-2 table in PlanStepCard (running = brand accent,
 * completed = success family, failed = error red, paused/blocked =
 * permission amber, everything else neutral), so the header and the
 * step list can never drift into two different colour maps again.
 */
private fun PlanStatus.label(): String = when (this) {
    PlanStatus.PROPOSED -> "Proposed"
    PlanStatus.PENDING -> "Pending"
    PlanStatus.RUNNING -> "Running"
    PlanStatus.COMPLETED -> "Completed"
    PlanStatus.FAILED -> "Failed"
    PlanStatus.PAUSED -> "Paused"
    PlanStatus.CANCELLED -> "Cancelled"
}

private fun PlanStatus.statusColor(colors: OpenDroidColors): Color = when (this) {
    PlanStatus.RUNNING -> colors.accentNeonGreen
    PlanStatus.COMPLETED -> colors.success
    PlanStatus.FAILED -> colors.accentRed
    PlanStatus.PAUSED -> colors.accentOrange
    PlanStatus.CANCELLED -> colors.textTertiary
    PlanStatus.PROPOSED, PlanStatus.PENDING -> colors.textSecondary
}

private fun PlanStatus.statusIcon(): ImageVector = when (this) {
    PlanStatus.RUNNING -> Icons.Default.Autorenew
    PlanStatus.COMPLETED -> Icons.Default.CheckCircle
    PlanStatus.FAILED -> Icons.Default.Error
    PlanStatus.PAUSED -> Icons.Default.FrontHand
    PlanStatus.CANCELLED -> Icons.Default.Cancel
    PlanStatus.PROPOSED, PlanStatus.PENDING -> Icons.Default.RadioButtonUnchecked
}

/** Steps that count as finished for the header progress derivation. */
private fun PlanStep.isFinished(): Boolean = when (getDisplayState(this)) {
    StepDisplayState.COMPLETED,
    StepDisplayState.REPAIRED,
    StepDisplayState.SKIPPED -> true
    else -> false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    modifier: Modifier = Modifier,
    templateViewModel: TemplateShareViewModel = hiltViewModel()
) {
    val currentPlan by viewModel.currentPlan.collectAsState()
    val planHistory by viewModel.planHistory.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors

    // ---- C6 template export (save-to-file via SAF / share sheet) ----
    var pendingExport by remember { mutableStateOf<TemplateExport?>(null) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val export = pendingExport
        pendingExport = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || export == null) {
            return@rememberLauncherForActivityResult // picker cancelled
        }
        scope.launch {
            try {
                TemplateFileIo.writeExport(context, uri, export)
                transferMessage = "Template saved: ${export.fileName}"
            } catch (e: Exception) {
                transferMessage = "Export failed: ${e.localizedMessage ?: "unknown error"}"
            }
        }
    }

    fun startExport(plan: Plan) {
        val export = templateViewModel.planExport(plan)
        pendingExport = export
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = TemplateFormat.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, export.fileName)
        }
        exportLauncher.launch(intent)
    }

    fun sharePlan(plan: Plan) {
        val export = templateViewModel.planExport(plan)
        if (!TemplateFileIo.shareExport(context, export)) {
            transferMessage = "Share failed: could not prepare the template file."
        }
    }

    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    val displayPlan = if (selectedPlanId != null) {
        planHistory.find { it.planId == selectedPlanId } ?: currentPlan
    } else {
        currentPlan
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Plan",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        },
        containerColor = colors.background,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Main Section: Current Active Plan
            if (displayPlan != null) {
                val isCurrentActivePlan = displayPlan!!.planId == currentPlan?.planId

                item {
                    PlanHeaderCard(
                        plan = displayPlan!!,
                        isCurrentActive = isCurrentActivePlan,
                        onClearSelection = { selectedPlanId = null },
                        onStop = { viewModel.stopTask() },
                        onExportTemplate = { startExport(displayPlan!!) },
                        onShareTemplate = { sharePlan(displayPlan!!) }
                    )
                }

                item {
                    Text(
                        text = "Plan sequence",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                // Steps render as one timeline block so the rail in
                // PlanStepCard runs continuously from the first node
                // to the last; per-step editability is unchanged.
                item {
                    Column {
                        val steps = displayPlan!!.steps
                        steps.forEachIndexed { index, step ->
                            val isStepEditable = isCurrentActivePlan &&
                                displayPlan!!.status == PlanStatus.RUNNING &&
                                step.status == StepStatus.PENDING
                            PlanStepCard(
                                step = step,
                                editable = isStepEditable,
                                onSaveEdit = { description, params ->
                                    viewModel.editStep(step.stepId, description, params)
                                },
                                onDeleteStep = { viewModel.deleteStep(step.stepId) },
                                isFirst = index == 0,
                                isLast = index == steps.lastIndex
                            )
                        }
                    }
                }
            } else {
                item {
                    EmptyPlanPlaceholder()
                }
            }

            // History Section: Past Autonomous Runs
            if (planHistory.isNotEmpty()) {
                item {
                    Text(
                        text = "Past runs",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }

                items(planHistory) { pastPlan ->
                    val isSelected = selectedPlanId == pastPlan.planId || (selectedPlanId == null && pastPlan.planId == currentPlan?.planId)
                    PastPlanRow(
                        plan = pastPlan,
                        isSelected = isSelected,
                        onSelect = { selectedPlanId = pastPlan.planId },
                        onDelete = { viewModel.deletePlan(pastPlan.planId) }
                    )
                }
            }
        }
    }

    // C6: one-shot export outcome message
    transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { transferMessage = null },
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textPrimary,
            title = { Text("Template transfer", style = MaterialTheme.typography.titleSmall) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { transferMessage = null }) {
                    Text("OK", style = MaterialTheme.typography.labelLarge, color = colors.accentNeonGreen)
                }
            }
        )
    }
}

@Composable
fun PlanHeaderCard(
    plan: Plan,
    isCurrentActive: Boolean,
    onClearSelection: () -> Unit,
    onStop: () -> Unit,
    onExportTemplate: (() -> Unit)? = null,
    onShareTemplate: (() -> Unit)? = null
) {
    val colors = AppTheme.colors
    val cardShape = RoundedCornerShape(18.dp)
    val isRunning = plan.status == PlanStatus.RUNNING
    val isReview = plan.status == PlanStatus.PROPOSED || plan.status == PlanStatus.PENDING

    // Determinate progress derived from the step states already on
    // screen — never time-based (research-2 Part 4 anti-pattern).
    val totalSteps = plan.steps.size
    val doneSteps = plan.steps.count { it.isFinished() }
    val runningIndex = plan.steps.indexOfFirst { getDisplayState(it) == StepDisplayState.RUNNING }
    val blockedStep = plan.steps.firstOrNull { getDisplayState(it) == StepDisplayState.BLOCKED }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderColor, cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceRaised)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // D8: plan status changes (RUNNING -> COMPLETED/FAILED)
                    // are announced to TalkBack without stealing focus.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Plan status: ${plan.status.label().lowercase(Locale.getDefault())}"
                    }
                ) {
                    val statusColor = plan.status.statusColor(colors)
                    Icon(
                        imageVector = plan.status.statusIcon(),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = plan.status.label(),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor
                    )
                }
                if (!isCurrentActive) {
                    Text(
                        text = "Viewing past run",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceOverlay)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable { onClearSelection() }
                            .semantics {
                                role = Role.Button
                                contentDescription = "Viewing past run. Activate to return to the current plan"
                            }
                    )
                } else if (isReview) {
                    // Review register: the plan is a proposal, not a run.
                    Text(
                        text = "Proposed plan",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceOverlay)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = plan.goal,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )

            // Waiting-for-you surfaces in the header itself — the user
            // never has to scroll to find why the agent stopped.
            if (blockedStep != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentOrange.copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FrontHand,
                        contentDescription = null,
                        tint = colors.accentOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Waiting for you",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accentOrange
                        )
                        if (!blockedStep.error.isNullOrBlank()) {
                            Text(
                                text = blockedStep.error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary
                            )
                        }
                    }
                }
            }

            if (totalSteps > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            runningIndex >= 0 -> "Step ${runningIndex + 1} of $totalSteps"
                            doneSteps > 0 -> "$doneSteps of $totalSteps steps done"
                            else -> "$totalSteps steps scheduled"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    if (doneSteps > 0) {
                        Text(
                            text = "$doneSteps done",
                            style = MonoMedium,
                            color = colors.textTertiary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (totalSteps == 0) 0f else doneSteps.toFloat() / totalSteps.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = colors.accentNeonGreen,
                    trackColor = colors.surfaceOverlay
                )
            }

            // Running register condenses the meta row: progress above
            // is the overview, so only the step count stays; review
            // and finished plans keep the full scheduled summary.
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = colors.borderSubtle)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Steps", style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$totalSteps scheduled",
                        style = MonoMedium,
                        color = colors.textPrimary
                    )
                }
                if (!isRunning) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Estimated duration", style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = plan.estimatedDuration,
                            style = MonoMedium,
                            color = colors.textPrimary
                        )
                    }
                }
            }

            // C6: share this plan as a portable template file
            if (onExportTemplate != null || onShareTemplate != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = colors.borderSubtle)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onExportTemplate != null) {
                        TextButton(onClick = onExportTemplate) {
                            Icon(imageVector = Icons.Default.FileUpload, contentDescription = "Export template", modifier = Modifier.size(16.dp), tint = colors.textSecondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export template", style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
                        }
                    }
                    if (onShareTemplate != null) {
                        TextButton(onClick = onShareTemplate) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share template", modifier = Modifier.size(16.dp), tint = colors.textSecondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
                        }
                    }
                }
            }

            if (isCurrentActive && isRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = colors.borderSubtle)
                // Calm stop: a clearly labelled text action in the
                // header action row, not a full-width alarm button.
                // Same onStop wiring as before.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = colors.accentRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stop task",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accentRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyPlanPlaceholder() {
    val colors = AppTheme.colors
    val cardShape = RoundedCornerShape(18.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderColor, cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "No plan",
                tint = colors.textTertiary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No active plans running",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Plans formulated by the autonomous system will display here in real-time.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun PastPlanRow(
    plan: Plan,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = AppTheme.colors
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val doneSteps = plan.steps.count { it.isFinished() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) colors.surfaceRaised else Color.Transparent)
            .border(1.dp, if (isSelected) colors.borderStrong else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable { onSelect() }
            .semantics {
                // D8: selection is state, not just a highlight colour.
                stateDescription = if (isSelected) "Selected" else "Not selected"
                contentDescription = "Past plan: ${plan.goal}, ${plan.steps.size} steps, " +
                    "status ${plan.status.label().lowercase(Locale.getDefault())}"
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = plan.status.statusIcon(),
                contentDescription = plan.status.label(),
                tint = plan.status.statusColor(colors),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = plan.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateFormat.format(Date(plan.createdAt)),
                        style = MonoMedium,
                        color = colors.textTertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "$doneSteps/${plan.steps.size} steps done",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = plan.status.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = plan.status.statusColor(colors)
                    )
                }
            }
        }
        IconButton(
            onClick = onDelete,
            // 40dp (was 24dp): the 24dp target was too small to hit
            // reliably; still compact inside the row (D8).
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete plan",
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
