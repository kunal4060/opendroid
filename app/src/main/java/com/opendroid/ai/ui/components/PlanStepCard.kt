package com.opendroid.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.ui.theme.*

enum class StepDisplayState {
    PENDING, RUNNING, COMPLETED, FAILED, AUTO_FIXING, REPAIRED, SKIPPED, BLOCKED
}

fun getDisplayState(step: PlanStep): StepDisplayState {
    val errorText = step.error?.lowercase() ?: ""
    val resultText = step.result?.lowercase() ?: ""
    
    return when {
        step.status == StepStatus.COMPLETED && (resultText.contains("auto-fixed") || resultText.contains("primary failed") || resultText.contains("repaired")) -> StepDisplayState.REPAIRED
        step.status == StepStatus.COMPLETED && resultText.contains("skipped") -> StepDisplayState.SKIPPED
        step.status == StepStatus.FAILED && errorText.contains("is not registered in ActionDispatcher") -> StepDisplayState.AUTO_FIXING
        step.status == StepStatus.FAILED && errorText.contains("blocked") -> StepDisplayState.BLOCKED
        step.status == StepStatus.COMPLETED -> StepDisplayState.COMPLETED
        step.status == StepStatus.RUNNING -> StepDisplayState.RUNNING
        step.status == StepStatus.FAILED -> StepDisplayState.FAILED
        else -> StepDisplayState.PENDING
    }
}

/** Plain-language step state for TalkBack (D8) — never the raw enum name. */
fun StepDisplayState.accessibleLabel(): String = when (this) {
    StepDisplayState.PENDING -> "Pending"
    StepDisplayState.RUNNING -> "Running"
    StepDisplayState.COMPLETED -> "Completed"
    StepDisplayState.FAILED -> "Failed"
    StepDisplayState.AUTO_FIXING -> "Auto-fixing"
    StepDisplayState.REPAIRED -> "Repaired"
    StepDisplayState.SKIPPED -> "Skipped"
    StepDisplayState.BLOCKED -> "Blocked"
}

