package com.opendroid.ai.core.assistant

import android.content.Context
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.voice.SpeechRecognitionEngine
import com.opendroid.ai.core.voice.VoiceApprovalIntent
import com.opendroid.ai.core.voice.VoiceApprovalParser
import com.opendroid.ai.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * C3 — the brain of the assistant session window.
 *
 * Bridges the platform voice session to the REAL agent: recognised speech
 * is dispatched through [AgentLoop.processQuery] pinned to the current
 * chat session — the identical agent path (planner, approval modes,
 * history) the in-app chat uses, mirroring how OpenDroidService already
 * drives the loop headlessly with a service context. Nothing here plans
 * or executes anything itself.
 *
 * While a plan awaits approval, an utterance is first read as a voice
 * approval/rejection (the existing [VoiceApprovalParser], same as the
 * wake-word flow); anything else becomes a new command. On-screen
 * approve/reject buttons call the same two functions.
 */
class AssistantSessionController(
    private val context: Context,
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val scope: CoroutineScope
) {

    /** One-line status for the session UI ("Listening…", "Working on it…", errors). */
    private val _statusText = MutableStateFlow("Listening…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    /** Live dictation while the user is speaking. */
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    /** Last full utterance heard, shown so the user can confirm what was sent to the agent. */
    private val _lastHeardCommand = MutableStateFlow<String?>(null)
    val lastHeardCommand: StateFlow<String?> = _lastHeardCommand.asStateFlow()

    /** The shared agent state — plan proposals, execution, errors render straight from this. */
    val agentState: StateFlow<AgentState> = agentLoop.agentState

    /**
     * Context from the assist invocation: which app was in front and the
     * content URI it offered, when the system provided them. Recorded so
     * the session UI can say "from <app>"; richer use (acting on the
     * foreground screen) builds on this foundation.
     */
    private val _assistSourcePackage = MutableStateFlow<String?>(null)
    val assistSourcePackage: StateFlow<String?> = _assistSourcePackage.asStateFlow()

    private var speechEngine: SpeechRecognitionEngine? = null

    fun onAssistContext(sourcePackage: String?) {
        _assistSourcePackage.value = sourcePackage
    }

    fun startListening() {
        if (speechEngine == null) {
            speechEngine = SpeechRecognitionEngine(context)
        }
        _statusText.value = "Listening…"
        speechEngine?.startListening(
            onResult = { text -> handleUtterance(text) },
            onPartialResult = { partial -> _partialTranscript.value = partial },
            onError = { error ->
                _partialTranscript.value = ""
                _statusText.value = error
            }
        )
    }

    fun stopListening() {
        speechEngine?.stopListening()
    }

    /**
     * A finished utterance. Approval-shaped speech wins while a plan is
     * proposed; everything else is a command for the agent.
     */
    fun handleUtterance(text: String) {
        val trimmed = text.trim()
        _partialTranscript.value = ""
        if (trimmed.isEmpty()) return

        if (agentLoop.agentState.value is AgentState.PlanProposed) {
            when (VoiceApprovalParser.parse(trimmed)) {
                VoiceApprovalIntent.APPROVE -> {
                    approvePlan()
                    return
                }
                VoiceApprovalIntent.REJECT -> {
                    rejectPlan()
                    return
                }
                VoiceApprovalIntent.NONE -> Unit // fall through: treat as a new command
            }
        }
        dispatchCommand(trimmed)
    }

    /** Typed fallback from the session UI — same dispatch path as speech. */
    fun submitTypedCommand(text: String) {
        handleUtterance(text)
    }

    /**
     * Sends [command] through the agent loop, pinned to the chat session
     * that is current right now (created on first use), so the exchange
     * lands in the visible chat history exactly like an in-app message.
     */
    fun dispatchCommand(command: String) {
        _lastHeardCommand.value = command
        _statusText.value = "Working on it…"
        scope.launch {
            val sessionId = conversationRepository.ensureCurrentSessionId()
            agentLoop.processQuery(command, context, sessionId)
        }
    }

    fun approvePlan() {
        _statusText.value = "Approved — running the plan…"
        agentLoop.approveProposedPlan(context)
    }

    fun rejectPlan() {
        _statusText.value = "Plan rejected."
        agentLoop.rejectProposedPlan()
    }

    fun close() {
        speechEngine?.destroy()
        speechEngine = null
    }
}
