package com.opendroid.ai.core.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.opendroid.ai.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts C4's user-visible run outcomes. Every scheduled run produces one
 * notification — result, approval-needed, or missed — tapping it opens the
 * app on the task's own chat session, where the run's turns (and, for an
 * approval, the waiting plan) actually live.
 *
 * Note: on Android 13+ these only appear when the user has granted
 * POST_NOTIFICATIONS; the run itself and its chat/history records are
 * unaffected either way.
 */
@Singleton
class ScheduledTaskNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scheduled tasks",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Results of scheduled agent tasks"
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun notifyResult(task: ScheduledTask, success: Boolean, summary: String) {
        val title = if (success) "Scheduled task done: ${task.label}" else "Scheduled task failed: ${task.label}"
        post(task, title, summary, android.R.drawable.ic_lock_idle_alarm)
    }

    fun notifyApprovalNeeded(task: ScheduledTask, blockedActions: List<String>) {
        val blocked = blockedActions.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: "a step"
        post(
            task,
            "Scheduled task needs your approval: ${task.label}",
            "It stopped before running $blocked. Open the chat to review and approve the plan.",
            android.R.drawable.ic_dialog_alert
        )
    }

    fun notifyMissed(task: ScheduledTask) {
        post(
            task,
            "Scheduled task missed: ${task.label}",
            "Its time passed more than a day ago while the device was off, so it was not run late.",
            android.R.drawable.ic_dialog_info
        )
    }

    private fun post(task: ScheduledTask, title: String, text: String, icon: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setContentIntent(openSessionPendingIntent(task))
            .build()
        notificationManager.notify(notificationId(task.id), notification)
    }

    private fun openSessionPendingIntent(task: ScheduledTask): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            task.sessionId?.let { putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationId(taskId: String): Int = BASE_NOTIFICATION_ID + (taskId.hashCode() and 0x3FFF)

    companion object {
        const val CHANNEL_ID = "opendroid_scheduled_tasks"
        private const val BASE_NOTIFICATION_ID = 4100
    }
}
