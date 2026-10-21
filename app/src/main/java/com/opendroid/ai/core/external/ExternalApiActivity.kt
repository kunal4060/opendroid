package com.opendroid.ai.core.external

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity entry point for the external intent API (feature C7): Tasker's
 * activity intents and `adb shell am start`.
 *
 * It shows no UI of its own (translucent, no-history): the intent is handed
 * to [ExternalCommandGateway] — the same toggle/token/lock pipeline as the
 * broadcast receiver — and the activity finishes. Two return channels:
 *  - If the caller supplied a reply target, the gateway sends the explicit
 *    [ExternalIntentContract.ACTION_COMMAND_RESULT] broadcast.
 *  - If the caller started this activity FOR RESULT, the outcome extras
 *    (same keys as the result broadcast) are delivered via `setResult` —
 *    this is the only channel an activity can return data on, and the one
 *    QUERY_STATUS is designed around.
 */
class ExternalApiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gateway = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                ExternalCommandGateway.ExternalCommandEntryPoint::class.java
            ).externalCommandGateway()
        } catch (e: Exception) {
            Log.e(TAG, "External gateway unavailable", e)
            finish()
            return
        }
        val requestIntent = intent
        lifecycleScope.launch {
            val outcome = try {
                // Off the main thread: a macro run executes synchronously in
                // the gateway and may take a while.
                withContext(Dispatchers.Default) {
                    gateway.handle(requestIntent, ExternalCaller.ACTIVITY)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle external intent", e)
                ExternalOutcome(
                    success = false,
                    code = ExternalIntentContract.CODE_EXECUTION_FAILED,
                    summary = "Internal error while handling the request.",
                    requestId = null
                )
            }
            if (!isFinishing) {
                val resultIntent = Intent().apply {
                    outcome.resultExtras().forEach { (key, value) -> putExtra(key, value) }
                }
                setResult(
                    if (outcome.success) RESULT_OK else RESULT_CANCELED,
                    resultIntent
                )
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "ExternalApiActivity"
    }
}
