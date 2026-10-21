package com.opendroid.ai.core.voice.kws

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * The pre-D1 wake mechanism, kept ONLY as an explicit user choice in
 * Settings: it loops the system [SpeechRecognizer] and string-matches the
 * configured phrase in transcripts. On most devices that recogniser is a
 * cloud service — network-dependent and battery-hungry — which is why it
 * is no longer the default and is labelled "Legacy" wherever it appears.
 * It is never selected automatically: [WakeEnginePolicy] refuses to
 * substitute it when the offline engine cannot run.
 *
 * Behaviour matches the original `WakeWordDetector` implementation
 * (destroy/recreate recogniser on a 1 s restart loop); the phrase is now
 * configurable instead of hard-coded to "opendroid".
 */
class LegacySpeechWakeEngine(private val context: Context) : KeywordSpotterEngine {

    override val kind: WakeEngineKind = WakeEngineKind.LEGACY_SPEECH

    @Volatile private var keywordPhrase: String = WakeKeywordFactory.DEFAULT_PHRASE

    private var speechRecognizer: SpeechRecognizer? = null
    private var intent: Intent? = null
    private var onDetected: ((String) -> Unit)? = null

    @Volatile private var isListening = false

    /**
     * Consecutive times listening could not even start (no recognition
     * service / recogniser creation failed). Past a small bound the
     * engine gives up into the Error state instead of polling forever
     * while claiming to be "Listening" (finding #4).
     */
    private var consecutiveStartFailures = 0

    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { startSpeechListening() }

    init {
        intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    override fun configure(keywordPhrase: String, sensitivity: Float) {
        this.keywordPhrase = keywordPhrase
    }

    override fun start(onDetected: (String) -> Unit) {
        if (isListening) return
        this.onDetected = onDetected
        isListening = true
        consecutiveStartFailures = 0
        WakeWordStatus.publish(
            WakeWordEngineState.Listening, kind,
            "Legacy engine: the system speech recogniser does the listening — " +
                "on most devices it needs the network."
        )
        startSpeechListening()
    }

    private fun startSpeechListening() {
        if (!isListening) return
        cleanupRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            noteStartFailure()
            return
        }
        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            noteStartFailure()
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                scheduleRestart()
            }

            override fun onResults(results: Bundle?) {
                handleMatches(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleMatches(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
            // Listening actually started — earlier start failures were
            // transient, so the give-up counter starts over.
            consecutiveStartFailures = 0
        } catch (_: Exception) {
            noteStartFailure()
        }
    }

    /**
     * Counts a failure to even start listening. The first few retry on
     * the usual 1 s loop (a recogniser can be briefly unavailable while
     * another app holds it); past [MAX_START_FAILURES] the failure is
     * clearly permanent on this device, so the engine stops retrying and
     * says so instead of polling silently forever.
     */
    private fun noteStartFailure() {
        consecutiveStartFailures++
        if (consecutiveStartFailures < MAX_START_FAILURES) {
            scheduleRestart()
            return
        }
        isListening = false
        handler.removeCallbacks(restartRunnable)
        cleanupRecognizer()
        WakeWordStatus.publish(
            WakeWordEngineState.Error(
                "Legacy wake word unavailable: this device has no working speech recognition service"
            ),
            kind
        )
    }

    private fun handleMatches(matches: ArrayList<String>?) {
        if (matches == null) return
        val phrase = keywordPhrase.trim().lowercase()
        if (phrase.isEmpty()) return
        val squashed = phrase.replace(" ", "")
        for (match in matches) {
            val text = match.lowercase()
            if (text.contains(phrase) || text.contains(squashed)) {
                onDetected?.invoke(keywordPhrase)
                break
            }
        }
    }

    private fun scheduleRestart() {
        handler.removeCallbacks(restartRunnable)
        if (isListening) {
            // 1 s delay lets the audio system settle and prevents the rapid
            // restart beeping the original implementation suffered from.
            handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    override fun stop() {
        isListening = false
        handler.removeCallbacks(restartRunnable)
        cleanupRecognizer()
        if (WakeWordStatus.info.value.state !is WakeWordEngineState.Error) {
            WakeWordStatus.publish(WakeWordEngineState.Stopped, kind)
        }
    }

    override fun destroy() {
        stop()
        onDetected = null
    }

    private companion object {
        const val RESTART_DELAY_MS = 1000L

        /** Start failures tolerated (with 1 s retries) before giving up. */
        const val MAX_START_FAILURES = 3
    }
}
