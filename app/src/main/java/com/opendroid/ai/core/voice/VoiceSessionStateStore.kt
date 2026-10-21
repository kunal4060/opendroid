package com.opendroid.ai.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI mirror of the continuous voice session (D2). The session itself is
 * driven by [VoiceConversationController] inside `OpenDroidService`
 * (which owns the mic/TTS engines); this store is the one-way bridge the
 * chat UI reads to render the session bar (phase label, Interrupt/End).
 * Writes happen only from the service, on the main thread.
 */
@Singleton
class VoiceSessionStateStore @Inject constructor() {

    data class UiState(
        val active: Boolean = false,
        val phase: VoiceSessionPhase? = null,
        val turnsCompleted: Int = 0
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun update(active: Boolean, phase: VoiceSessionPhase?, turnsCompleted: Int) {
        _uiState.value = UiState(active, phase, turnsCompleted)
    }

    fun clear() {
        _uiState.value = UiState()
    }
}
