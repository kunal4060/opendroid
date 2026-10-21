package com.opendroid.ai.core.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives Lane A's alarm broadcasts. Deliberately thin: all it does is
 * hand the task id to the expedited run worker (WorkManager owns the
 * execution lifecycle, so a process death after the broadcast cannot lose
 * the run) and, when the exact-alarm grant changes, re-arm everything —
 * alarms registered while the grant was off must be re-registered as
 * exact once it is granted.
 *
 * Not exported: only this app (via its own PendingIntents) and the system
 * (permission-state broadcast) can reach it.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScheduledTaskReceiverEntryPoint {
        fun scheduledTaskScheduler(): ScheduledTaskScheduler
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ScheduledTaskScheduler.ACTION_FIRE -> {
                val taskId = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_TASK_ID) ?: return
                ScheduledTaskRunWorker.enqueue(context, taskId)
            }
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        val entryPoint = EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            ScheduledTaskReceiverEntryPoint::class.java
                        )
                        entryPoint.scheduledTaskScheduler().rearmAll()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
