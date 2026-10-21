package com.opendroid.ai.ui.screens

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.agent.ParamDefinition
import com.opendroid.ai.core.learning.LearnedActionMapping
import com.opendroid.ai.core.learning.UnknownActionReviewState
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.MacroStepDraft
import com.opendroid.ai.ui.viewmodel.UnknownActionGroupUi
import com.opendroid.ai.ui.viewmodel.UnknownActionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B5 — Action Learning: the review queue that closes the unknown-action loop.
 *
 * Every group is a verb the model invented, recorded for real in
 * `unknown_actions`. From here the user can dismiss it, teach it to run an
 * existing action, or convert it into a real macro — and the dispatcher
 * consults those teachings the next time the verb shows up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnknownActionsScreen(
    onBack: () -> Unit,
    viewModel: UnknownActionsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val groups by viewModel.groups.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val registeredActions = remember { viewModel.registeredActions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ACTION LEARNING",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
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
            Text(
                text = if (pendingCount > 0) {
                    "$pendingCount invented action${if (pendingCount == 1) "" else "s"} to review. " +
                        "Teach one what it should do, or dismiss it — OpenDroid uses your answer next time."
                } else {
                    "Nothing waiting. New invented actions the model tries will appear here."
                },
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            if (groups.isEmpty()) {
                Box(modifier = Modifier.weight(1f)) {
                    EmptyStateView(
                        title = "No unknown actions recorded",
                        subtitle = "When the model invents an action OpenDroid doesn't have, it is recorded here for review.",
                        icon = Icons.Default.CheckCircle,
                        iconColor = AccentNeonGreen
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    items(groups, key = { it.group.key }) { ui ->
                        UnknownActionGroupCard(
                            ui = ui,
                            registeredActions = registeredActions,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

/** Settings entry card (B5) — shows the live pending-review count. */
@Composable
fun ActionLearningSettingsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: UnknownActionsViewModel = hiltViewModel()
    val pendingCount by viewModel.pendingCount.collectAsState()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AccentOrange.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = "Action Learning",
                tint = AccentOrange,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ACTION LEARNING",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = AccentOrange
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (pendingCount > 0) {
                        "$pendingCount invented action${if (pendingCount == 1) "" else "s"} waiting — teach or dismiss them."
                    } else {
                        "Review actions the model invented and teach what they should do."
                    },
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Go",
                tint = TextSecondary
            )
        }
    }
}

private enum class TeachMode { NONE, ACTION, MACRO }

@Composable
private fun UnknownActionGroupCard(
    ui: UnknownActionGroupUi,
    registeredActions: List<String>,
    viewModel: UnknownActionsViewModel
) {
    val group = ui.group
    var expanded by remember(group.key) { mutableStateOf(false) }
    var teachMode by remember(group.key) { mutableStateOf(TeachMode.NONE) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val stateColor = when (ui.state) {
        UnknownActionReviewState.PENDING -> AccentOrange
        UnknownActionReviewState.DISMISSED -> TextSecondary
        UnknownActionReviewState.CONVERTED -> AppTheme.colors.success
    }
    val stateText = when (ui.state) {
        UnknownActionReviewState.PENDING -> "TO REVIEW"
        UnknownActionReviewState.DISMISSED -> "DISMISSED"
        UnknownActionReviewState.CONVERTED -> "TAUGHT"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, stateColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (!expanded) teachMode = TeachMode.NONE
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(stateColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stateText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = stateColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "×${group.occurrences} · last ${dateFormat.format(Date(group.lastSeen))}",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = group.displayAction,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AccentNeonGreen
            )

            if (group.sampleGoal.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Asked as: ${group.sampleGoal}",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
            }

            val breakdown = group.fixStatusCounts.entries
                .sortedByDescending { it.value }
                .joinToString(" · ") { (status, count) -> "$status $count" }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = breakdown,
                fontSize = 10.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )

            ui.mapping?.let { mapping ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (mapping.kind) {
                        LearnedActionMapping.Kind.ACTION -> "Runs instead: ${mapping.targetAction}"
                        LearnedActionMapping.Kind.MACRO -> "Runs instead: macro '${mapping.macroName}'"
                        LearnedActionMapping.Kind.DISMISSED -> "Dismissed — matching steps are skipped quietly."
                    },
                    fontSize = 12.sp,
                    color = AccentNeonGreen,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (expanded) {
                Divider(color = BorderColor, modifier = Modifier.padding(vertical = 10.dp))

                Text("Recent attempts:", fontSize = 11.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                group.recent.forEach { row ->
                    Text(
                        text = "• ${dateFormat.format(Date(row.timestamp))} — ${row.fixStatus}" +
                            (row.fixedWith?.let { " → $it" } ?: "") +
                            (if (row.goal.isNotBlank()) " — ${row.goal}" else ""),
                        fontSize = 11.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (ui.state) {
                    UnknownActionReviewState.PENDING -> {
                        when (teachMode) {
                            TeachMode.NONE -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { teachMode = TeachMode.ACTION },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton, contentColor = OnAccent)
                                    ) { Text("Map to action", fontSize = 11.sp) }
                                    Button(
                                        onClick = { teachMode = TeachMode.MACRO },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton, contentColor = OnAccent)
                                    ) { Text("Create macro", fontSize = 11.sp) }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = { viewModel.dismiss(group.key) }) {
                                    Text("Dismiss", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                            TeachMode.ACTION -> ActionMapEditor(
                                ui = ui,
                                registeredActions = registeredActions,
                                viewModel = viewModel,
                                onDone = { teachMode = TeachMode.NONE }
                            )
                            TeachMode.MACRO -> MacroMapEditor(
                                ui = ui,
                                registeredActions = registeredActions,
                                viewModel = viewModel,
                                onDone = { teachMode = TeachMode.NONE }
                            )
                        }
                    }
                    UnknownActionReviewState.DISMISSED, UnknownActionReviewState.CONVERTED -> {
                        if (ui.state == UnknownActionReviewState.CONVERTED &&
                            ui.mapping?.kind == LearnedActionMapping.Kind.MACRO
                        ) {
                            Text(
                                text = "Undo removes the teaching; the macro itself stays in Macros.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        OutlinedButton(onClick = { viewModel.undoReview(group.key) }) {
                            Text("Undo — back to review", fontSize = 11.sp, color = AccentOrange)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            expanded = !expanded
                            if (!expanded) teachMode = TeachMode.NONE
                        }
                )
            }
        }
    }
}

@Composable
private fun ActionMapEditor(
    ui: UnknownActionGroupUi,
    registeredActions: List<String>,
    viewModel: UnknownActionsViewModel,
    onDone: () -> Unit
) {
    val group = ui.group
    var selectedAction by remember(group.key) {
        mutableStateOf(group.suggestedTarget?.takeIf { it in registeredActions })
    }
    var paramValues by remember(group.key) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showPicker by remember(group.key) { mutableStateOf(false) }

    val definition = selectedAction?.let { viewModel.actionDefinition(it) }

    Column {
        Text(
            text = "When the model tries ${group.displayAction}, run this instead:",
            fontSize = 12.sp,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(onClick = { showPicker = true }) {
            Text(
                text = selectedAction ?: "Choose an existing action…",
                fontSize = 12.sp,
                color = if (selectedAction != null) AccentNeonGreen else TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }

        if (definition != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(definition.description, fontSize = 11.sp, color = TextSecondary)
            ParamFields(
                params = definition.params,
                values = paramValues,
                onValueChange = { name, value -> paramValues = paramValues + (name to value) }
            )
            Text(
                text = "Tip: {name} in a value reuses the matching value from the original request.",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    selectedAction?.let { target ->
                        viewModel.saveActionMapping(group.key, target, paramValues)
                    }
                    onDone()
                },
                enabled = selectedAction != null,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton, contentColor = OnAccent)
            ) { Text("Save teaching", fontSize = 11.sp, color = OnAccent) }
            TextButton(onClick = onDone) { Text("Cancel", fontSize = 11.sp, color = TextSecondary) }
        }
    }

    if (showPicker) {
        ActionPickerDialog(
            actions = registeredActions,
            onPick = { picked ->
                selectedAction = picked
                paramValues = emptyMap()
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun MacroMapEditor(
    ui: UnknownActionGroupUi,
    registeredActions: List<String>,
    viewModel: UnknownActionsViewModel,
    onDone: () -> Unit
) {
    val group = ui.group
    var macroName by remember(group.key) {
        mutableStateOf(
            group.sampleGoal.take(40).ifBlank { "Handle ${group.displayAction}" }
        )
    }
    var steps by remember(group.key) {
        mutableStateOf(
            listOf(
                MacroStepDraft(
                    action = group.suggestedTarget?.takeIf { it in registeredActions } ?: ""
                )
            )
        )
    }
    var pickerForStep by remember(group.key) { mutableStateOf<Int?>(null) }
    var error by remember(group.key) { mutableStateOf<String?>(null) }

    Column {
        Text(
            text = "Build a real macro from existing actions. It will appear in Macros and run whenever the model tries ${group.displayAction}.",
            fontSize = 12.sp,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = macroName,
            onValueChange = { macroName = it },
            label = { Text("Macro name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { pickerForStep = index },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = step.action.ifBlank { "Choose an action…" },
                        fontSize = 12.sp,
                        color = if (step.action.isNotBlank()) AccentNeonGreen else TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (steps.size > 1) {
                    IconButton(onClick = { steps = steps.filterIndexed { i, _ -> i != index } }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove step",
                            tint = AccentRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            val stepDefinition = step.action.takeIf { it.isNotBlank() }
                ?.let { viewModel.actionDefinition(it) }
            if (stepDefinition != null) {
                ParamFields(
                    params = stepDefinition.params,
                    values = step.params,
                    onValueChange = { name, value ->
                        steps = steps.mapIndexed { i, draft ->
                            if (i == index) draft.copy(params = draft.params + (name to value)) else draft
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        TextButton(onClick = { steps = steps + MacroStepDraft(action = "") }) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add step", fontSize = 11.sp, color = AccentNeonGreen)
        }

        error?.let {
            Text(it, color = AccentRed, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.saveMacroMapping(group.key, macroName, steps) { result ->
                        if (result == null) onDone() else error = result
                    }
                },
                enabled = macroName.isNotBlank() && steps.all { it.action.isNotBlank() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton, contentColor = OnAccent)
            ) { Text("Create macro & teach", fontSize = 11.sp, color = OnAccent) }
            TextButton(onClick = onDone) { Text("Cancel", fontSize = 11.sp, color = TextSecondary) }
        }
    }

    pickerForStep?.let { index ->
        ActionPickerDialog(
            actions = registeredActions,
            onPick = { picked ->
                steps = steps.mapIndexed { i, draft ->
                    if (i == index) MacroStepDraft(action = picked) else draft
                }
                pickerForStep = null
            },
            onDismiss = { pickerForStep = null }
        )
    }
}

/** Schema-driven parameter fields for one action — free text, or a dropdown for enums. */
@Composable
private fun ParamFields(
    params: List<ParamDefinition>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit
) {
    params.forEach { param ->
        Spacer(modifier = Modifier.height(8.dp))
        if (param.enumValues.isNotEmpty()) {
            var menuOpen by remember(param.name) { mutableStateOf(false) }
            Column {
                Text(
                    text = param.name + if (param.required) " *" else "",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(
                        text = values[param.name] ?: param.defaultValue?.toString() ?: "Choose…",
                        fontSize = 12.sp,
                        color = AccentNeonGreen,
                        fontFamily = FontFamily.Monospace
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    param.enumValues.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, fontSize = 12.sp) },
                            onClick = {
                                onValueChange(param.name, option)
                                menuOpen = false
                            }
                        )
                    }
                }
                Text(param.description, fontSize = 10.sp, color = TextSecondary)
            }
        } else {
            OutlinedTextField(
                value = values[param.name] ?: "",
                onValueChange = { onValueChange(param.name, it) },
                label = {
                    Text(
                        param.name + if (param.required) " *" else "",
                        fontSize = 11.sp
                    )
                },
                supportingText = { Text(param.description, fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Searchable picker over the real registered action set. */
@Composable
private fun ActionPickerDialog(
    actions: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, actions) {
        if (query.isBlank()) actions
        else actions.filter { it.contains(query.trim().uppercase(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an existing action", fontSize = 15.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search actions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered) { action ->
                        Text(
                            text = action,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(action) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    Text("No registered action matches.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
