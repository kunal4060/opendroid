package com.opendroid.ai.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskSweepWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun scheduledTaskScheduler(): ScheduledTaskScheduler
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                OpenDroidService.start(context)
                rearmSchedules(context)
            }
            // Wall-clock schedules are stored as local time + zone, so a
            // clock or timezone change (and an app update, which can drop
            // registered alarms) must recompute every alarm.
            // ACTION_TIME_CHANGED's value is "android.intent.action.TIME_SET"
            // (the action declared in the manifest); there is no
            // Intent.ACTION_TIME_SET constant in the SDK.
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> rearmSchedules(context)
        }
    }

    /**
     * C4 re-arm: schedules survive reboot because the STORE persists and
     * this receiver re-registers every alarm from it, and re-ensures the
     * WorkManager sweep exists. Per the Android 14+/15 FGS-from-boot
     * restrictions, this path deliberately enqueues WorkManager work
     * only — it never starts a foreground service for scheduling.
     */
    private fun rearmSchedules(context: Context) {
        ScheduledTaskSweepWorker.enqueuePeriodicWork(context)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BootReceiverEntryPoint::class.java
                )
                entryPoint.scheduledTaskScheduler().rearmAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
