package com.opendroid.ai.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opendroid.ai.core.schedule.NextFireCalculator
import com.opendroid.ai.core.schedule.ScheduleRecurrence
import com.opendroid.ai.core.schedule.ScheduledTask
import com.opendroid.ai.core.schedule.ScheduledTaskKind
import com.opendroid.ai.core.schedule.ScheduledTaskStatus
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentOrange
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.SchedulesViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * C4 Schedules UI: create, list, pause/resume, run-now and delete scheduled
 * agent tasks. Everything shown comes from the same store the firing lanes
 * read, so "next run" here is the time the alarm lane actually armed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(
    viewModel: SchedulesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsState()
    val macros by viewModel.macros.collectAsState()
    val exactAlarmGranted by viewModel.exactAlarmGranted.collectAsState()

    // Returning from the system "Alarms & reminders" page must refresh the
    // banner — and re-arm, since alarms registered while the grant was off
    // were inexact.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissionState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SCHEDULED TASKS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (!exactAlarmGranted) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AccentOrange.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "EXACT TIMING IS OFF",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentOrange
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Without the Alarms & reminders permission, tasks still run but may fire late (Android defers inexact alarms in Doze). Enable it for on-time runs.",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { context.startActivity(viewModel.exactAlarmSettingsIntent()) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                            ) {
                                Text("Enable exact alarms", color = Color.Black)
                            }
                        }
                    }
                }
            }

            item {
                CreateScheduleCard(
                    macros = macros,
                    onCreateInstruction = { instruction, hour, minute, recurrence, dayOfWeek, dateEpochDay ->
                        viewModel.createInstruction(instruction, hour, minute, recurrence, dayOfWeek, dateEpochDay)
                    },
                    onCreateForMacro = { macro, hour, minute, recurrence, dayOfWeek, dateEpochDay ->
                        viewModel.createForMacro(macro, hour, minute, recurrence, dayOfWeek, dateEpochDay)
                    }
                )
            }

            if (tasks.isEmpty()) {
                item {
                    Text(
                        text = "No scheduled tasks yet. Create one above — or ask in chat: \"tomorrow at 7 AM, check if it will rain\". Macros with a cron schedule appear here automatically.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    ScheduledTaskCard(
                        task = task,
                        onSetEnabled = { viewModel.setEnabled(task, it) },
                        onRunNow = { viewModel.runNow(task) },
                        onDelete = { viewModel.delete(task) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScheduleCard(
    macros: List<MacroEntity>,
    onCreateInstruction: (String, Int, Int, ScheduleRecurrence, Int?, Long?) -> Unit,
    onCreateForMacro: (MacroEntity, Int, Int, ScheduleRecurrence, Int?, Long?) -> Unit
) {
    var kind by remember { mutableStateOf(ScheduledTaskKind.INSTRUCTION) }
    var instruction by remember { mutableStateOf("") }
    var selectedMacro by remember { mutableStateOf<MacroEntity?>(null) }
    var macroMenuOpen by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(7) }
    var minute by remember { mutableStateOf(0) }
    var showTimePicker by remember { mutableStateOf(false) }
    var recurrence by remember { mutableStateOf(ScheduleRecurrence.ONCE) }
    var dayOfWeek by remember { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    var dateEpochDay by remember { mutableStateOf(LocalDate.now().plusDays(1).toEpochDay()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "NEW SCHEDULED TASK",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == ScheduledTaskKind.INSTRUCTION,
                    onClick = { kind = ScheduledTaskKind.INSTRUCTION },
                    label = { Text("Instruction") }
                )
                FilterChip(
                    selected = kind == ScheduledTaskKind.MACRO,
                    onClick = { kind = ScheduledTaskKind.MACRO },
                    label = { Text("Macro") }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (kind == ScheduledTaskKind.INSTRUCTION) {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("What should the agent do?") },
                    placeholder = { Text("e.g. Check if it will rain and tell me if I need an umbrella") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = macroMenuOpen,
                    onExpandedChange = { macroMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = selectedMacro?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Macro to run") },
                        placeholder = { Text(if (macros.isEmpty()) "No macros saved yet" else "Choose a macro") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = macroMenuOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = macroMenuOpen,
                        onDismissRequest = { macroMenuOpen = false }
                    ) {
                        macros.forEach { macro ->
                            DropdownMenuItem(
                                text = { Text(macro.name) },
                                onClick = {
                                    selectedMacro = macro
                                    macroMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time:", color = TextPrimary, fontSize = 13.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = { showTimePicker = true }) {
                    Text(String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
                }
                if (recurrence == ScheduleRecurrence.ONCE) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { showDatePicker = true }) {
                        Text(
                            LocalDate.ofEpochDay(dateEpochDay)
                                .format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ScheduleRecurrence.ONCE to "Once",
                    ScheduleRecurrence.DAILY to "Daily",
                    ScheduleRecurrence.WEEKDAYS to "Weekdays",
                    ScheduleRecurrence.WEEKLY to "Weekly"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = recurrence == value,
                        onClick = { recurrence = value },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            if (recurrence == ScheduleRecurrence.WEEKLY) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                        5 to "Fri", 6 to "Sat", 7 to "Sun"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = dayOfWeek == value,
                            onClick = { dayOfWeek = value },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val canCreate = when (kind) {
                ScheduledTaskKind.INSTRUCTION -> instruction.isNotBlank()
                ScheduledTaskKind.MACRO -> selectedMacro != null
            }
            Button(
                enabled = canCreate,
                onClick = {
                    val effectiveDayOfWeek = if (recurrence == ScheduleRecurrence.WEEKLY) dayOfWeek else null
                    val effectiveDate = if (recurrence == ScheduleRecurrence.ONCE) dateEpochDay else null
                    when (kind) {
                        ScheduledTaskKind.INSTRUCTION ->
                            onCreateInstruction(instruction, hour, minute, recurrence, effectiveDayOfWeek, effectiveDate)
                        ScheduledTaskKind.MACRO ->
                            selectedMacro?.let {
                                onCreateForMacro(it, hour, minute, recurrence, effectiveDayOfWeek, effectiveDate)
                            }
                    }
                    confirmation = "Scheduled — it will fire at the chosen time and report back here and in its own chat."
                    instruction = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add schedule")
            }
            confirmation?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = AccentCyan, lineHeight = 16.sp)
            }
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Fire time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    hour = timePickerState.hour
                    minute = timePickerState.minute
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(dateEpochDay)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        dateEpochDay = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTask,
    onSetEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM HH:mm", Locale.getDefault())
    val nextFire = if (task.enabled) {
        NextFireCalculator.nextFireAfter(task, System.currentTimeMillis(), zone)
    } else {
        null
    }
    val statusColor = when (task.status) {
        ScheduledTaskStatus.ARMED -> AccentCyan
        ScheduledTaskStatus.RUNNING -> AccentOrange
        ScheduledTaskStatus.AWAITING_APPROVAL -> AccentOrange
        ScheduledTaskStatus.FAILED -> AccentRed
        ScheduledTaskStatus.DONE -> TextSecondary
        ScheduledTaskStatus.MISSED -> AccentRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = describeSchedule(task),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                Text(
                    text = task.status.name.replace('_', ' '),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = task.enabled, onCheckedChange = onSetEnabled)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    !task.enabled -> "Paused — will not fire."
                    nextFire != null -> "Next run: " +
                        dateTimeFormatter.format(Instant.ofEpochMilli(nextFire).atZone(zone))
                    else -> "No future run."
                },
                fontSize = 11.sp,
                color = TextSecondary
            )
            task.lastResultSummary?.let { summary ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last run: $summary",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
            }
            if (task.sourceKey != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "From a macro's cron schedule — deleting it here keeps it deleted unless the macro's schedule changes.",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    lineHeight = 14.sp
                )
            }

            Row {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRunNow) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run now", tint = AccentCyan)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete schedule", tint = AccentRed)
                }
            }
        }
    }
}

private fun describeSchedule(task: ScheduledTask): String {
    val time = String.format(Locale.getDefault(), "%02d:%02d", task.hour, task.minute)
    return when (task.recurrence) {
        ScheduleRecurrence.CRON -> "Cron: ${task.cronExpression ?: ""} · " +
            if (task.kind == ScheduledTaskKind.MACRO) "macro" else "instruction"
        ScheduleRecurrence.ONCE -> {
            val date = task.dateEpochDay?.let {
                LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
            } ?: ""
            "Once · $date · $time"
        }
        ScheduleRecurrence.DAILY -> "Daily · $time"
        ScheduleRecurrence.WEEKDAYS -> "Weekdays · $time"
        ScheduleRecurrence.WEEKLY -> {
            val day = task.dayOfWeek?.let {
                java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            } ?: ""
            "Weekly · $day · $time"
        }
    }
}
