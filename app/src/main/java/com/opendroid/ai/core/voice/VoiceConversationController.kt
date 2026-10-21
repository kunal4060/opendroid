package com.opendroid.ai.core.voice

/** Coarse phase of a continuous voice session, mirrored to the chat UI. */
enum class VoiceSessionPhase { LISTENING, THINKING, SPEAKING, AWAITING_APPROVAL }

/** Why a voice session ended — recorded so teardown copy/logging stays honest. */
enum class VoiceSessionEndReason {
    USER_STOP,
    END_PHRASE,
    SILENCE,
    SESSION_CAP,
    TURN_ERROR,
    LISTEN_UNAVAILABLE,
    APPROVAL_UNRESOLVED,
    AUDIO_FOCUS_LOST
}

/**
 * Side effects the service host must perform on behalf of the controller.
 * The controller is pure decision logic: it never touches the mic, TTS or
 * the agent itself.
 */
sealed interface VoiceSessionEffect {
    /** (Re)open the recognizer for the next user turn. */
    object BeginListening : VoiceSessionEffect

    /** Cancel any in-flight recognition immediately. */
    object CancelListening : VoiceSessionEffect

    /** Hand a heard utterance to the agent (same chat session as typed chat). */
    data class SubmitQuery(val text: String) : VoiceSessionEffect

    /** Speak a short session-originated line (prompt / farewell). */
    data class SpeakText(val text: String) : VoiceSessionEffect

    /** Stop speech output now (barge-in / user stop). */
    object StopSpeaking : VoiceSessionEffect

    /**
     * Tear the session down: release the mic, restore idle wake-word
     * listening, drop audio focus, update the UI store. [farewell], when
     * non-null, is spoken *after* teardown as an ordinary one-shot line.
     */
    data class EndSession(
        val reason: VoiceSessionEndReason,
        val farewell: String? = null
    ) : VoiceSessionEffect
}

/**
 * D2 — state machine for continuous hands-free voice conversation.
 *
 * Pure Kotlin (no Android imports) so the whole loop is unit-testable; the
 * host (`OpenDroidService`) owns the engines and feeds it events:
 *
 * ```
 * OFF ──start()──▶ LISTENING ──utterance──▶ THINKING ──agent speech──▶ SPEAKING
 *  ▲                  │  ▲                       │                        │
 *  │                  │  └───── speech finished / turn settled ◀─────────┘
 *  │                  ├─ timeout ladder: silent retry → spoken "still
 *  │                  │   listening" prompt → polite session end
 *  │                  └─ PlanProposed ──▶ AWAITING_APPROVAL ──resolved──▶ THINKING
 *  └── any state: stop / cap / fatal error / focus loss ──▶ EndSession ──▶ OFF
 * ```
 *
 * Approval semantics are NOT reimplemented here: the host routes
 * PlanProposed to the existing spoken-approval listen
 * ([VoiceApprovalParser]) exactly as the one-shot flow does, and merely
 * tells this controller the outcome. Nothing in this class can approve a
 * plan, and a voice session grants no approval bypass — MANUAL approval
 * still requires the same explicit spoken "approve" the parser demands.
 *
 * Barge-in: while SPEAKING, the host watches for the wake phrase (touch
 * stop is always available in the UI) and calls [onBargeIn], which stops
 * the speech and re-opens listening. Full speech-over-speech recognition
 * is not promised — the recognizer is never run *into* the agent during
 * playback; the wake-phrase watcher is the interrupt channel.
 *
 * All entry points are synchronized: engine callbacks arrive on the main
 * thread in practice, but the guard makes the machine safe regardless.
 */
