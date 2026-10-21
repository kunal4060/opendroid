package com.opendroid.ai.core.voice.kws

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live status of the wake-word pipeline, published by [WakeWordDetector]
 * and its engines and read by the Settings wake-word section (engine
 * status row). Deliberately a plain process-wide holder: the detector is
 * constructed by `OpenDroidService`, not by DI, and Settings only needs
 * to observe — there is exactly one wake-word pipeline per process.
 */
data class WakeWordStatusInfo(
    val state: WakeWordEngineState = WakeWordEngineState.Idle,
    val engine: WakeEngineKind? = null,
    /** Optional human-readable note (e.g. a keyword fallback warning). */
    val detail: String? = null
)

object WakeWordStatus {
    private val _info = MutableStateFlow(WakeWordStatusInfo())
    val info: StateFlow<WakeWordStatusInfo> = _info.asStateFlow()

    fun publish(state: WakeWordEngineState, engine: WakeEngineKind?, detail: String? = null) {
        _info.value = WakeWordStatusInfo(state = state, engine = engine, detail = detail)
    }

    fun publishDetail(detail: String?) {
        _info.value = _info.value.copy(detail = detail)
    }
}

/**
 * One wake-word listening engine behind [WakeWordDetector].
 *
 * Contract (this is what keeps mic contention safe): [start] begins
 * capturing the microphone; [stop] must fully release it before
 * returning, so the service can hand the mic to command STT/TTS flows.
 * Detection callbacks are delivered on the main thread, matching the
 * behaviour of the original SpeechRecognizer-based detector.
 */
interface KeywordSpotterEngine {
    val kind: WakeEngineKind

    /** Applies the user's phrase/sensitivity before (or between) starts. */
    fun configure(keywordPhrase: String, sensitivity: Float)

    fun start(onDetected: (keyword: String) -> Unit)

    fun stop()

    fun destroy()
}
