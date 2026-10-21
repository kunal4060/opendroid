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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.opendroid.ai.ui.viewmodel.PlanViewModel
import com.opendroid.ai.ui.viewmodel.TemplateShareViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                        text = "PLAN ENGINE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
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
                        text = "PLAN SEQUENCE STAGE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(displayPlan!!.steps, key = { it.stepId }) { step ->
                    val isStepEditable = isCurrentActivePlan &&
                        displayPlan!!.status == PlanStatus.RUNNING &&
                        step.status == StepStatus.PENDING
                    PlanStepCard(
                        step = step,
                        editable = isStepEditable,
                        onSaveEdit = { description, params ->
                            viewModel.editStep(step.stepId, description, params)
                        },
                        onDeleteStep = { viewModel.deleteStep(step.stepId) }
                    )
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
                        text = "AUTONOMOUS EXECUTION HISTORY",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
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
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = { Text("Template Transfer", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = { Text(message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { transferMessage = null }) {
                    Text("OK", color = AccentCyan, fontWeight = FontWeight.Bold)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isCurrentActive) AccentCyan.copy(alpha = 0.5f) else BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (plan.status) {
                        PlanStatus.COMPLETED -> AccentCyan
                        PlanStatus.RUNNING -> AccentCyan
                        PlanStatus.FAILED -> AccentRed
                        PlanStatus.CANCELLED -> Color(0xFFFFB300) // Amber, matches PlanStepCard's in-between/warning states
                        else -> TextSecondary
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = plan.status.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (!isCurrentActive) {
                    Text(
                        text = "Viewing Past Run",
                        fontSize = 10.sp,
                        color = AccentPurple,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentPurple.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clickable { onClearSelection() }
                    )
                } else {
                    Text(
                        text = "ACTIVE RUN",
                        fontSize = 10.sp,
                        color = AccentCyan,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = plan.goal,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Steps", fontSize = 10.sp, color = TextSecondary)
                    Text("${plan.steps.size} scheduled", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Estimated duration", fontSize = 10.sp, color = TextSecondary)
                    Text(plan.estimatedDuration, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            }

            // C6: share this plan as a portable template file
            if (onExportTemplate != null || onShareTemplate != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = BorderColor)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onExportTemplate != null) {
                        TextButton(onClick = onExportTemplate) {
                            Icon(imageVector = Icons.Default.FileUpload, contentDescription = "Export Template", modifier = Modifier.size(16.dp), tint = AccentCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export Template", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onShareTemplate != null) {
                        TextButton(onClick = onShareTemplate) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share Template", modifier = Modifier.size(16.dp), tint = AccentCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (isCurrentActive && plan.status == PlanStatus.RUNNING) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = BorderColor)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STOP TASK",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyPlanPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
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
                tint = TextSecondary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No active plans running",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Plans formulated by the autonomous system will display here in real-time.",
                fontSize = 11.sp,
                color = TextSecondary,
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
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) CardBackground else Color.Transparent)
            .border(1.dp, if (isSelected) BorderColor else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onSelect() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plan.goal,
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 1,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(plan.createdAt)),
                    fontSize = 10.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${plan.steps.size} steps",
                    fontSize = 10.sp,
                    color = AccentCyan,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (plan.status) {
                    PlanStatus.COMPLETED -> Icons.Default.Check
                    PlanStatus.FAILED -> Icons.Default.Close
                    PlanStatus.CANCELLED -> Icons.Default.Cancel
                    else -> Icons.Default.Info
                },
                contentDescription = plan.status.name,
                tint = when (plan.status) {
                    PlanStatus.COMPLETED -> AccentCyan
                    PlanStatus.FAILED -> AccentRed
                    PlanStatus.CANCELLED -> Color(0xFFFFB300) // Amber, matches PlanHeaderCard's CANCELLED color
                    else -> TextSecondary
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Plan",
                    tint = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