class VoiceConversationController(
    private val config: Config = Config()
) {

    data class Config(
        /** Silent re-listens granted before the spoken "still listening" prompt. */
        val silentRetries: Int = 1,
        val stillListeningPrompt: String = "I'm still listening.",
        val endPhraseFarewell: String = "Okay, ending our voice conversation.",
        val silenceFarewell: String =
            "I'll stop listening for now. Say the wake word or long-press the mic to talk again.",
        val capFarewell: String =
            "We've been talking a while, so I'll close the voice session here.",
        val errorFarewell: String =
            "Sorry, something went wrong. Ending the voice conversation."
    )

    enum class State { OFF, LISTENING, THINKING, SPEAKING, AWAITING_APPROVAL }

    var state: State = State.OFF
        private set

    /** Completed question→answer turns in this session. */
    var turnsCompleted: Int = 0
        private set

    /** Consecutive listen misses (no-match / timeout / watchdog) this cycle. */
    private var listenMisses = 0

    /** The spoken still-listening prompt was already given since the last heard utterance. */
    private var stillListeningPrompted = false

    /** Current SPEAKING is a session prompt, so its completion re-listens instead of counting a turn. */
    private var speakingIsPrompt = false

    val isActive: Boolean get() = state != State.OFF

    val phase: VoiceSessionPhase?
        get() = when (state) {
            State.OFF -> null
            State.LISTENING -> VoiceSessionPhase.LISTENING
            State.THINKING -> VoiceSessionPhase.THINKING
            State.SPEAKING -> VoiceSessionPhase.SPEAKING
            State.AWAITING_APPROVAL -> VoiceSessionPhase.AWAITING_APPROVAL
        }

    @Synchronized
    fun start(): List<VoiceSessionEffect> {
        if (isActive) return emptyList()
        turnsCompleted = 0
        listenMisses = 0
        stillListeningPrompted = false
        speakingIsPrompt = false
        state = State.LISTENING
        return listOf(VoiceSessionEffect.BeginListening)
    }

    /** A final transcript arrived while listening. */
    @Synchronized
    fun onUtterance(text: String): List<VoiceSessionEffect> {
        if (state != State.LISTENING) return emptyList()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return onListenTimeout()
        if (VoiceSessionCommands.isEndPhrase(trimmed)) {
            state = State.OFF
            return listOf(
                VoiceSessionEffect.CancelListening,
                VoiceSessionEffect.EndSession(
                    VoiceSessionEndReason.END_PHRASE,
                    farewell = config.endPhraseFarewell
                )
            )
        }
        listenMisses = 0
        stillListeningPrompted = false
        state = State.THINKING
        return listOf(
            VoiceSessionEffect.CancelListening,
            VoiceSessionEffect.SubmitQuery(trimmed)
        )
    }

    /**
     * No usable speech from the current listen (recognizer no-match /
     * speech-timeout, or the host's listen watchdog fired). Ladder: one
     * silent retry, then one spoken prompt, then the session ends —
     * the mic is never left open forever.
     */
    @Synchronized
    fun onListenTimeout(): List<VoiceSessionEffect> {
        if (state != State.LISTENING) return emptyList()
        listenMisses++
        return when {
            listenMisses <= config.silentRetries ->
                listOf(VoiceSessionEffect.BeginListening)
            !stillListeningPrompted -> {
                stillListeningPrompted = true
                speakingIsPrompt = true
                state = State.SPEAKING
                listOf(VoiceSessionEffect.SpeakText(config.stillListeningPrompt))
            }
            else -> {
                state = State.OFF
                listOf(
                    VoiceSessionEffect.CancelListening,
                    VoiceSessionEffect.EndSession(
                        VoiceSessionEndReason.SILENCE,
                        farewell = config.silenceFarewell
                    )
                )
            }
        }
    }

    /** Recognizer error: fatal (unavailable / permission) ends the session; anything else joins the timeout ladder. */
    @Synchronized
    fun onListenFailure(fatal: Boolean): List<VoiceSessionEffect> {
        if (state != State.LISTENING) return emptyList()
        if (!fatal) return onListenTimeout()
        state = State.OFF
        return listOf(
            VoiceSessionEffect.CancelListening,
            VoiceSessionEffect.EndSession(
                VoiceSessionEndReason.LISTEN_UNAVAILABLE,
                farewell = config.errorFarewell
            )
        )
    }

    /** The agent's spoken reply (turn-final speech) has begun. Host-observed via AgentState. */
    @Synchronized
    fun onSpeechStarted(): List<VoiceSessionEffect> {
        if (state == State.THINKING) {
            speakingIsPrompt = false
            state = State.SPEAKING
        }
        return emptyList()
    }

    /** Speech output finished (TTS completion while the session owns the turn). */
    @Synchronized
    fun onSpeechFinished(): List<VoiceSessionEffect> {
        if (state != State.SPEAKING) return emptyList()
        val wasPrompt = speakingIsPrompt
        speakingIsPrompt = false
        if (!wasPrompt) {
            turnsCompleted++
            listenMisses = 0
            stillListeningPrompted = false
        }
        // After the still-listening PROMPT, the ladder deliberately does
        // NOT reset: the next miss ends the session (see onListenTimeout),
        // otherwise prompt → miss → prompt would loop forever.
        state = State.LISTENING
        return listOf(VoiceSessionEffect.BeginListening)
    }

    /**
     * The agent turn settled back to Idle without turn-final speech (e.g.
     * a rejected plan, or a question the user will answer by speaking —
     * the reply simply becomes the next turn's utterance).
     */
    @Synchronized
    fun onAgentTurnSettled(): List<VoiceSessionEffect> {
        if (state != State.THINKING && state != State.SPEAKING) return emptyList()
        speakingIsPrompt = false
        turnsCompleted++
        listenMisses = 0
        stillListeningPrompted = false
        state = State.LISTENING
        return listOf(VoiceSessionEffect.BeginListening)
    }

    /** A plan was proposed mid-conversation; the host speaks the existing approval prompt. */
    @Synchronized
    fun onPlanProposed(): List<VoiceSessionEffect> {
        if (state == State.THINKING) state = State.AWAITING_APPROVAL
        return emptyList()
    }

    /** The spoken approval listen produced a decision (approve OR reject) via VoiceApprovalParser. */
    @Synchronized
    fun onApprovalResolved(): List<VoiceSessionEffect> {
        if (state == State.AWAITING_APPROVAL) state = State.THINKING
        return emptyList()
    }

    /**
     * The approval listen heard nothing usable (NONE / recognizer error).
     * The plan stays proposed in the chat UI for a touch decision; the
     * voice session ends rather than guessing — speech must never widen
     * the approval allowlist, and silence is not consent.
     */
    @Synchronized
    fun onApprovalUnresolved(): List<VoiceSessionEffect> {
        if (state != State.AWAITING_APPROVAL) return emptyList()
        state = State.OFF
        return listOf(
            VoiceSessionEffect.EndSession(VoiceSessionEndReason.APPROVAL_UNRESOLVED)
        )
    }

    /** The agent turn failed (provider/STT error surfaced as AgentState.Error). Speak once, end — no retry loop. */
    @Synchronized
    fun onTurnFailed(): List<VoiceSessionEffect> {
        if (state != State.THINKING && state != State.SPEAKING && state != State.AWAITING_APPROVAL) {
            return emptyList()
        }
        speakingIsPrompt = false
        state = State.OFF
        return listOf(
            VoiceSessionEffect.CancelListening,
            VoiceSessionEffect.StopSpeaking,
            VoiceSessionEffect.EndSession(
                VoiceSessionEndReason.TURN_ERROR,
                farewell = config.errorFarewell
            )
        )
    }

    /** Barge-in: the user interrupted speech output (wake phrase heard, or the UI Interrupt button). */
    @Synchronized
    fun onBargeIn(): List<VoiceSessionEffect> {
        if (state != State.SPEAKING) return emptyList()
        speakingIsPrompt = false
        listenMisses = 0
        stillListeningPrompted = false
        state = State.LISTENING
        return listOf(
            VoiceSessionEffect.StopSpeaking,
            VoiceSessionEffect.BeginListening
        )
    }

    /** Touch stop (UI End button) — immediate, from any active state. */
    @Synchronized
    fun onStopRequested(): List<VoiceSessionEffect> {
        if (!isActive) return emptyList()
        state = State.OFF
        return listOf(
            VoiceSessionEffect.CancelListening,
            VoiceSessionEffect.StopSpeaking,
            VoiceSessionEffect.EndSession(VoiceSessionEndReason.USER_STOP)
        )
    }

    /** Hard session-length cap reached (host timer). */
    @Synchronized
    fun onSessionCapReached(): List<VoiceSessionEffect> {
        if (!isActive) return emptyList()
        state = State.OFF
        return listOf(
            VoiceSessionEffect.CancelListening,
            VoiceSessionEffect.StopSpeaking,
            VoiceSessionEffect.EndSession(
                VoiceSessionEndReason.SESSION_CAP,
                farewell = config.capFarewell
            )
        )
    }

    /** Audio focus was taken by another app — end cleanly instead of talking over it. No farewell: we no longer own the audio. */
    @Synchronized
    fun onAudioFocusLost(): List<VoiceSessionEffect> {
        if (!isActive) return emptyList()
        state = State.OFF
        return listOf(
            VoiceSessionEffect.CancelListening,
            VoiceSessionEffect.StopSpeaking,
            VoiceSessionEffect.EndSession(VoiceSessionEndReason.AUDIO_FOCUS_LOST)
        )
    }
}
