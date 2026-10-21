package com.opendroid.ai.wear

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.wear.input.RemoteInputIntentHelper

/**
 * The watch app's single Activity (feature D7). Thin by design: it owns
 * the [WearRepository] (Data Layer) and the voice-input intent flow, and
 * renders [WearApp]. All agent work happens on the phone.
 *
 * Voice input follows the research's lightest reliable path:
 *  1. `RemoteInputIntentHelper.createActionRemoteInputIntent()` — the
 *     system dictation / text-entry UI (voice or keyboard), result read
 *     back through [RemoteInput.getResultsFromIntent].
 *  2. If that intent has no handler on this watch, fall back to the
 *     speech recognizer intent.
 *  3. If neither exists, say so honestly in the command-state area —
 *     the user is never left with a dead button.
 */
class WearMainActivity : ComponentActivity() {

    private lateinit var repository: WearRepository

    private val remoteInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = RemoteInput.getResultsFromIntent(result.data ?: Intent())
            ?.getString(KEY_COMMAND_REPLY)
            ?.trim()
        if (!text.isNullOrEmpty()) {
            repository.sendCommand(text)
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!text.isNullOrEmpty()) {
            repository.sendCommand(text)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Granted or not, attempt the RemoteInput flow: its system UI can
        // still offer text entry, and a hard failure surfaces honestly.
        launchCommandInput()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WearRepository(applicationContext)
        repository.start()
        setContent {
            WearApp(
                repository = repository,
                onVoiceCommand = { onVoiceCommandTapped() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) repository.refreshConnection()
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.stop()
        super.onDestroy()
    }

    private fun onVoiceCommandTapped() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCommandInput()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchCommandInput() {
        val remoteInput = RemoteInput.Builder(KEY_COMMAND_REPLY)
            .setLabel("Say or type a command")
            .build()
        // wear-input 1.2.0 has no RemoteInputIntent class: the helper builds
        // the ACTION_REMOTE_INPUT intent and attaches the inputs extra
        // (platform RemoteInput, as a List) — same flow, real API.
        val remoteInputIntent = RemoteInputIntentHelper.putRemoteInputsExtra(
            RemoteInputIntentHelper.createActionRemoteInputIntent(),
            listOf(remoteInput)
        )
        try {
            remoteInputLauncher.launch(remoteInputIntent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No RemoteInput handler on this watch; trying speech recognizer", e)
        }
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command for OpenDroid")
        }
        try {
            speechLauncher.launch(speechIntent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No voice input of any kind on this watch", e)
            repository.reportInputUnavailable()
        }
    }

    companion object {
        private const val TAG = "WearMainActivity"
        private const val KEY_COMMAND_REPLY = "opendroid_command_reply"
    }
}