@Composable
fun PlanStepCard(
    step: PlanStep,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onSaveEdit: (description: String, params: Map<String, String>) -> Unit = { _, _ -> },
    onDeleteStep: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var isEditing by remember(step.stepId) { mutableStateOf(false) }
    var editDescription by remember(step.stepId, isEditing) { mutableStateOf(step.description) }
    var editParams by remember(step.stepId, isEditing) {
        mutableStateOf(step.params.map { (key, value) -> key to value })
    }
    val displayState = getDisplayState(step)

    // Warning states read the theme's semantic orange token instead of a
    // hard-coded amber: #FFB300 was 1.79:1 as text on light surfaces and
    // #FF5722 3.16:1 — both below WCAG AA. AccentOrange is the same amber
    // family, theme-aware (dark #FF9500 / light #B45309, both AA) (D8).
    val statusColor = when (displayState) {
        StepDisplayState.COMPLETED -> AccentCyan
        StepDisplayState.RUNNING -> AccentCyan
        StepDisplayState.FAILED -> AccentRed
        StepDisplayState.AUTO_FIXING -> AccentOrange // Amber (theme token)
        StepDisplayState.REPAIRED -> AccentPurple
        StepDisplayState.SKIPPED -> TextSecondary
        StepDisplayState.BLOCKED -> AccentOrange // Deep orange (theme token)
        StepDisplayState.PENDING -> TextSecondary
    }

    val statusBorderColor = statusColor.copy(alpha = 0.5f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, statusBorderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = !isEditing) { expanded = !expanded }
            .then(
                if (isEditing) {
                    // While the edit form is open its fields must stay
                    // individually focusable — no merging (D8).
                    Modifier
                } else {
                    // D8: one TalkBack stop per step — "Step 3. <description>"
                    // with the state as stateDescription; expanded detail
                    // texts merge into the same stop. Edit/delete would be
                    // swallowed by the merge, so they are re-exposed as
                    // custom actions.
                    Modifier.semantics(mergeDescendants = true) {
                        // A merged node's contentDescription shadows its
                        // children, so the step's outcome is folded in here:
                        // collapsed cards announce that a result/error exists,
                        // expanded cards read it in full (D8).
                        contentDescription = buildString {
                            append("Step ${step.order}. ${step.description}")
                            if (expanded) {
                                if (step.params.isNotEmpty()) {
                                    append(". Parameters: ")
                                    append(
                                        step.params.entries.joinToString(", ") {
                                            "${it.key}: ${it.value}"
                                        }
                                    )
                                }
                                step.result?.let { append(". Result: $it") }
                                step.error?.let { append(". Error: $it") }
                            } else if (step.error != null) {
                                append(". Failed — expand for details")
                            } else if (step.result != null) {
                                append(". Has a result — expand for details")
                            }
                        }
                        stateDescription = displayState.accessibleLabel()
                        if (editable) {
                            customActions = listOf(
                                CustomAccessibilityAction("Edit step") {
                                    isEditing = true
                                    true
                                },
                                CustomAccessibilityAction("Delete step") {
                                    onDeleteStep()
                                    true
                                }
                            )
                        }
                    }
                }
            ),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Circle badge for step order
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(statusColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${step.order}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = step.description,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = if (expanded) Int.MAX_VALUE else 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (editable && !isEditing) {
                        IconButton(
                            onClick = { isEditing = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit step",
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onDeleteStep,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete step",
                                tint = AccentRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Step status icon
                    Icon(
                        imageVector = when (displayState) {
                            StepDisplayState.COMPLETED -> Icons.Default.CheckCircle
                            StepDisplayState.RUNNING -> Icons.Default.Refresh
                            StepDisplayState.FAILED -> Icons.Default.Close
                            StepDisplayState.AUTO_FIXING -> Icons.Default.Build
                            StepDisplayState.REPAIRED -> Icons.Default.CheckCircle
                            StepDisplayState.SKIPPED -> Icons.Default.ArrowForward
                            StepDisplayState.BLOCKED -> Icons.Default.Warning
                            StepDisplayState.PENDING -> Icons.Default.PlayArrow
                        },
                        contentDescription = displayState.accessibleLabel(),
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isEditing) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))

                    Text("Action Module: ${step.action}", fontSize = 11.sp, color = AccentPurple, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text("Step Description", fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Parameters", fontSize = 11.sp, color = TextSecondary)

                    editParams.forEachIndexed { index, (key, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = key,
                                onValueChange = { newKey ->
                                    editParams = editParams.toMutableList().also { it[index] = newKey to value }
                                },
                                label = { Text("Key", fontSize = 10.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedTextField(
                                value = value,
                                onValueChange = { newValue ->
                                    editParams = editParams.toMutableList().also { it[index] = key to newValue }
                                },
                                label = { Text("Value", fontSize = 10.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { editParams = editParams.toMutableList().also { it.removeAt(index) } },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = AccentRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TextButton(
                        onClick = { editParams = editParams + ("" to "") },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = AccentCyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Parameter", fontSize = 11.sp, color = AccentCyan)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { isEditing = false },
                            border = BorderStroke(1.dp, BorderColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Cancel", fontSize = 12.sp, color = TextSecondary)
                        }
                        Button(
                            onClick = {
                                val cleanedParams = editParams
                                    .map { (k, v) -> k.trim() to v }
                                    .filter { it.first.isNotEmpty() }
                                    .toMap()
                                val cleanedDescription = editDescription.trim().ifEmpty { step.description }
                                onSaveEdit(cleanedDescription, cleanedParams)
                                isEditing = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded && !isEditing) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text("Action Module: ${step.action}", fontSize = 11.sp, color = AccentPurple, fontFamily = FontFamily.Monospace)
                    
                    if (step.params.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Parameters:", fontSize = 11.sp, color = TextSecondary)
                        step.params.forEach { (key, valStr) ->
                            Text("- $key: $valStr", fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (step.dependsOn.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Depends On Steps: ${step.dependsOn.joinToString()}", fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }

                    if (step.canParallelize) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Parallel execution supported", fontSize = 11.sp, color = AccentCyan)
                    }

                    if (step.fallback.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Fallback Routine:", fontSize = 11.sp, color = TextSecondary)
                        Text(step.fallback, fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                    }

                    if (step.result != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBackground)
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("Execution Result:", fontSize = 10.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                                Text(step.result!!, fontSize = 11.sp, color = TextPrimary)
                            }
                        }
                    }

                    if (step.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val isHallucinationError = displayState == StepDisplayState.AUTO_FIXING
                        val errorBgColor = if (isHallucinationError) AccentOrange.copy(alpha = 0.1f) else AccentRed.copy(alpha = 0.1f)
                        val errorBorderColor = if (isHallucinationError) AccentOrange.copy(alpha = 0.3f) else AccentRed.copy(alpha = 0.3f)
                        val errorTitleColor = if (isHallucinationError) AccentOrange else AccentRed
                        val errorTextDisplay = if (isHallucinationError) "Auto-fixing: The requested system action is currently being recovered and updated by the OpenDroid Repair Engine." else step.error!!

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(errorBgColor)
                                .border(1.dp, errorBorderColor, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text(
                                    text = if (isHallucinationError) "Repair Phase Active" else "Execution Error:",
                                    fontSize = 10.sp,
                                    color = errorTitleColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(errorTextDisplay, fontSize = 11.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
