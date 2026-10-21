package com.opendroid.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
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
    StepDisplayState.PENDING -> "Queued"
    StepDisplayState.RUNNING -> "Running"
    StepDisplayState.COMPLETED -> "Completed"
    StepDisplayState.FAILED -> "Failed"
    StepDisplayState.AUTO_FIXING -> "Auto-fixing"
    StepDisplayState.REPAIRED -> "Repaired"
    StepDisplayState.SKIPPED -> "Skipped"
    StepDisplayState.BLOCKED -> "Waiting for you"
}

/**
 * Sentence-case status headline shown under the step description
 * (research-2 Part 2 label styles, adopted verbatim). Colour is never
 * the only channel: node icon + this label always travel together.
 */
fun StepDisplayState.statusHeadline(): String = when (this) {
    StepDisplayState.PENDING -> "Queued"
    StepDisplayState.RUNNING -> "Running"
    StepDisplayState.COMPLETED -> "Done"
    StepDisplayState.FAILED -> "Failed"
    StepDisplayState.AUTO_FIXING -> "Hit a problem — trying another way"
    StepDisplayState.REPAIRED -> "Done — recovered after a retry"
    StepDisplayState.SKIPPED -> "Skipped"
    StepDisplayState.BLOCKED -> "Waiting for you"
}

/**
 * The Part-2 state → token mapping (single source for this file; the
 * Plan header applies the same roles to plan-level states).
 * PENDING/SKIPPED are neutral, RUNNING is the one brand accent,
 * COMPLETED/REPAIRED use the success (green) family, FAILED is the
 * error red, AUTO_FIXING/BLOCKED are the permission amber.
 */
fun StepDisplayState.nodeColor(colors: OpenDroidColors): Color = when (this) {
    StepDisplayState.PENDING -> colors.textSecondary
    StepDisplayState.RUNNING -> colors.accentNeonGreen
    StepDisplayState.COMPLETED -> colors.success
    StepDisplayState.FAILED -> colors.accentRed
    StepDisplayState.AUTO_FIXING -> colors.accentOrange
    StepDisplayState.REPAIRED -> colors.success
    StepDisplayState.SKIPPED -> colors.textTertiary
    StepDisplayState.BLOCKED -> colors.accentOrange
}

/** Part-2 node icon per state (Material filled vectors only). */
fun StepDisplayState.nodeIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    StepDisplayState.PENDING -> Icons.Default.RadioButtonUnchecked
    StepDisplayState.RUNNING -> Icons.Default.Autorenew
    StepDisplayState.COMPLETED -> Icons.Default.CheckCircle
    StepDisplayState.FAILED -> Icons.Default.Error
    StepDisplayState.AUTO_FIXING -> Icons.Default.Build
    StepDisplayState.REPAIRED -> Icons.Default.CheckCircle
    StepDisplayState.SKIPPED -> Icons.Default.SkipNext
    StepDisplayState.BLOCKED -> Icons.Default.FrontHand
}

