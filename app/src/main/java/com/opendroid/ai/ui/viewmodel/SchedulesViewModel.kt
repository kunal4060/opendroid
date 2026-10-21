package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.schedule.MacroScheduleSync
import com.opendroid.ai.core.schedule.ScheduleRecurrence
import com.opendroid.ai.core.schedule.ScheduledTask
import com.opendroid.ai.core.schedule.ScheduledTaskKind
import com.opendroid.ai.core.schedule.ScheduledTaskRunWorker
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskStore
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * C4 Schedules UI state: the task list comes straight from the store (the
 * single source of truth both firing lanes read), and every mutation goes
 * store-first, then (dis)arms the AlarmManager lane — a task that exists
 * but is not armed does not exist as far as firing is concerned.
 */
@HiltViewModel
class SchedulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScheduledTaskStore,
    private val scheduler: ScheduledTaskScheduler,
    private val macroScheduleSync: MacroScheduleSync,
    macroDao: MacroDao
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTask>> = store.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val macros: StateFlow<List<MacroEntity>> = macroDao.getAllMacrosFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exactAlarmGranted = MutableStateFlow(scheduler.canScheduleExactAlarms())
    val exactAlarmGranted: StateFlow<Boolean> = _exactAlarmGranted.asStateFlow()

    init {
        // Opening the screen is also a sync point: macro cron triggers
        // materialise here even if the sweep hasn't run yet.
        viewModelScope.launch { macroScheduleSync.sync() }
    }

    /** Re-checks the exact-alarm grant (e.g. after returning from system Settings) and re-arms with the fresh grant state. */
    fun refreshPermissionState() {
        _exactAlarmGranted.value = scheduler.canScheduleExactAlarms()
        viewModelScope.launch { scheduler.rearmAll() }
    }

    /** Intent for the system "Alarms & reminders" page for this app. */
    fun exactAlarmSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createInstruction(
        instruction: String,
        hour: Int,
        minute: Int,
        recurrence: ScheduleRecurrence,
        dayOfWeek: Int?,
        dateEpochDay: Long?
    ) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            kind = ScheduledTaskKind.INSTRUCTION,
            label = trimmed.take(60),
            instruction = trimmed,
            recurrence = recurrence,
            hour = hour,
            minute = minute,
            dayOfWeek = dayOfWeek,
            dateEpochDay = dateEpochDay,
            createdAtMillis = System.currentTimeMillis()
        )
        viewModelScope.launch {
            store.upsert(task)
            scheduler.arm(task)
        }
    }

    fun createForMacro(
        macro: MacroEntity,
        hour: Int,
        minute: Int,
        recurrence: ScheduleRecurrence,
        dayOfWeek: Int?,
        dateEpochDay: Long?
    ) {
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            kind = ScheduledTaskKind.MACRO,
            label = macro.name,
            macroId = macro.id,
            macroName = macro.name,
            recurrence = recurrence,
            hour = hour,
            minute = minute,
            dayOfWeek = dayOfWeek,
            dateEpochDay = dateEpochDay,
            createdAtMillis = System.currentTimeMillis()
        )
        viewModelScope.launch {
            store.upsert(task)
            scheduler.arm(task)
        }
    }

    /** Pause/resume: pausing disarms the alarm lane (a paused task cannot fire); resuming re-arms it. */
    fun setEnabled(task: ScheduledTask, enabled: Boolean) {
        viewModelScope.launch {
            val updated = store.update(task.id) { it.copy(enabled = enabled) } ?: return@launch
            if (enabled) scheduler.arm(updated) else scheduler.cancel(task.id)
        }
    }

    /** Deletes the task AND disarms it — a cancelled task must not fire. */
    fun delete(task: ScheduledTask) {
        viewModelScope.launch {
            scheduler.cancel(task.id)
            // For sync-materialised tasks, remember the dismissal so the
            // macro's cron trigger doesn't silently resurrect it.
            task.sourceKey?.let { store.dismissSource(it, task.cronExpression) }
            store.delete(task.id)
        }
    }

    /** Runs the task immediately through the same worker/executor path a firing uses. */
    fun runNow(task: ScheduledTask) {
        ScheduledTaskRunWorker.enqueue(context, task.id, occurrenceMillis = null, manual = true)
    }
}
