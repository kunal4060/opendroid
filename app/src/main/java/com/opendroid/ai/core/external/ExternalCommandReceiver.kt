package com.opendroid.ai.core.external

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opendroid.ai.core.service.OpenDroidService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Headless entry point for the external intent API (feature C7): Tasker's
 * Send Intent, MacroDroid, and `adb shell am broadcast`.
 *
 * Exported on purpose — this IS the feature's surface — but it decides
 * nothing itself: every intent is funnelled into [ExternalCommandGateway],
 * which enforces the master toggle, the API token, and the app lock before
 * anything executes, and audits every outcome. Results return through the
 * gateway's explicit reply broadcast when the caller supplied a reply
 * target; see `docs/external-intents.md` for the full contract.
 */
class ExternalCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        // A broadcast's goAsync() window is only ~10s, but a macro (or
        // command) can run far longer — executing inside that window got
        // runs frozen/killed mid-flight with the stored last-run record
        // stuck at RUNNING. Hand execution off to the app's foreground
        // service so the process outlives the broadcast, finish the
        // pending result immediately, and let the gateway deliver the
        // outcome asynchronously through its reply broadcast.
        try {
            OpenDroidService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service for external command", e)
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    ExternalCommandGateway.ExternalCommandEntryPoint::class.java
                )
                entryPoint.externalCommandGateway().handle(intent, ExternalCaller.BROADCAST)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle external intent", e)
            }
        }
        pendingResult.finish()
    }

    companion object {
        private const val TAG = "ExternalCommandReceiver"
    }
}
