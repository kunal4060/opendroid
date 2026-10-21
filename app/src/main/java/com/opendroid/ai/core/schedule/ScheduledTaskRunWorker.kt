package com.opendroid.ai.core.schedule

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Lane-neutral execution vehicle for C4: both the AlarmManager lane
 * ([ScheduledTaskReceiver]) and the WorkManager sweep lane
 * ([ScheduledTaskSweepWorker]) enqueue this worker, and the
 * [ScheduledTaskExecutor]'s store claim decides which of them actually
 * runs a given occurrence — so a task can be enqueued twice and still
 * fire exactly once.
 *
 * Enqueued as expedited work so an alarm-fired run starts immediately
 * rather than waiting for a WorkManager window; when the expedited quota
 * is exhausted it degrades to a regular one-time request.
 */
class ScheduledTaskRunWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScheduledTaskRunEntryPoint {
        fun scheduledTaskExecutor(): ScheduledTaskExecutor
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val occurrence = inputData.getLong(KEY_OCCURRENCE, NO_OCCURRENCE)
            .takeIf { it != NO_OCCURRENCE }
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ScheduledTaskRunEntryPoint::class.java
        )
        entryPoint.scheduledTaskExecutor().executeTask(taskId, occurrence, manual)
        // A skipped/failed run is still a completed unit of work: the
        // outcome lives on the task row and in task history, and retrying
        // from WorkManager would risk a duplicate firing.
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            ScheduledTaskNotifier.CHANNEL_ID
        )
            .setContentTitle("Running scheduled task")
            .setContentText("OpenDroid is running a task you scheduled.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_OCCURRENCE = "occurrence_millis"
        private const val KEY_MANUAL = "manual"
        private const val NO_OCCURRENCE = -1L
        private const val FOREGROUND_NOTIFICATION_ID = 4099

        fun enqueue(
            context: Context,
            taskId: String,
            occurrenceMillis: Long? = null,
            manual: Boolean = false
        ) {
            val data = Data.Builder()
                .putString(KEY_TASK_ID, taskId)
                .putBoolean(KEY_MANUAL, manual)
                .apply {
                    if (occurrenceMillis != null) putLong(KEY_OCCURRENCE, occurrenceMillis)
                }
                .build()
            val request = OneTimeWorkRequestBuilder<ScheduledTaskRunWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // Unique per (task, occurrence): the alarm lane and the sweep
            // lane enqueue the same name for the same occurrence and
            // WorkManager keeps only one of them. Manual "run now" gets
            // its own name component: sharing the occurrence-less name
            // with scheduled work under KEEP silently dropped whichever
            // request arrived second.
            val uniqueName = when {
                manual -> "schedule_run_${taskId}_manual"
                occurrenceMillis != null -> "schedule_run_${taskId}_$occurrenceMillis"
                else -> "schedule_run_${taskId}_adhoc"
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
