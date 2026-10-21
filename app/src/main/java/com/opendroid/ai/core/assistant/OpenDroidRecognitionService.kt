package com.opendroid.ai.core.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.opendroid.ai.core.voice.SpeechRecognitionEngine

/**
 * C3 (Tier 2) — the recognition service named by the voice-interaction
 * meta-data.
 *
 * A VoiceInteractionService stack is expected to name a recognition
 * service; this one delegates to the app's existing
 * [SpeechRecognitionEngine] (the same STT stack the chat mic uses) and
 * translates its high-level callbacks into the [RecognitionService.Callback]
 * protocol. It holds no recognition logic of its own.
 *
 * Threading: RecognitionService callbacks arrive on a binder thread while
 * SpeechRecognizer must be driven from the main thread, so every engine
 * interaction is posted to the main looper.
 */
class OpenDroidRecognitionService : RecognitionService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: SpeechRecognitionEngine? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        mainHandler.post {
            listener.onReadyForSpeech(Bundle())
            val active = engine ?: SpeechRecognitionEngine(this).also { engine = it }
            active.startListening(
                onResult = { text ->
                    listener.onEndOfSpeech()
                    listener.onResults(resultsBundle(text))
                },
                onPartialResult = { partial ->
                    listener.onPartialResults(resultsBundle(partial))
                },
                onError = {
                    // The engine reports errors as display strings; the
                    // protocol only carries codes, so map to a generic
                    // client error (the session UI surfaces the string).
                    listener.onError(SpeechRecognizer.ERROR_CLIENT)
                }
            )
        }
    }

    override fun onStopListening(listener: Callback) {
        mainHandler.post { engine?.stopListening() }
    }

    override fun onCancel(listener: Callback) {
        mainHandler.post { engine?.cancel() }
    }

    override fun onDestroy() {
        mainHandler.post { engine?.destroy() }
        engine = null
        super.onDestroy()
    }

    private fun resultsBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf(text)
        )
    }
}
