package com.opendroid.ai.core.external

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    ExternalCommandGateway.ExternalCommandEntryPoint::class.java
                )
                entryPoint.externalCommandGateway().handle(intent, ExternalCaller.BROADCAST)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle external intent", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ExternalCommandReceiver"
    }
}
