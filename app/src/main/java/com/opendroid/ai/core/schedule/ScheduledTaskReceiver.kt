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
import java.time.ZoneId

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
        fun scheduledTaskStore(): ScheduledTaskStore
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ScheduledTaskScheduler.ACTION_FIRE -> {
                val taskId = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_TASK_ID) ?: return
                // Compute the occurrence this alarm is for and pass it
                // along: the run worker's unique-work name is keyed by
                // occurrence, so the alarm lane must name the same
                // occurrence as the sweep lane for KEEP to dedupe them
                // (previously the alarm lane always used the
                // occurrence-less name and the lanes never matched).
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        val entryPoint = EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            ScheduledTaskReceiverEntryPoint::class.java
                        )
                        val task = entryPoint.scheduledTaskStore().get(taskId)
                        val occurrence = task?.let {
                            NextFireCalculator.dueOccurrence(
                                it,
                                System.currentTimeMillis(),
                                ZoneId.systemDefault()
                            )
                        }
                        ScheduledTaskRunWorker.enqueue(context, taskId, occurrence)
                    } finally {
                        pendingResult.finish()
                    }
                }
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
