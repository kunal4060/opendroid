package com.opendroid.ai.core.voice

import android.content.Context
import com.opendroid.ai.core.voice.kws.KeywordSpotterEngine
import com.opendroid.ai.core.voice.kws.LegacySpeechWakeEngine
import com.opendroid.ai.core.voice.kws.SherpaKwsEngine
import com.opendroid.ai.core.voice.kws.WakeEngineKind
import com.opendroid.ai.core.voice.kws.WakeEnginePolicy
import com.opendroid.ai.core.voice.kws.WakeKeywordFactory
import com.opendroid.ai.core.voice.kws.WakeWordEngineState
import com.opendroid.ai.core.voice.kws.WakeWordStatus

/**
 * Wake-word entry point used by `OpenDroidService` — public contract
 * unchanged from the original implementation ([startListening],
 * [stopListening], [destroy]), but the listening itself is delegated to
 * a [KeywordSpotterEngine] (D1):
 *
 * - Default: [SherpaKwsEngine] — sherpa-onnx keyword spotting running
 *   fully on-device (mic → AudioRecord → native inference → callback).
 *   No network is involved in the idle listening path.
 * - [LegacySpeechWakeEngine] — the old SpeechRecognizer loop, used only
 *   when the user explicitly picks it in Settings.
 *
 * If the offline engine was requested but its model is unavailable, the
 * detector publishes an error state and does NOT listen — silently
 * falling back to the network recogniser would fake the feature.
 *
 * Settings arrive via [applySettings] (the service forwards every
 * `LLMConfig` update); a change while listening restarts the engine with
 * the new configuration.
 */
class WakeWordDetector(private val context: Context) {

    private data class SettingsSnapshot(
        val kind: WakeEngineKind,
        val keywordPhrase: String,
        val sensitivity: Float
    )

    private var settings = SettingsSnapshot(
        kind = WakeEngineKind.OFFLINE_SHERPA,
        keywordPhrase = WakeKeywordFactory.DEFAULT_PHRASE,
        sensitivity = 0.5f
    )

    private var engine: KeywordSpotterEngine? = null

    @Volatile private var listening = false
    private var onWakeWordDetectedCallback: (() -> Unit)? = null

    /** Forwards the user's wake-word settings; restarts a live engine on change. */
    fun applySettings(engineId: String, keywordPhrase: String, sensitivity: Float) {
        val updated = SettingsSnapshot(
            kind = WakeEngineKind.fromSettingsId(engineId),
            keywordPhrase = keywordPhrase.trim().ifEmpty { WakeKeywordFactory.DEFAULT_PHRASE },
            sensitivity = sensitivity.coerceIn(0f, 1f)
        )
        if (updated == settings) return
        settings = updated
        if (listening) {
            engine?.destroy()
            engine = null
            startEngine()
        }
    }

    fun startListening(onWakeWordDetected: () -> Unit) {
        onWakeWordDetectedCallback = onWakeWordDetected
        if (listening) return
        listening = true
        startEngine()
    }

    private fun startEngine() {
        val resolved = WakeEnginePolicy.resolve(
            requested = settings.kind,
            offlineModelAvailable = SherpaKwsEngine.isModelBundled(context)
        )
        if (resolved == null) {
            WakeWordStatus.publish(
                WakeWordEngineState.Error(
                    "The offline wake-word model is missing from this build, so wake-word " +
                        "listening is off. Reinstall the full app, or pick the Legacy engine " +
                        "in Settings → Wake Word."
                ),
                settings.kind
            )
            return
        }
        val newEngine = when (resolved) {
            WakeEngineKind.OFFLINE_SHERPA -> SherpaKwsEngine(context)
            WakeEngineKind.LEGACY_SPEECH -> LegacySpeechWakeEngine(context)
        }
        engine = newEngine
        newEngine.configure(settings.keywordPhrase, settings.sensitivity)
        newEngine.start {
            if (listening) onWakeWordDetectedCallback?.invoke()
        }
    }

    fun stopListening() {
        listening = false
        engine?.stop()
    }

    fun destroy() {
        listening = false
        onWakeWordDetectedCallback = null
        engine?.destroy()
        engine = null
    }
}
