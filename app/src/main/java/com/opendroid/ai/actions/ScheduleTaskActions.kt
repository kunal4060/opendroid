package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.schedule.NextFireCalculator
import com.opendroid.ai.core.schedule.ScheduleRecurrence
import com.opendroid.ai.core.schedule.ScheduledTask
import com.opendroid.ai.core.schedule.ScheduledTaskKind
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskStore
import com.opendroid.ai.data.db.dao.MacroDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C4's chat creation path: the actions the planner can emit when the user
 * says "tomorrow at 7 AM, check the rain and…". Unlike the pre-C4
 * `SCHEDULE_MACRO` (which wrote a cron string into Room and stopped —
 * nothing ever fired), these create a real [ScheduledTask] in the store
 * AND arm it with the AlarmManager lane before reporting success.
 */
@Singleton
class ScheduleTaskActions @Inject constructor(
    private val store: ScheduledTaskStore,
    private val scheduler: ScheduledTaskScheduler,
    private val macroDao: MacroDao
) {

    fun getActions(): List<Action> = listOf(
        ScheduleAgentTaskAction(store, scheduler, macroDao),
        ListScheduledTasksAction(store),
        CancelScheduledTaskAction(store, scheduler)
    )

    private class ScheduleAgentTaskAction(
        private val store: ScheduledTaskStore,
        private val scheduler: ScheduledTaskScheduler,
        private val macroDao: MacroDao
    ) : Action {
        override val name: String = "SCHEDULE_AGENT_TASK"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val timeRaw = params["time"]?.trim()
                ?: return ActionResult(false, null, "time parameter missing (expected HH:mm)")
            val timeParts = timeRaw.split(":")
            val hour = timeParts.getOrNull(0)?.toIntOrNull()
            val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
            if (hour == null || hour !in 0..23 || minute !in 0..59) {
                return ActionResult(false, null, "time must be in HH:mm 24-hour format")
            }
            val recurrence = when (params["recurrence"]?.trim()?.lowercase()) {
                null, "", "once" -> ScheduleRecurrence.ONCE
                "daily" -> ScheduleRecurrence.DAILY
                "weekdays" -> ScheduleRecurrence.WEEKDAYS
                "weekly" -> ScheduleRecurrence.WEEKLY
                else -> return ActionResult(false, null, "recurrence must be once, daily, weekdays or weekly")
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            // Parse an explicit date for every recurrence that can use one:
            // previously only the ONCE branch read it, so a WEEKLY task's
            // "every week on Friday" date was silently dropped and today's
            // weekday was used instead (#28).
            val explicitDate: LocalDate? = params["date"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: Exception) {
                    return ActionResult(false, null, "date must be yyyy-MM-dd")
                }
            }
            val dateEpochDay: Long? = if (recurrence == ScheduleRecurrence.ONCE) {
                if (explicitDate != null) {
                    explicitDate.toEpochDay()
                } else {
                    // No date given: today if the time is still ahead, else tomorrow.
                    val candidate = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                    if (candidate > System.currentTimeMillis()) today.toEpochDay()
                    else today.plusDays(1).toEpochDay()
                }
            } else {
                null
            }
            // Reject a ONCE date/time that can never fire: one-shots may run
            // late by design up to NextFireCalculator.ONE_SHOT_STALE_MILLIS,
            // but beyond that window the task is dead on arrival and must
            // not be stored/reported as a successful schedule (#27).
            if (recurrence == ScheduleRecurrence.ONCE && dateEpochDay != null) {
                val occurrenceMillis = LocalDate.ofEpochDay(dateEpochDay)
                    .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                if (System.currentTimeMillis() - occurrenceMillis > NextFireCalculator.ONE_SHOT_STALE_MILLIS) {
                    return ActionResult(false, null, "That date and time is in the past — a one-time task set for then could never run. Please pick a future time.")
                }
            }
            val dayOfWeek: Int? = if (recurrence == ScheduleRecurrence.WEEKLY) {
                params["dayOfWeek"]?.trim()?.toIntOrNull()?.takeIf { it in 1..7 }
                    ?: explicitDate?.dayOfWeek?.value
                    ?: today.dayOfWeek.value
            } else {
                null
            }

            val macroName = params["macroName"]?.trim()?.takeIf { it.isNotEmpty() }
            val instruction = params["instruction"]?.trim().orEmpty()
            if (macroName == null && instruction.isEmpty()) {
                return ActionResult(false, null, "either instruction or macroName is required")
            }

            return try {
                val task: ScheduledTask
                if (macroName != null) {
                    val macro = macroDao.getMacroByName(macroName)
                        ?: return ActionResult(false, null, "Macro '$macroName' not found.")
                    task = ScheduledTask(
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
                } else {
                    task = ScheduledTask(
                        id = UUID.randomUUID().toString(),
                        kind = ScheduledTaskKind.INSTRUCTION,
                        label = instruction.take(60),
                        instruction = instruction,
                        recurrence = recurrence,
                        hour = hour,
                        minute = minute,
                        dayOfWeek = dayOfWeek,
                        dateEpochDay = dateEpochDay,
                        createdAtMillis = System.currentTimeMillis()
                    )
                }
                store.upsert(task)
                scheduler.arm(task)
                val next = NextFireCalculator.nextFireAfter(task, System.currentTimeMillis(), zone)
                val whenText = next?.let {
                    DateTimeFormatter.ofPattern("EEE, d MMM 'at' HH:mm", Locale.getDefault())
                        .format(Instant.ofEpochMilli(it).atZone(zone))
                } ?: "the scheduled time"
                ActionResult(
                    true,
                    "Scheduled '${task.label}' — it will run on $whenText. You can manage it in Macros → Scheduled Tasks.",
                    null
                )
            } catch (e: Exception) {
                Log.e("ScheduleAgentTask", "Scheduling failed", e)
                ActionResult(false, null, "Couldn't schedule that task.")
            }
        }
    }

    private class ListScheduledTasksAction(
        private val store: ScheduledTaskStore
    ) : Action {
        override val name: String = "LIST_SCHEDULED_TASKS"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val zone = ZoneId.systemDefault()
            val tasks = store.snapshot()
            if (tasks.isEmpty()) {
                return ActionResult(true, "You have no scheduled tasks.", null)
            }
            val formatter = DateTimeFormatter.ofPattern("EEE, d MMM HH:mm", Locale.getDefault())
            val lines = tasks.sortedBy { it.label }.map { task ->
                val next = if (task.enabled) {
                    NextFireCalculator.nextFireAfter(task, System.currentTimeMillis(), zone)
                } else {
                    null
                }
                val nextText = next?.let {
                    "next: ${formatter.format(Instant.ofEpochMilli(it).atZone(zone))}"
                } ?: "paused"
                "- ${task.label} (${task.recurrence.name.lowercase()}, $nextText)"
            }
            return ActionResult(true, "Your scheduled tasks:\n${lines.joinToString("\n")}", null)
        }
    }

    private class CancelScheduledTaskAction(
        private val store: ScheduledTaskStore,
        private val scheduler: ScheduledTaskScheduler
    ) : Action {
        override val name: String = "CANCEL_SCHEDULED_TASK"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["task"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ActionResult(false, null, "task parameter missing (id or label)")
            val tasks = store.snapshot()
            val exact = tasks.firstOrNull { it.id == query }
                ?: tasks.firstOrNull { it.label.equals(query, ignoreCase = true) }
            val match = exact ?: run {
                val partialMatches = tasks.filter { it.label.contains(query, ignoreCase = true) }
                // A fragment matching several tasks must not cancel
                // whichever happens to be listed first — ask instead.
                if (partialMatches.size > 1) {
                    return ActionResult(
                        false, null,
                        "'$query' matches ${partialMatches.size} scheduled tasks (${partialMatches.joinToString(", ") { it.label }}). Please name the exact task to cancel."
                    )
                }
                partialMatches.firstOrNull()
            } ?: return ActionResult(false, null, "No scheduled task matching '$query'.")
            return try {
                // Disarm AND delete: removing only one of the two is how
                // "cancelled" tasks used to keep firing in spirit.
                scheduler.cancel(match.id)
                match.sourceKey?.let { store.dismissSource(it, match.cronExpression) }
                store.delete(match.id)
                ActionResult(true, "Cancelled scheduled task '${match.label}'. It will not fire.", null)
            } catch (e: Exception) {
                Log.e("CancelScheduledTask", "Cancel failed", e)
                ActionResult(false, null, "Couldn't cancel that scheduled task.")
            }
        }
    }
}
