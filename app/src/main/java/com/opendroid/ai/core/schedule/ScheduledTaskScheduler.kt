package com.opendroid.ai.core.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lane A of C4's two-lane scheduler: AlarmManager, for precise,
 * user-visible fire times.
 *
 * - When the app holds the exact-alarm grant, alarms are registered with
 *   `setExactAndAllowWhileIdle` (RTC_WAKEUP, so Doze does not defer them).
 * - When the grant is missing (the default for fresh installs targeting
 *   API 33+ on Android 14+), it degrades to `setAndAllowWhileIdle` —
 *   inexact, Doze-deferred — and the Schedules UI surfaces a banner
 *   deep-linking the system "Alarms & reminders" settings. Lane B (the
 *   WorkManager sweep) backstops anything the inexact alarm delivers late.
 *
 * Only one-shot alarms are ever registered; recurrence is handled by
 * re-arming the next occurrence after each run (setRepeating has been
 * inexact since API 19 and cannot express weekday/cron rules anyway).
 *
 * The manifest declares only `SCHEDULE_EXACT_ALARM` (the grantable
 * permission). `USE_EXACT_ALARM` was removed by C4: it is Play-policy
 * restricted to alarm-clock/calendar core-function apps, which a general
 * AI agent is not.
 */
@Singleton
class ScheduledTaskScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScheduledTaskStore
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True when exact alarms may be scheduled right now. */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

    /**
     * (Re)registers the alarm for [task]'s next occurrence, or cancels any
     * existing alarm when the task is disabled or has no future occurrence.
     */
    fun arm(task: ScheduledTask, zone: ZoneId = ZoneId.systemDefault()) {
        if (!task.enabled) {
            cancel(task.id)
            return
        }
        val next = NextFireCalculator.nextFireAfter(task, System.currentTimeMillis(), zone)
        if (next == null) {
            cancel(task.id)
            return
        }
        val pendingIntent = firePendingIntent(task.id)
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent)
        }
    }

    /** Disarms the task's alarm. Together with deleting/disabling the task, this is what makes a cancelled task never fire. */
    fun cancel(taskId: String) {
        alarmManager.cancel(firePendingIntent(taskId))
    }

    /** Re-arms every task from the store. Called on boot, time/timezone change, app update, app start, and each sweep. */
    suspend fun rearmAll(zone: ZoneId = ZoneId.systemDefault()) {
        store.snapshot().forEach { arm(it, zone) }
    }

    private fun firePendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
            // Make the intent unique per task even before extras are compared.
            data = android.net.Uri.parse("opendroid://scheduled-task/$taskId")
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_FIRE = "com.opendroid.ai.action.SCHEDULED_TASK_FIRE"
        const val EXTRA_TASK_ID = "com.opendroid.ai.extra.SCHEDULED_TASK_ID"
    }
}
