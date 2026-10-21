package com.opendroid.ai

import android.app.Application
import android.util.Log
import com.opendroid.ai.core.crash.CrashLogRecorder
import com.opendroid.ai.core.crash.DeviceMetadata
import com.opendroid.ai.core.crash.OpenDroidCrashHandler
import com.opendroid.ai.core.memory.MemoryManager
import com.opendroid.ai.core.mcp.McpToolBridge
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskSweepWorker
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.core.security.LegacyPreferenceMigration
import com.opendroid.ai.widget.WidgetStateSync
import com.opendroid.ai.data.crash.CrashLogRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpenDroidApp : Application() {

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var crashLogRepository: CrashLogRepository

    @Inject
    lateinit var legacyPreferenceMigration: LegacyPreferenceMigration

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var widgetStateSync: WidgetStateSync

    @Inject
    lateinit var scheduledTaskScheduler: ScheduledTaskScheduler

    @Inject
    lateinit var mcpToolBridge: McpToolBridge

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Installed first so that a crash in any later startup step is captured.
        installCrashHandler()

        // Arm the app-lock gate (A5): registers the process-lifecycle observer
        // that re-locks the UI after the background timeout. The manager's
        // initial state already reflects a cold start (locked when enabled).
        appLockManager.start()

        // Keep the home-screen widget + dynamic shortcuts fed with live
        // repository data (C1): observes routines, task history, routing and
        // briefing stamps for the whole process lifetime. Best-effort — the
        // sync guards its own writes, so a failure here never blocks startup.
        widgetStateSync.start()

        // D5: connect the configured MCP servers and register their tools
        // with the agent (background; unreachable servers simply contribute
        // no tools and show their error in Settings → MCP Servers).
        mcpToolBridge.start()

        // Retire the legacy preference files into the direct-Keystore stores. This opens the
        // Keystore and the legacy keyset, so it runs off the main thread; splash routing and
        // onboarding await it before reading. It is a no-op once every value has been imported.
        legacyPreferenceMigration.start()

        // One-time startup cleanup: remove any poisoned memory entries
        // that may have been stored by previous versions of the app
        appScope.launch {
            try {
                memoryManager.cleanPoisonedMemories()
            } catch (e: Exception) {
                // Silently ignore cleanup errors to not block app startup
            }
        }

        // C4: make sure the scheduled-task sweep exists and every alarm is
        // registered, even when the process was started by the user rather
        // than by the boot receiver (alarms can be dropped by force-stops
        // and OEM task killers; re-arming on every cold start is the cheap
        // self-heal). Enqueueing WorkManager work from Application.onCreate
        // is always allowed — no foreground service is started here.
        appScope.launch {
            try {
                ScheduledTaskSweepWorker.enqueuePeriodicWork(this@OpenDroidApp)
                scheduledTaskScheduler.rearmAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to arm scheduled tasks", e)
            }
        }
    }

    private fun installCrashHandler() {
        try {
            val recorder = CrashLogRecorder(
                sink = crashLogRepository,
                // Read here, at startup, rather than at crash time - a dying
                // process is the wrong place to be querying PackageManager.
                metadata = DeviceMetadata.fromContext(this),
                onRecordingFailed = { Log.e(TAG, "Failed to record crash", it) }
            )
            OpenDroidCrashHandler.install(recorder)
        } catch (t: Throwable) {
            // Crash logging is best-effort. Failing to install it must never be
            // the reason the app does not start.
            Log.e(TAG, "Failed to install crash handler", t)
        }
    }

    companion object {
        private const val TAG = "OpenDroidApp"
    }
}
