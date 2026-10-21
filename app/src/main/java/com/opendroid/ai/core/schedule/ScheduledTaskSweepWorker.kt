package com.opendroid.ai.core.schedule

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Lane B of C4's two-lane scheduler: a 15-minute WorkManager sweep,
 * modelled directly on the in-repo `SocialScheduleWorker` pattern (Hilt
 * EntryPointAccessors + unique periodic work).
 *
 * The alarm lane is precise but not invincible — OEM ROMs suppress
 * alarms, exact-alarm grants lapse, processes die. The sweep is the
 * backstop: every 15 minutes it
 *   1. syncs macro `cron:` triggers into schedulable tasks,
 *   2. marks one-shot tasks that went stale (>24h) as MISSED,
 *   3. enqueues a run for every task whose occurrence is due,
 *   4. re-arms all alarms (self-healing after anything that dropped them).
 *
 * It never executes a task itself — execution goes through
 * [ScheduledTaskRunWorker] + the store claim, same as the alarm lane.
 */
class ScheduledTaskSweepWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScheduledTaskSweepEntryPoint {
        fun scheduledTaskStore(): ScheduledTaskStore
        fun scheduledTaskScheduler(): ScheduledTaskScheduler
        fun scheduledTaskNotifier(): ScheduledTaskNotifier
        fun macroScheduleSync(): MacroScheduleSync
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ScheduledTaskSweepEntryPoint::class.java
            )
            val store = entryPoint.scheduledTaskStore()
            val scheduler = entryPoint.scheduledTaskScheduler()
            val notifier = entryPoint.scheduledTaskNotifier()

            entryPoint.macroScheduleSync().sync()

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            for (task in store.snapshot()) {
                if (NextFireCalculator.isMissedOneShot(task, now, zone)) {
                    store.update(task.id) {
                        it.copy(status = ScheduledTaskStatus.MISSED, enabled = false)
                    }
                    scheduler.cancel(task.id)
                    notifier.notifyMissed(task)
                    continue
                }
                val due = NextFireCalculator.dueOccurrence(task, now, zone)
                if (due != null) {
                    ScheduledTaskRunWorker.enqueue(applicationContext, task.id, due)
                }
            }

            scheduler.rearmAll(zone)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sweep failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskSweep"
        const val WORK_NAME = "opendroid_scheduled_task_sweep"

        fun enqueuePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledTaskSweepWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