@Composable
fun PlanStepCard(
    step: PlanStep,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onSaveEdit: (description: String, params: Map<String, String>) -> Unit = { _, _ -> },
    onDeleteStep: () -> Unit = {},
    /** Timeline rail ends: true when this card is the first / last step. */
    isFirst: Boolean = true,
    isLast: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var isEditing by remember(step.stepId) { mutableStateOf(false) }
    var editDescription by remember(step.stepId, isEditing) { mutableStateOf(step.description) }
    var editParams by remember(step.stepId, isEditing) {
        mutableStateOf(step.params.map { (key, value) -> key to value })
    }
    val displayState = getDisplayState(step)
    val colors = AppTheme.colors
    val statusColor = displayState.nodeColor(colors)
    val isRunning = displayState == StepDisplayState.RUNNING

    // Only the running card is tinted (accentContainer); every other
    // state sits on the neutral raised surface with the same hairline.
    val targetContainer = if (isRunning) colors.accentContainer else colors.surfaceRaised
    val containerColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 250),
        label = "planStepContainer"
    )
    val cardShape = RoundedCornerShape(18.dp)
    val railColor = colors.borderStrong

    // Only the running node animates (research-2 Part 2 motion column);
    // it stands still when reduce-motion is on. The transition is
    // created unconditionally; the angle is only applied to the
    // running node.
    val reduceMotion = Motion.reduceMotion
    val runningTransition = rememberInfiniteTransition(label = "runningNode")
    val runningAngle by runningTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "runningNodeRotation"
    )
    val rotation = if (isRunning && !reduceMotion) runningAngle else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            // ── Timeline rail: continuous vertical line through the
            // node-icon column, broken only at the list ends ──
            Column(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(if (isFirst) Color.Transparent else railColor)
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) colors.accentContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = displayState,
                        animationSpec = tween(durationMillis = 200),
                        label = "planStepNode"
                    ) { state ->
                        Icon(
                            imageVector = state.nodeIcon(),
                            contentDescription = state.accessibleLabel(),
                            tint = state.nodeColor(colors),
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    rotationZ = if (state == StepDisplayState.RUNNING) rotation else 0f
                                }
                        )
                    }
                    if (displayState == StepDisplayState.REPAIRED) {
                        // Repaired = success node + small repair badge.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceRaised),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = colors.accentOrange,
                                modifier = Modifier.size(9.dp)
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(if (isLast) Color.Transparent else railColor)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, colors.borderColor, cardShape)
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
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = containerColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (displayState == StepDisplayState.SKIPPED) colors.textSecondary else colors.textPrimary,
                                textDecoration = if (displayState == StepDisplayState.SKIPPED) TextDecoration.LineThrough else TextDecoration.None,
                                maxLines = if (expanded) Int.MAX_VALUE else 2
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Meta line: step number moved off the old badge
                            // into "Step 3 · <action>" (research-2 B2). The
                            // action name is data, so it keeps the mono voice.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Step ${step.order}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textTertiary
                                )
                                if (step.action.isNotBlank()) {
                                    Text(
                                        text = " · ",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.textTertiary
                                    )
                                    Text(
                                        text = step.action,
                                        style = MonoMedium,
                                        color = colors.textTertiary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            // Live status sentence — the second collapsed line.
                            Text(
                                text = displayState.statusHeadline(),
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor
                            )
                            // Collapsed detail: the failure reason / blocked
                            // question / result summary rides under the
                            // headline; expanded view shows the full boxes.
                            if (!expanded && !isEditing) {
                                val detail = when (displayState) {
                                    StepDisplayState.FAILED,
                                    StepDisplayState.BLOCKED -> step.error
                                    StepDisplayState.COMPLETED,
                                    StepDisplayState.REPAIRED -> step.result
                                    else -> null
                                }
                                if (!detail.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 2
                                    )
                                }
                            }
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
                                        tint = colors.textSecondary,
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
                                        tint = colors.accentRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    AnimatedVisibility(visible = isEditing) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Divider(color = colors.borderSubtle, modifier = Modifier.padding(vertical = 4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Action module",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textTertiary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = step.action,
                                    style = MonoMedium,
                                    color = colors.textSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = editDescription,
                                onValueChange = { editDescription = it },
                                label = { Text("Step description") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.accentNeonGreen,
                                    unfocusedBorderColor = colors.borderStrong,
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Parameters",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )

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
                                        label = { Text("Key") },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = colors.accentNeonGreen,
                                            unfocusedBorderColor = colors.borderStrong,
                                            focusedTextColor = colors.textPrimary,
                                            unfocusedTextColor = colors.textPrimary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    OutlinedTextField(
                                        value = value,
                                        onValueChange = { newValue ->
                                            editParams = editParams.toMutableList().also { it[index] = key to newValue }
                                        },
                                        label = { Text("Value") },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = colors.accentNeonGreen,
                                            unfocusedBorderColor = colors.borderStrong,
                                            focusedTextColor = colors.textPrimary,
                                            unfocusedTextColor = colors.textPrimary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { editParams = editParams.toMutableList().also { it.removeAt(index) } },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = colors.accentRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            TextButton(
                                onClick = { editParams = editParams + ("" to "") },
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = colors.accentNeonGreen, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add parameter", style = MaterialTheme.typography.labelLarge, color = colors.accentNeonGreen)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { isEditing = false },
                                    border = BorderStroke(1.dp, colors.borderStrong),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Cancel", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
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
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreenButton, contentColor = colors.onAccent),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Save", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = expanded && !isEditing) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Divider(color = colors.borderSubtle, modifier = Modifier.padding(vertical = 4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Action module",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textTertiary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = step.action,
                                    style = MonoMedium,
                                    color = colors.textSecondary
                                )
                            }

                            if (step.params.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Parameters",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                step.params.forEach { (key, valStr) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = key,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )
                                        Text(
                                            text = valStr,
                                            style = MonoMedium,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }

                            if (step.dependsOn.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Depends on steps",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = step.dependsOn.joinToString(),
                                        style = MonoMedium,
                                        color = colors.textPrimary
                                    )
                                }
                            }

                            if (step.canParallelize) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Parallel execution supported",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }

                            if (step.fallback.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Fallback routine",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = step.fallback,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary
                                )
                            }

                            if (step.result != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.surfaceSunken)
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Result",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colors.success
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = step.result!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }

                            if (step.error != null) {
                                Spacer(modifier = Modifier.height(10.dp))

                                val isHallucinationError = displayState == StepDisplayState.AUTO_FIXING
                                val errorBgColor = if (isHallucinationError) colors.accentOrange.copy(alpha = 0.1f) else colors.accentRed.copy(alpha = 0.1f)
                                val errorBorderColor = if (isHallucinationError) colors.accentOrange.copy(alpha = 0.3f) else colors.accentRed.copy(alpha = 0.3f)
                                val errorTitleColor = if (isHallucinationError) colors.accentOrange else colors.accentRed
                                val errorTextDisplay = if (isHallucinationError) "The requested system action is currently being recovered and updated by the OpenDroid Repair Engine." else step.error!!

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(errorBgColor)
                                        .border(1.dp, errorBorderColor, RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = if (isHallucinationError) "Repair phase active" else "Error",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = errorTitleColor
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = errorTextDisplay,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Connector gap: the rail line runs through the space between
        // cards so the timeline reads as one continuous ledger.
        if (!isLast) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.width(28.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(12.dp)
                            .background(railColor)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
