package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.PreparedAttachment
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.LatencyBudgetStatus
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.prompts.PlanningPrompts
import com.opendroid.ai.core.memory.MemoryManager
import com.opendroid.ai.core.memory.AuditLog
import com.opendroid.ai.core.memory.ExecutionHistoryPrivacy
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.models.effectiveGrantedActions
import com.opendroid.ai.data.models.resolvedAutoMode
import com.opendroid.ai.data.models.PersonaPromptComposer
import com.opendroid.ai.data.models.approvalSettings
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.PersonaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.opendroid.ai.core.util.NetworkErrorFormatter
import com.opendroid.ai.core.llm.error.LLMException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_NEEDS_INPUT_PROMPTS = 5
private const val MAX_INCOMPLETE_MESSAGE_IDS = 100
private val CONTACT_NUMBER_PROMPT_ACTIONS = setOf("MAKE_CALL", "SEND_SMS", "SEND_WHATSAPP", "SEND_TELEGRAM")

internal fun paramKeyForNeedsInput(needsInput: ActionResult.NeedsInput, actionName: String): String {
    needsInput.metadata["param"]?.let { return it }

    val asksForNumber = needsInput.question.contains("number", ignoreCase = true) ||
            needsInput.question.contains("phone", ignoreCase = true)
    return if (actionName.uppercase() in CONTACT_NUMBER_PROMPT_ACTIONS && asksForNumber) {
        "contact"
    } else {
        "value"
    }
}

sealed interface AgentState {
    object Idle : AgentState
    object Listening : AgentState
    object Thinking : AgentState
    data class PlanProposed(val plan: Plan) : AgentState
    data class ExecutingPlan(val currentStepDesc: String) : AgentState
    data class Speaking(val text: String) : AgentState
    data class Error(val message: String) : AgentState
}

@Singleton
class AgentLoop @Inject constructor(
    private val intentClassifier: IntentClassifier,
    private val llmProviderFactory: LLMProviderFactory,
    private val planManager: PlanManager,
    private val actionDispatcher: ActionDispatcher,
    private val actionSequenceExecutor: ActionSequenceExecutor,
    private val memoryManager: MemoryManager,
    private val conversationRepository: ConversationRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: com.opendroid.ai.data.repository.SettingsRepository,
    private val reEvalEngine: dagger.Lazy<ReEvaluationEngine>
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val queryMutex = Mutex()

    // The currently in-flight processQuery/approveProposedPlan job, if any. Tracked so a
    // fresh query (or an explicit cancel) can stop whatever the agent is doing right now.
    // NOT reassigned when processQuery is merely delivering a reply to a pending
    // awaitUserResponse() prompt - that reply belongs to the job already running.
    @Volatile private var currentJob: Job? = null

    // The session id pinned for whichever task currentJob is currently running end-to-end.
    // Resolved ONCE, right when a genuinely new task starts (processQuery for a fresh query,
    // or approveProposedPlan), then threaded as a plain parameter through that task's whole
    // call chain, so every message the task writes - plan execution, contact-picker prompts,
    // the final summary - uses that same id and never re-resolves "current session" mid-task.
    // If the user switches (or starts) a chat while the task is still running, its writes
    // keep landing in the chat it was pinned to, not wherever the user has navigated to.
    //
    // That parameter-threading is what makes pinning safe against races: this field is only
    // ever read back in one place - relaying a reply to THIS SAME task's awaitUserResponse()
    // prompt (see processQuery) - never by the task's own ongoing execution, so a later task
    // overwriting this field can't redirect an earlier task's still-unwinding writes.
    @Volatile private var activeTaskSessionId: String = ""

    // The last successfully executed action/params, scoped PER SESSION so a contextual
    // follow-up (AliasResolver.resolveContextual - "turn it off", "stop it", ...) in one
    // chat can only ever resolve against that same chat's own history, never leftover
    // state from an unrelated conversation. Previously this was a pair of process-global
    // @Volatile fields, which meant a device toggle executed in chat A would silently be
    // replayed by a generic contextual phrase typed in chat B. Bundled into one data class
    // per session (rather than two parallel maps) so an action and its params can never be
    // read back mismatched.
    private data class LastExecutedAction(val action: String, val params: Map<String, String>)
    private val lastExecutedActionsBySession = java.util.concurrent.ConcurrentHashMap<String, LastExecutedAction>()

    // Session id the currently PlanProposed plan (if any) was proposed in, or null when
    // agentState isn't PlanProposed. planManager.currentPlan and agentState are both
    // single global values with no session affiliation of their own, so this is what lets
    // a genuinely new task starting in a DIFFERENT session (see resolveStaleProposedPlan)
    // recognize "there's a proposal out there that isn't mine" before it does anything
    // that would otherwise silently clobber it.
    @Volatile private var proposedPlanSessionId: String? = null

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _chatError = MutableStateFlow<ChatErrorUiState?>(null)
    val chatError: StateFlow<ChatErrorUiState?> = _chatError.asStateFlow()

    // Ids of partially streamed agent replies, so re-sent context can label them as
    // incomplete. Bounded: an insertion-ordered set capped at
    // MAX_INCOMPLETE_MESSAGE_IDS, dropping the oldest entry once full - only the ids
    // still inside the last-10-messages context window matter, so evicted entries can
    // never affect a prompt again.
    private val incompleteMessageIds: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                    size > MAX_INCOMPLETE_MESSAGE_IDS
            }
        )
    )

    // A single pending awaitUserResponse() prompt, identified by [requestId] - not just a
    // session id, so that even a second prompt opened for the SAME session can never be
    // resolved by a reply aimed at an earlier, already-abandoned one. [deferred] is
    // completed exactly once, by the reply path in processQuery, and ONLY if it is still
    // the value installed here (re-validated at completion time, not just when the reply
    // was scheduled) - there is deliberately no buffering flow a stale reply could sit in
    // and later be picked up by some unrelated future prompt. See processQuery.
    private data class PendingUserInput(
        val sessionId: String,
        val requestId: String,
        val deferred: CompletableDeferred<String>
    )

    @Volatile private var pendingUserInput: PendingUserInput? = null

    // Audit-2 #10: requestId of the prompt currently parked in
    // awaitUserResponse(), exposed so the chat UI can tell the ONE live
    // prompt (e.g. a contact picker) apart from historical ones — only the
    // card tagged with this id may accept taps; every older card renders
    // inert, so a stale tap can never be delivered as a bare "1"/"2" answer
    // to whatever unrelated prompt happens to be pending later.
    private val _pendingPromptRequestId = MutableStateFlow<String?>(null)
    val pendingPromptRequestId: StateFlow<String?> = _pendingPromptRequestId.asStateFlow()

    // Session id of whichever task is currently parked inside awaitUserResponse(), or null
    // when nothing is waiting. Session-affiliated (not a bare boolean) so processQuery can
    // tell a genuine reply to THIS prompt (arriving from the same session) apart from an
    // unrelated new message that happens to arrive in some other chat while this prompt is
    // still open - see processQuery.
    @Volatile private var waitingSessionId: String? = null

    val isWaitingForUserInput: Boolean
        get() = waitingSessionId != null

    /**
     * Suspends until the user answers a prompt this task just posted. Defaults to
     * whichever session the currently running task is pinned to (activeTaskSessionId) -
     * correct for every existing internal caller, since a call to this function only ever
     * happens from within that task's own execution. Pass a session explicitly only if
     * that assumption doesn't hold.
     */
    suspend fun awaitUserResponse(
        sessionId: String = activeTaskSessionId,
        requestId: String = UUID.randomUUID().toString()
    ): String {
        val request = PendingUserInput(sessionId, requestId, CompletableDeferred())
        waitingSessionId = sessionId
        pendingUserInput = request
        _pendingPromptRequestId.value = requestId
        try {
            return request.deferred.await()
        } finally {
            // Only clear state that is still ours - a newer prompt for the same (or a
            // different) session, or an explicit abandon/cancel, may already have
            // replaced or cleared it, and clobbering that would be wrong.
            if (pendingUserInput === request) {
                pendingUserInput = null
            }
            if (_pendingPromptRequestId.value == requestId) {
                _pendingPromptRequestId.value = null
            }
            if (waitingSessionId == sessionId) {
                waitingSessionId = null
            }
        }
    }

    fun setAgentState(state: AgentState) {
        _agentState.value = state
    }

    // Speak callback to be implemented by TTS service
    var onSpeakCallback: ((String) -> Unit)? = null

    /**
     * @param explicitSessionId The session this message actually belongs to (whatever chat
     * the caller was looking at when the user hit send), if the caller can determine it.
     * Used to decide whether this message answers a pending awaitUserResponse() prompt -
     * see below - and, for a genuinely new query, which session it's stored in. Callers
     * that cannot determine this (e.g. voice input via OpenDroidService, which has no
     * notion of "which chat" a spoken query belongs to) pass null, which preserves the
     * pre-multi-session behavior: a null session is always assumed to answer whatever
     * prompt is currently pending, and otherwise falls back to resolving "current".
     *
     * @param configOverride Per-chat LLM config snapshot (feature A2), resolved by
     * the caller from the session's persisted model override. When non-null, this
     * task's answering provider (simple replies and plan generation) is built from
     * the snapshot instead of the global config. Null (every non-chat caller)
     * preserves the global-config behaviour exactly.
     */
    fun processQuery(
        query: String,
        context: Context,
        explicitSessionId: String? = null,
        configOverride: LLMConfig? = null,
        attachment: PreparedAttachment? = null,
        attachedImageBase64: String? = null
    ) {
        // processQuery is also the delivery path for a reply to a pending
        // awaitUserResponse() prompt (see below). That's ONLY true when this message comes
        // from the SAME session the waiting task is pinned to (waitingSessionId) - a
        // message from a different session is a genuinely new query, never an answer to
        // some other chat's prompt, no matter how it compares to the global "is anything
        // waiting" state.
        val waitingSession = waitingSessionId
        // Snapshot of the exact prompt that is pending right now, at the moment this
        // reply was scheduled. The reply is only ever allowed to resolve THIS SAME
        // instance - re-checked below, inside the launched coroutine, right before
        // completing it - never whatever happens to be pending by the time that
        // coroutine actually runs.
        val pendingAtScheduleTime = pendingUserInput
        val isAnsweringPendingQuestion = waitingSession != null &&
                (explicitSessionId == null || explicitSessionId == waitingSession)

        if (waitingSession != null && !isAnsweringPendingQuestion) {
            // A brand-new message arrived in a different session while another task is
            // still parked on a prompt in `waitingSession`. Nobody there is ever going to
            // answer that prompt now - kill it and leave a short, honest message in ITS OWN
            // session saying it stopped waiting, rather than either routing this message
            // into the wrong chat or leaving that chat hanging silently forever.
            abandonWaitingTask(waitingSession)
        } else if (!isAnsweringPendingQuestion) {
            currentJob?.cancel()
        }

        val job = scope.launch {
            try {
                // Pin the session THIS task writes to, resolved once, right here, before any
                // message is written. A genuinely new query pins whatever session the caller
                // says this message belongs to (falling back to whatever is current if the
                // caller couldn't say); a reply to a pending prompt reuses the session the
                // task it's replying to already pinned via activeTaskSessionId - it must NOT
                // re-resolve "current", since the user may have switched (or created) chats
                // while that task was still waiting on them.
                val sessionId = if (isAnsweringPendingQuestion) {
                    activeTaskSessionId
                } else {
                    (explicitSessionId ?: conversationRepository.ensureCurrentSessionId())
                        .also { activeTaskSessionId = it }
                }

                if (!isAnsweringPendingQuestion) {
                    // This is a genuinely new task. If some OTHER session still has an
                    // unresolved plan proposal sitting in agentState/planManager, resolve
                    // it explicitly right now, before anything below can silently
                    // overwrite it out from under that chat - see resolveStaleProposedPlan.
                    resolveStaleProposedPlan(sessionId)
                }

                // Only capture the screen when the query actually asks about it.
                // Attaching a screenshot to every request would silently send
                // whatever is on screen (messages, banking apps, ...) to the LLM.
                val screenshotBase64 = if (needsScreenContext(query)) {
                    com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()?.takeScreenshotAndEncode()
                } else {
                    null
                }

                // Save user message. An explicit attachment wins over the
                // on-demand screenshot — it is what the user meant to send;
                // the two never combine into one message. A composer
                // attachment (B1) supplies its payload with mime/name riding
                // along so providers and the history bubble know what the
                // payload is; a shared image staged by C2 supplies a bare
                // payload (no mime/name known at this layer).
                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = query,
                    sender = ChatMessage.Sender.USER,
                    modelBadge = null,
                    imageBase64 = attachment?.imageBase64 ?: attachedImageBase64 ?: screenshotBase64,
                    attachmentMimeType = attachment?.attachmentMimeType,
                    attachmentName = attachment?.attachmentName
                )
                memoryManager.storeMessage(userMsg, sessionId)
                // What lands in Room can differ from the in-memory message:
                // attachment payloads persist only up to the storage cap
                // (large PDFs are send-once), and screenshot payloads stay
                // send-only, exactly as they behaved before the v11 columns
                // existed (the old mapper dropped them).
                val persistedMsg = when {
                    attachment != null ->
                        userMsg.copy(imageBase64 = attachment.persistedImageBase64)
                    // A shared image (C2) is user-sent content staged in
                    // app-private storage, not an ambient screenshot: it
                    // persists with the message (the v11 column B1 added —
                    // this is the B1↔C2 hand-off point).
                    attachedImageBase64 != null -> userMsg
                    else -> userMsg.copy(imageBase64 = null)
                }
                conversationRepository.insertMessage(sessionId, persistedMsg)

                if (isAnsweringPendingQuestion) {
                    // Re-validate at delivery time, not just at schedule time: only
                    // complete the EXACT prompt this reply was aimed at (matched by
                    // requestId, not merely by session). If it's gone - already answered,
                    // abandoned, or superseded by a newer prompt in the meantime - there
                    // is nothing to buffer it for; just drop it.
                    val pending = pendingUserInput
                    if (pending != null && pending.requestId == pendingAtScheduleTime?.requestId) {
                        pending.deferred.complete(query)
                    }
                    return@launch
                }

                // Serialize query handling so contextual follow-up state cannot race.
                queryMutex.withLock {
                    processQueryLocked(userMsg, query, context, sessionId, configOverride)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agentState.value = AgentState.Error(e.localizedMessage ?: "Unknown processing error")
            }
        }

        if (!isAnsweringPendingQuestion) {
            currentJob = job
        }
    }

    /**
     * Re-executes a previously failed request after the user taps Retry on its error
     * card. Mirrors processQuery's new-task path, but reuses the user message already
     * persisted for [requestId] in [sessionId] instead of inserting a duplicate bubble -
     * and always runs in the error's own session, never wherever the user is looking.
     * Falls back to the session's last user message if [requestId] doesn't resolve to
     * one (e.g. a plan re-evaluation failure, whose requestId is a plan id).
     */
    fun retryRequest(
        requestId: String,
        sessionId: String,
        context: Context,
        configOverride: LLMConfig? = null
    ) {
        val waitingSession = waitingSessionId
        if (waitingSession != null && waitingSession != sessionId) {
            abandonWaitingTask(waitingSession)
        } else if (currentTaskSessionId() == null || currentTaskSessionId() == sessionId) {
            // Audit-2 #17: never cancel a task pinned to a DIFFERENT
            // session — AgentLoop is a singleton with one currentJob, and an
            // unscoped cancel here would silently kill another chat's
            // running task just because this session retried/regenerated.
            currentJob?.cancel()
        }

        val job = scope.launch {
            try {
                activeTaskSessionId = sessionId
                resolveStaleProposedPlan(sessionId)

                val messages = conversationRepository.getMessages(sessionId).first()
                val userMsg = messages.lastOrNull {
                    it.id == requestId && it.sender == ChatMessage.Sender.USER
                } ?: messages.lastOrNull { it.sender == ChatMessage.Sender.USER } ?: return@launch

                queryMutex.withLock {
                    processQueryLocked(userMsg, userMsg.text, context, sessionId, configOverride)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agentState.value = AgentState.Error(e.localizedMessage ?: "Unknown processing error")
            }
        }
        currentJob = job
    }

    private suspend fun processQueryLocked(
        userMsg: ChatMessage,
        query: String,
        context: Context,
        sessionId: String,
        configOverride: LLMConfig? = null
    ) {
                // Each new task starts with a clean slate: a stale error card from an
                // earlier request must not outlive the request it described.
                _chatError.value = null
                _agentState.value = AgentState.Thinking

                // 0. Check if this is a complex, multi-step query
                //    If so, skip ALL shortcuts and let the LLM planner handle it properly
                val complexity = intentClassifier.classifyComplexity(query)
                val isMultiStep = complexity != QueryComplexity.SIMPLE

                // 1. Alias resolution — bypass LLM for simple, single-action commands ONLY
                if (!isMultiStep) {
                    val lastExecuted = lastExecutedActionsBySession[sessionId]
                    val contextual = AliasResolver.resolveContextual(query, lastExecuted?.action, lastExecuted?.params)
                    if (contextual != null) {
                        executeAliasDirect(contextual, query, context, sessionId)
                        return
                    }

                    val alias = AliasResolver.resolve(query)
                    if (alias != null) {
                        executeAliasDirect(alias, query, context, sessionId)
                        return
                    }

                    // 1b. Alarm shortcut — bypass LLM for simple alarm requests ONLY
                    if (AliasResolver.isAlarmRequest(query)) {
                        val timeStr = AliasResolver.extractAlarmTime(query)
                        if (timeStr != null) {
                            val alarmHint = AliasResolver.ActionHint(
                                "SET_ALARM",
                                mapOf("time" to timeStr, "label" to "Alarm")
                            )
                            executeAliasDirect(alarmHint, query, context, sessionId)
                            return
                        }
                    }

                    // 1c. Timer shortcut — bypass LLM for simple timer requests ONLY
                    if (AliasResolver.isTimerRequest(query)) {
                        val durationSecs = AliasResolver.extractTimerDuration(query)
                        if (durationSecs != null) {
                            val timerHint = AliasResolver.ActionHint(
                                "SET_TIMER",
                                mapOf("duration" to durationSecs.toString(), "label" to "Timer")
                            )
                            executeAliasDirect(timerHint, query, context, sessionId)
                            return
                        }
                    }

                    // 1d. Read & Remember screen shortcut — bypass LLM for screen reading/saving
                    if (AliasResolver.isReadAndRememberRequest(query)) {
                        val topic = AliasResolver.extractTopicForReadAndRemember(query)
                        val readAndRememberHint = AliasResolver.ActionHint(
                            "READ_AND_REMEMBER_SCREEN",
                            mapOf("topic" to topic, "save_as" to "note")
                        )
                        executeAliasDirect(readAndRememberHint, query, context, sessionId)
                        return
                    }

                    // 1e. Recall memory shortcut — bypass LLM for querying saved notes/memory
                    if (AliasResolver.isRecallMemoryRequest(query)) {
                        val recallQuery = AliasResolver.extractRecallQuery(query)
                        val recallHint = AliasResolver.ActionHint(
                            "RECALL_MEMORY",
                            mapOf("query" to recallQuery)
                        )
                        executeAliasDirect(recallHint, query, context, sessionId)
                        return
                    }
                }

                // 1f. Persona resolution (B6) — the persona bound to THIS
                // session, resolved once per task and threaded into the two
                // LLM paths below. The alias/alarm/timer/screen shortcuts
                // above bypass the LLM entirely, so a persona correctly has
                // no effect on them. A chat with no binding (or whose persona
                // was deleted) resolves to null and behaves exactly as before.
                val personaPrompt = personaRepository.personaForSession(sessionId)
                    ?.prompt?.takeIf { it.isNotBlank() }

                // 2. Intent Classification
                val requiresAction = intentClassifier.requiresAction(query)
                if (requiresAction) {
                    generatePlan(userMsg, context, sessionId, configOverride, personaPrompt)
                } else {
                    executeSimpleQuery(userMsg, sessionId, configOverride, personaPrompt)
                }
    }

    /**
     * Execute an alias-resolved command directly, bypassing the LLM.
     * Builds a single-step Plan and runs it through the normal plan execution pipeline.
     */
    private suspend fun executeAliasDirect(
        alias: AliasResolver.ActionHint,
        originalQuery: String,
        context: Context,
        sessionId: String
    ) {
        try {
            val speechText = humanizePreSpeech(alias.action)
            onSpeakCallback?.invoke(speechText)

            // Save agent response
            val replyMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = speechText,
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "alias"
            )
            conversationRepository.insertMessage(sessionId, replyMsg)
            memoryManager.storeMessage(replyMsg, sessionId)

            // Build a single-step plan from the alias
            val plan = buildSingleStepPlan(originalQuery, alias.action, alias.baseParams)

            planManager.startNewPlan(plan, context, PlanStatus.PROPOSED)
            val approval = settingsRepository.llmConfig.first().approvalSettings()
            if (AutoApprovalPolicy.shouldAutoApprove(approval.mode, approval.grantedActions, plan)) {
                executePlanLoop(plan, context, sessionId, autoApproved = true)
            } else {
                proposePlan(plan, sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _agentState.value = AgentState.Error("Alias execution failed: ${e.localizedMessage}")
        }
    }

    fun dismissChatError() {
        _chatError.value = null
    }

    private fun publishChatError(error: ChatErrorUiState) {
        _chatError.value = error
        _agentState.value = AgentState.Error(error.title())
    }

    private suspend fun executeSimpleQuery(
        userMsg: ChatMessage,
        sessionId: String,
        configOverride: LLMConfig? = null,
        personaPrompt: String? = null
    ) {
        val runId = UUID.randomUUID().toString()
        val requestId = userMsg.id
        try {
            val provider = llmProviderFactory.getActiveProvider(configOverride)
            val relevantContext = memoryManager.getRelevantContext(userMsg.text)
            val autoModeLabel = settingsRepository.llmConfig.first().approvalSettings().mode.name

            val systemPrompt = """
                You are OpenDroid, a friendly and helpful Android AI assistant.
                Talk like a real person — warm, casual, and natural. Avoid sounding robotic.
                Keep your answers short and to the point, but feel free to be friendly.
                
                You can control this Android device: open apps, set alarms, toggle WiFi/Bluetooth/flashlight, send messages, make calls, and more. If someone asks you to do something, just do it or let them know you can help.
                
                Never dump raw error messages or technical details. If something goes wrong, say it simply and suggest what to do next.

                Plan auto-approval mode is currently: $autoModeLabel (OFF = every plan needs manual approval, AUTO = allowlisted plans run automatically, YOLO = plans run automatically except destructive actions, which still need confirmation). You cannot change this mode; the user changes it in Settings or via the chat mode chip.
                
                Context about user and device state:
                $relevantContext
            """.trimIndent()

            // B6: a bound persona's prompt leads the base system prompt
            // (PersonaPromptComposer); null leaves the prompt untouched.
            val effectiveSystemPrompt = PersonaPromptComposer.compose(personaPrompt, systemPrompt)

            val lastMsgs = conversationRepository.getLastMessages(sessionId, 10).map { msg ->
                val withImage = if (msg.id == userMsg.id) {
                    // The in-memory message carries the full send payload
                    // (history may hold a send-once attachment without it).
                    msg.copy(
                        imageBase64 = userMsg.imageBase64,
                        attachmentMimeType = userMsg.attachmentMimeType,
                        attachmentName = userMsg.attachmentName
                    )
                } else {
                    msg
                }
                if (incompleteMessageIds.contains(withImage.id) &&
                    withImage.sender == ChatMessage.Sender.AGENT
                ) {
                    withImage.copy(text = "[incomplete assistant reply]\n${withImage.text}")
                } else {
                    withImage
                }
            }

            val replyId = UUID.randomUUID().toString()
            var currentReplyText = ""
            var inserted = false
            val replyMsg = ChatMessage(
                id = replyId,
                text = currentReplyText,
                sender = ChatMessage.Sender.AGENT,
                modelBadge = provider.name
            )

            try {
                provider.streamComplete(
                    LLMRequest(
                        systemPrompt = effectiveSystemPrompt,
                        messages = lastMsgs,
                        temperature = 0.5f,
                        maxTokens = 500,
                        responseFormat = ResponseFormat.TEXT
                    )
                ).collect { chunk ->
                    if (chunk.isEmpty()) return@collect
                    currentReplyText += chunk
                    conversationRepository.insertMessage(sessionId, replyMsg.copy(text = currentReplyText))
                    inserted = true
                }
            } catch (streamError: CancellationException) {
                if (inserted && currentReplyText.isNotBlank()) {
                    // This coroutine is already cancelled; without NonCancellable the
                    // suspend insert would abort immediately and the "Stopped" partial
                    // would never persist.
                    withContext(NonCancellable) {
                        conversationRepository.insertMessage(
                            sessionId,
                            replyMsg.copy(text = currentReplyText, modelBadge = "Stopped")
                        )
                    }
                }
                throw streamError
            } catch (streamError: LLMException) {
                val partialId = if (inserted && currentReplyText.isNotBlank()) {
                    incompleteMessageIds.add(replyId)
                    conversationRepository.insertMessage(sessionId, replyMsg.copy(text = currentReplyText))
                    replyId
                } else {
                    null
                }
                publishChatError(
                    ChatErrorUiState.fromException(
                        sessionId = sessionId,
                        requestId = requestId,
                        runId = runId,
                        failure = streamError,
                        partialMessageId = partialId
                    )
                )
                return
            }

            if (!inserted || currentReplyText.isBlank()) {
                publishChatError(
                    ChatErrorUiState.fromException(
                        sessionId = sessionId,
                        requestId = requestId,
                        runId = runId,
                        failure = com.opendroid.ai.core.llm.error.LLMErrorMapper.malformed(
                            provider.name,
                            ""
                        )
                    )
                )
                return
            }

            val finalReplyMsg = replyMsg.copy(text = currentReplyText)
            conversationRepository.insertMessage(sessionId, finalReplyMsg)
            memoryManager.storeMessage(finalReplyMsg, sessionId)
            _chatError.value = null
            _agentState.value = AgentState.Speaking(finalReplyMsg.text)
            onSpeakCallback?.invoke(finalReplyMsg.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            publishChatError(
                ChatErrorUiState.fromException(
                    sessionId = sessionId,
                    requestId = requestId,
                    runId = runId,
                    failure = e
                )
            )
        } catch (e: Exception) {
            _agentState.value = AgentState.Error(NetworkErrorFormatter.toUserMessage(e))
        }
    }

    private suspend fun generatePlan(
        userMsg: ChatMessage,
        context: Context,
        sessionId: String,
        configOverride: LLMConfig? = null,
        personaPrompt: String? = null
    ) {
        try {
            val provider = llmProviderFactory.getActiveProvider(configOverride)
            val relevantContext = memoryManager.getRelevantContext(userMsg.text)
            // B6: the bound persona leads the PLANNING prompt (used by the
            // planner request below and the single-agent request). The
            // multi-agent critic and merge prompts deliberately do NOT get
            // the persona — voicing them would corrupt their JSON contracts.
            val sysPrompt = PersonaPromptComposer.compose(
                personaPrompt,
                "${PlanningPrompts.PLANNING_SYSTEM_PROMPT}\n\nContext about user and device:\n$relevantContext"
            )
            
            val config = settingsRepository.llmConfig.first()
            val plan = if (config.multiAgentModeEnabled) {
                kotlinx.coroutines.coroutineScope {
                    val plannerDeferred = async(Dispatchers.Default) {
                        provider.complete(
                            LLMRequest(
                                systemPrompt = sysPrompt,
                                messages = listOf(userMsg),
                                temperature = 0.2f,
                                maxTokens = 1500,
                                responseFormat = ResponseFormat.JSON
                            )
                        )
                    }

                    val criticDeferred = async(Dispatchers.Default) {
                        provider.complete(
                            LLMRequest(
                                systemPrompt = PlanningPrompts.CRITIC_SYSTEM_PROMPT,
                                messages = listOf(userMsg),
                                temperature = 0.2f,
                                maxTokens = 1000,
                                responseFormat = ResponseFormat.TEXT
                            )
                        )
                    }

                    val plannerResponse = plannerDeferred.await()
                    val criticResponse = criticDeferred.await()
                    reportLocalPlanningLatency(plannerResponse)
                    reportLocalPlanningLatency(criticResponse)

                    val mergePrompt = """
                        ${PlanningPrompts.MERGE_SYSTEM_PROMPT}
                        
                        User Goal: ${userMsg.text}
                        Initial Plan: ${plannerResponse.content}
                        Critic Safety & Edge Case Report: ${criticResponse.content}
                    """.trimIndent()

                    val mergeResponse = provider.complete(
                        LLMRequest(
                            systemPrompt = mergePrompt,
                            messages = listOf(
                                ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    text = "Merge the plan and critique into the final JSON plan.",
                                    sender = ChatMessage.Sender.USER,
                                    imageBase64 = userMsg.imageBase64,
                                    attachmentMimeType = userMsg.attachmentMimeType,
                                    attachmentName = userMsg.attachmentName
                                )
                            ),
                            temperature = 0.1f,
                            maxTokens = 1500,
                            responseFormat = ResponseFormat.JSON
                        )
                    )
                    reportLocalPlanningLatency(mergeResponse)

                    parsePlanFromLlmResponse(mergeResponse.content, userMsg.text)
                }
            } else {
                val response = provider.complete(
                    LLMRequest(
                        systemPrompt = sysPrompt,
                        messages = listOf(userMsg),
                        temperature = 0.1f,
                        maxTokens = 1500,
                        responseFormat = ResponseFormat.JSON
                    )
                )
                reportLocalPlanningLatency(response)
                parsePlanFromLlmResponse(response.content, userMsg.text)
            }

            planManager.startNewPlan(plan, context, PlanStatus.PROPOSED)
            // Re-read after LLM work: user may have flipped mode or revoked grants
            // while planning was in flight; stale pre-LLM config must not auto-run.
            val liveConfig = settingsRepository.llmConfig.first()
            val approval = liveConfig.approvalSettings()
            if (AutoApprovalPolicy.shouldAutoApprove(approval.mode, approval.grantedActions, plan)) {
                recordAutoApprovedTrace(plan, approval.mode, sessionId)
                executePlanLoop(plan, context, sessionId, autoApproved = true)
            } else {
                proposePlan(plan, sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            publishChatError(
                ChatErrorUiState.fromException(
                    sessionId = sessionId,
                    requestId = userMsg.id,
                    runId = UUID.randomUUID().toString(),
                    failure = e
                )
            )
        } catch (e: Exception) {
            fallbackOrError(userMsg, context, e, sessionId, configOverride, personaPrompt)
        }
    }

    private suspend fun reportLocalPlanningLatency(response: LLMResponse) {
        val result = llmProviderFactory.recordPlanningLatency(response)
        if (result?.status == LatencyBudgetStatus.EXCEEDED && result.message != null) {
            onSpeakCallback?.invoke(result.message)
        }
    }

    /**
     * Non-LLM planning failures may still degrade to alias/simple chat. Typed
     * [LLMException]s are handled above and must never fall through here.
     */
    private suspend fun fallbackOrError(
        userMsg: ChatMessage,
        context: Context,
        cause: Throwable,
        sessionId: String,
        configOverride: LLMConfig? = null,
        personaPrompt: String? = null
    ) {
        android.util.Log.e("AgentLoop", "Plan generation failed: ${cause.localizedMessage}", cause)

        val lastExecuted = lastExecutedActionsBySession[sessionId]
        val alias = AliasResolver.resolve(userMsg.text)
            ?: AliasResolver.resolveContextual(userMsg.text, lastExecuted?.action, lastExecuted?.params)
        if (alias != null) {
            executeAliasDirect(alias, userMsg.text, context, sessionId)
            return
        }

        try {
            memoryManager.logTaskExecution(
                stepId = "plan-gen",
                planId = "n/a",
                description = userMsg.text,
                actionType = "PLAN_GENERATION",
                params = emptyMap(),
                success = false,
                resultData = null,
                errorMessage = cause.localizedMessage?.take(200)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AgentLoop", "Failed to log plan generation failure: ${e.localizedMessage}")
        }
        executeSimpleQuery(userMsg, sessionId, configOverride, personaPrompt)
    }

    fun approveProposedPlan(context: Context, grantActions: Set<String> = emptySet()) {
        // Audit-2 #2: only a plan actually sitting at the approval gate may be
        // approved, and only the plan that state carries (mirror
        // rejectProposedPlan's state read below). Previously this executed
        // whatever plan PlanManager happened to hold — including a CANCELLED
        // plan whose steps are still PENDING (executePlanLoop would flip it
        // back to RUNNING and run the remainder) or a superseded proposal —
        // whenever a stale/duplicate approve signal arrived (a second voice
        // "yes", a double-tap, a surface still rendering an old proposal).
        val proposedState = _agentState.value as? AgentState.PlanProposed ?: return
        val proposedPlan = proposedState.plan
        // Note: the state carries the pre-storage plan object (its own status
        // field is the PENDING default) — the authoritative PROPOSED-status
        // check happens under the lock below, against PlanManager's stored
        // copy of this same planId.
        // Consume the proposal synchronously, before launching: a second
        // approve arriving while this one is still starting finds no proposal
        // at the gate and is a no-op.
        _agentState.value = AgentState.Idle
        currentJob?.cancel()
        val job = scope.launch {
            // "Always allow" checkboxes from the approval modal: persist BEFORE
            // executing so a crash mid-plan can't lose an explicit user grant.
            // Filter through isGrantable - neverAutoApprove actions can never
            // enter the allowlist no matter what the UI sends.
            val toGrant = grantActions.filter { AutoApprovalPolicy.isGrantable(it) }
            if (toGrant.isNotEmpty()) {
                val grantedAt = System.currentTimeMillis()
                settingsRepository.updateConfig { current ->
                    current.copy(
                        grantedActions = current.effectiveGrantedActions() + toGrant.associateWith { grantedAt }
                    )
                }
            }
            // Approving a proposed plan starts a new task in its own right (the plan may
            // have been proposed in an earlier, now-idle turn), so pin its session here too -
            // same rationale as processQuery, see activeTaskSessionId. Executes in the
            // session the plan was actually PROPOSED for, never wherever the user happens
            // to be looking right now - falls back to "current" only if somehow nothing
            // was pinned (there is no proposal to misattribute in that case anyway).
            val sessionId = (proposedPlanSessionId ?: conversationRepository.ensureCurrentSessionId())
                .also { activeTaskSessionId = it }
            proposedPlanSessionId = null
            queryMutex.withLock {
                // Re-validate under the lock: execute ONLY the plan that was
                // approved, and only while it is still PROPOSED — PlanManager
                // may have been overwritten (or the plan cancelled) while the
                // grants above were being persisted.
                val plan = planManager.currentPlan.value ?: return@withLock
                if (plan.planId != proposedPlan.planId || plan.status != PlanStatus.PROPOSED) {
                    return@withLock
                }
                executePlanLoop(plan, context, sessionId)
            }
        }
        currentJob = job
    }

    fun rejectProposedPlan() {
        // Capture the proposed plan BEFORE clearing it: rejecting a plan at
        // the approval gate is an auditable event (B4). The denied steps are
        // written to task_history through the same sink executions use, so
        // the audit log shows denials alongside successes and failures
        // instead of the rejection vanishing without a trace. The plan is
        // taken from the PlanProposed state itself — PlanManager deliberately
        // keeps the last FINISHED plan around for the Plan tab, and logging
        // that stale plan as "denied" would falsify the log; only a plan
        // actually sitting at the approval gate counts as denied.
        val deniedPlan = (_agentState.value as? AgentState.PlanProposed)?.plan
        proposedPlanSessionId = null
        // Audit-2 #6: pin the clear to the plan being rejected. clearPlan()
        // runs asynchronously; an unpinned clear could land after a brand-new
        // plan started and erase THAT plan mid-flight.
        planManager.clearPlan(deniedPlan?.planId ?: planManager.currentPlan.value?.planId)
        _agentState.value = AgentState.Idle
        if (deniedPlan != null) {
            scope.launch { logPlanDenial(deniedPlan) }
        }
    }

    /**
     * Records a user-rejected plan in `task_history`: one row per step that
     * never ran, with `success = false` and the denial marker as the error
     * message ([AuditLog.DENIED_MESSAGE]) — the same shape an execution
     * failure takes, so the audit viewer's outcome derivation and the DAO's
     * filtered query both pick these rows up as DENIED. Descriptions and
     * params go through the same redaction as real executions (the
     * repository sink re-applies it, exactly as on the execution path).
     * Best-effort like every other history write in this class: a logging
     * failure must never break the rejection itself.
     */
    private suspend fun logPlanDenial(plan: Plan) {
        for (record in AuditLog.deniedStepRecords(plan)) {
            try {
                val canonicalActionName = actionDispatcher.canonicalActionName(record.actionType)
                memoryManager.logTaskExecution(
                    stepId = record.stepId,
                    planId = record.planId,
                    description = ExecutionHistoryPrivacy.sanitizeDescription(
                        canonicalActionName,
                        record.description
                    ),
                    actionType = record.actionType,
                    params = ExecutionHistoryPrivacy.sanitizeParams(canonicalActionName, record.params),
                    success = false,
                    resultData = null,
                    errorMessage = AuditLog.DENIED_MESSAGE
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AgentLoop", "Failed to log plan denial: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Forgets a session's contextual follow-up state (see
     * [lastExecutedActionsBySession]). Call this once a session is gone for good
     * (ChatViewModel.deleteChat -> ConversationRepository.deleteSession) so its
     * entry doesn't sit in the map forever - a deleted chat can never come back
     * to ask "turn it off" against whatever it last did. Harmless no-op if the
     * session never executed an action, or is already gone.
     */
    fun forgetSession(sessionId: String) {
        lastExecutedActionsBySession.remove(sessionId)
    }

    /**
     * Cancels whatever the agent is currently doing - a running plan, a query in
     * flight, or a pending "waiting for user input" prompt - and returns the loop
     * to Idle. Safe to call when nothing is running: when there is no job to
     * cancel there is nothing in flight for planManager.cancelPlan() to affect
     * either, so it's skipped entirely rather than invoked pointlessly - it would
     * otherwise land on whatever plan is still sitting in PlanManager from the
     * last finished task (kept there deliberately so the Plan tab can keep
     * showing it - see PlanManager.updatePlanStatus) and relabel it, which is
     * exactly what PlanManager's own terminal-status guard on cancelPlan() also
     * defends against - see that doc comment for why that guard is the
     * load-bearing one.
     *
     * The join-then-cancel below runs on its own coroutine, so it can finish
     * arbitrarily later - potentially after a brand-new, unrelated task has
     * already started (see the class-level race this guards against). It must
     * NOT resolve "the plan to cancel" by asking PlanManager what's current at
     * that later point; the only trustworthy identity is whatever plan was
     * actually current RIGHT NOW, captured synchronously before [jobToCancel]
     * even starts unwinding. [planManager.currentPlan] is a plain StateFlow
     * read, not suspending, so this capture is safe to do here.
     */
    /**
     * Session id the in-flight task is pinned to, or null when no task is
     * currently running. Audit-2 #17: lets session-scoped UI actions (chat
     * regenerate/retry) tell "my chat's task" apart from another chat's
     * before touching [cancelCurrentTask] — the loop is a singleton, so
     * its one job may belong to any session or surface.
     */
    fun currentTaskSessionId(): String? =
        if (currentJob?.isActive == true) {
            activeTaskSessionId.takeIf { it.isNotEmpty() }
        } else {
            null
        }

    fun cancelCurrentTask() {
        val jobToCancel = currentJob
        currentJob = null
        waitingSessionId = null
        proposedPlanSessionId = null
        _agentState.value = AgentState.Idle
        jobToCancel?.cancel() ?: return
        val planIdToCancel = planManager.currentPlan.value?.planId
        scope.launch {
            // Wait for the cancelled job to actually finish unwinding before marking
            // the plan cancelled, so its own (best-effort) status writes can't race
            // ahead of and overwrite the CANCELLED status set here.
            jobToCancel.join()
            if (planIdToCancel != null) {
                planManager.cancelPlan(planIdToCancel)
            }
        }
    }

    /**
     * Called right before a genuinely new task starts (see processQuery), never for a
     * reply to a pending awaitUserResponse() prompt. If [newSessionId] is about to take
     * over agentState/planManager's single global slots and some OTHER session still has
     * a plan sitting in AgentState.PlanProposed - proposed, but never approved, rejected,
     * or cancelled - resolve it explicitly right now instead of letting it be silently
     * destroyed: cancel it in planManager and leave a short, honest message in ITS OWN
     * session, mirroring the treatment abandonWaitingTask() gives an abandoned prompt.
     * A no-op when there is nothing stale to resolve, or when the proposal already
     * belongs to [newSessionId] (a new task in the SAME chat legitimately supersedes it).
     */
    private suspend fun resolveStaleProposedPlan(newSessionId: String) {
        val staleSessionId = proposedPlanSessionId
        if (staleSessionId == null || staleSessionId == newSessionId) return
        val proposedState = _agentState.value as? AgentState.PlanProposed ?: return

        proposedPlanSessionId = null
        _agentState.value = AgentState.Idle
        // Target the exact plan this state was carrying, not "whatever is
        // current" - see cancelPlan's expectedPlanId doc comment. There is no
        // job-join race here (the task that proposed this plan already
        // finished), but pinning the identity explicitly keeps this call
        // consistent with cancelCurrentTask/abandonWaitingTask rather than
        // relying on a coincidence that nothing else changed it in between.
        planManager.cancelPlan(proposedState.plan.planId)

        val stoppedMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "I cancelled the plan I proposed here since you started a new task elsewhere.",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(staleSessionId, stoppedMsg)
        memoryManager.storeMessage(stoppedMsg, staleSessionId)
    }

    /**
     * Cancels a task that is parked in awaitUserResponse() for [staleSessionId] because a
     * genuinely new message just arrived in a different session - nobody in
     * [staleSessionId] is ever going to answer that prompt now. Mirrors
     * [cancelCurrentTask]'s join-then-cancel-plan ordering, and additionally leaves a
     * short, honest message in the abandoned prompt's OWN session so that chat doesn't
     * just sit there silently forever.
     */
    private fun abandonWaitingTask(staleSessionId: String) {
        val jobToCancel = currentJob
        currentJob = null
        waitingSessionId = null
        _agentState.value = AgentState.Idle
        jobToCancel?.cancel()
        // Same identity-pinning rationale as cancelCurrentTask: capture now,
        // synchronously, before jobToCancel unwinds - never resolve "the plan
        // to cancel" from whatever PlanManager holds once the join below
        // finally completes.
        val planIdToCancel = planManager.currentPlan.value?.planId
        scope.launch {
            jobToCancel?.join()
            if (planIdToCancel != null) {
                planManager.cancelPlan(planIdToCancel)
            }

            val stoppedMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = "I stopped waiting for your reply here since you started a new conversation elsewhere.",
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "System"
            )
            conversationRepository.insertMessage(staleSessionId, stoppedMsg)
            memoryManager.storeMessage(stoppedMsg, staleSessionId)
        }
    }

    private fun proposePlan(plan: Plan, sessionId: String) {
        proposedPlanSessionId = sessionId
        _agentState.value = AgentState.PlanProposed(plan)
    }

    /**
     * Audit-2 #1: merges a re-evaluation / replan MODIFY [updatedPlan] into
     * [currentPlanState] and decides whether execution may continue without a
     * fresh user approval. Returns true when the caller should keep
     * executing; false when the merged remainder was parked back in the
     * PlanProposed gate (the caller must return).
     *
     * Merge rules: finished steps are kept as-is; an [updatedPlan] step whose
     * stepId matches a still-PENDING original step replaces that step;
     * original PENDING steps the update never mentions are KEPT — approved
     * work must not silently vanish just because the LLM's rewrite omitted
     * it; genuinely new stepIds from [updatedPlan] are appended.
     *
     * Approval gate: any merged PENDING step that differs from what was
     * approved (a new stepId, or a same-id replacement whose action/params/
     * fallback changed, or an approved step the update retired) may only run
     * unattended when the plan was auto-approved AND the whole merged
     * remainder still passes [AutoApprovalPolicy] under the LIVE config —
     * the same all-or-nothing re-check the UnknownAction branch has always
     * applied to AUTO plans, extended to manually approved plans: the user
     * approved a specific step set, and LLM-invented steps must never run
     * past that approval unexamined.
     */
    private suspend fun applyModifiedPlan(
        currentPlanState: Plan,
        updatedPlan: Plan,
        context: Context,
        sessionId: String,
        autoApproved: Boolean
    ): Boolean {
        val finished = currentPlanState.steps.filter { it.status != StepStatus.PENDING }
        val originalPending = currentPlanState.steps.filter { it.status == StepStatus.PENDING }
        val originalPendingIds = originalPending.map { it.stepId }.toSet()
        val updatedById = updatedPlan.steps.associateBy { it.stepId }

        val mergedPending = mutableListOf<PlanStep>()
        val changedPending = mutableListOf<PlanStep>()
        for (original in originalPending) {
            val replacement = updatedById[original.stepId]
            when {
                replacement == null -> mergedPending += original
                replacement.status == StepStatus.PENDING -> {
                    mergedPending += replacement
                    if (replacement.action != original.action ||
                        replacement.params != original.params ||
                        replacement.fallback != original.fallback
                    ) {
                        changedPending += replacement
                    }
                }
                // A replacement arriving already non-PENDING retires the
                // approved step — a change to the approved set, so it is
                // gated like any other edit (the step itself is dropped).
                else -> changedPending += original
            }
        }
        for (step in updatedPlan.steps) {
            if (step.stepId !in originalPendingIds && step.status == StepStatus.PENDING) {
                mergedPending += step
                changedPending += step
            }
        }

        planManager.startNewPlan(currentPlanState.copy(steps = finished + mergedPending), context)

        if (changedPending.isEmpty()) return true

        val liveConfig = settingsRepository.llmConfig.first()
        val approval = liveConfig.approvalSettings()
        val mergedPlan = planManager.currentPlan.value
        if (autoApproved && mergedPlan != null && AutoApprovalPolicy.shouldAutoApprove(
                approval.mode,
                approval.grantedActions,
                mergedPlan.copy(steps = mergedPending)
            )
        ) {
            return true
        }

        // Park the whole remainder at the approval gate. startNewPlan
        // persisted the merged plan as RUNNING; reflect the gate in the
        // stored status too so the Plan tab matches the PlanProposed state.
        planManager.updatePlanStatus(PlanStatus.PROPOSED)
        proposePlan(
            planManager.currentPlan.value ?: (mergedPlan ?: currentPlanState)
                .copy(status = PlanStatus.PROPOSED),
            sessionId
        )
        return false
    }

    private suspend fun executePlanLoop(plan: Plan, context: Context, sessionId: String, autoApproved: Boolean = false) {
        planManager.updatePlanStatus(PlanStatus.RUNNING)
        var currentPlanState = planManager.currentPlan.value ?: return

        while (true) {
            // Cooperative cancellation checkpoint: ensures a cancelCurrentTask() call
            // stops this loop promptly even on iterations that finish without ever
            // hitting a suspending call that would otherwise surface the cancellation.
            currentCoroutineContext().ensureActive()

            val nextStep = planManager.getActiveStep()
            if (nextStep == null) {
                // Audit-2 #5: PENDING steps getActiveStep() cannot reach are
                // stranded — their dependencies can never be satisfied (a
                // dependency step was removed, or a dependency cycle survived
                // validation). They must not be silently counted as done:
                // fail them explicitly so the plan's terminal state and the
                // summary the user hears stay truthful.
                val stranded = currentPlanState.steps.filter { it.status == StepStatus.PENDING }
                for (step in stranded) {
                    planManager.updateStepStatus(
                        step.stepId,
                        StepStatus.FAILED,
                        error = "Step could not run: its dependencies were never satisfied."
                    )
                }
                if (stranded.isNotEmpty()) {
                    currentPlanState = planManager.currentPlan.value ?: currentPlanState
                }
                // Audit-2 #8: a zero-step plan has done nothing; it must
                // never be reported as a successful completion.
                // If there are any failed steps, plan is failed. Otherwise, completed!
                val hasFailed = currentPlanState.steps.isEmpty() ||
                    currentPlanState.steps.any { it.status == StepStatus.FAILED }
                if (hasFailed) {
                    planManager.updatePlanStatus(PlanStatus.FAILED)
                    speakAndSaveSummary(currentPlanState, false, sessionId)
                } else {
                    planManager.updatePlanStatus(PlanStatus.COMPLETED)
                    // Successful completion supersedes any error card still showing.
                    _chatError.value = null
                    speakAndSaveSummary(currentPlanState, true, sessionId)
                }
                break
            }

            planManager.updateStepStatus(nextStep.stepId, StepStatus.RUNNING)

            // Re-read the step's current description/action/params immediately before
            // dispatching. getStepSnapshot takes the same mutex the Plan-tab editor's
            // mutators (updateStepDescription / updateStepParams) hold, so this always
            // observes whichever edit last committed to the DB - never the stale copy
            // getActiveStep() handed back at the top of this loop iteration, which a
            // concurrent edit landing in between would otherwise silently outrun.
            val stepToExecute = planManager.getStepSnapshot(nextStep.stepId) ?: nextStep
            _agentState.value = AgentState.ExecutingPlan(stepToExecute.description)

            // Resolve parameters from prior step results
            val resolvedParams = actionSequenceExecutor.resolveParameters(
                params = stepToExecute.params,
                priorSteps = currentPlanState.steps
            )

            // Execute the action dispatcher
            var actionResult = try {
                var result = actionSequenceExecutor.dispatch(stepToExecute.action, resolvedParams, context)

                resolveNeedsInput(result, stepToExecute.action, resolvedParams, context, sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AgentLoop", "Exception executing action ${stepToExecute.action}: ${e.localizedMessage}", e)
                ActionResult(false, null, e.localizedMessage ?: "Unknown execution error")
            }

            // Redaction must be based on the dispatcher's canonical mapped action,
            // not the raw plan action string — the dispatcher accepts non-canonical
            // names (e.g. "EMAIL", "send-email") that still execute as SEND_EMAIL.
            val canonicalActionName = actionDispatcher.canonicalActionName(stepToExecute.action)

            try {
                memoryManager.logTaskExecution(
                    stepId = stepToExecute.stepId,
                    planId = currentPlanState.planId,
                    description = ExecutionHistoryPrivacy.sanitizeDescription(
                        canonicalActionName,
                        stepToExecute.description
                    ),
                    actionType = stepToExecute.action,
                    params = ExecutionHistoryPrivacy.sanitizeParams(canonicalActionName, resolvedParams),
                    success = actionResult.success,
                    resultData = actionResult.data?.let(com.opendroid.ai.core.crash.CrashLogRedactor::redact),
                    errorMessage = actionResult.error?.let(com.opendroid.ai.core.crash.CrashLogRedactor::redact)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AgentLoop", "Failed to log task execution: ${e.localizedMessage}")
            }

            if (actionResult.success) {
                lastExecutedActionsBySession[sessionId] = LastExecutedAction(stepToExecute.action, resolvedParams)
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.COMPLETED,
                    result = actionResult.data ?: "Completed successfully."
                )
            } else if (actionResult is ActionResult.PendingUserAction) {
                // The action handed control to the user (e.g. the dialer is open awaiting a
                // tap). Nothing failed, so no fallback and no failure replan - surface the
                // hand-off in chat and speech, record it as the step result, and let the
                // normal re-evaluation path decide what remains.
                handlePendingUserAction(actionResult, sessionId)
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.COMPLETED,
                    result = actionResult.message
                )
            } else if (actionResult is ActionResult.UserActionRequired) {
                // A user-facing flow (for example email compose) is not an
                // executed side effect. Do not run an automated fallback or
                // allow the plan to treat the step as completed.
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.FAILED,
                    error = actionResult.error ?: "User action is required before this step can complete."
                )
                // Skip the generic re-evaluation below: its prompt allows adding
                // alternative steps, which would let the agent launch another
                // action or duplicate composer while the user is still reviewing
                // the one just opened. Move on to the next plan step (if any)
                // instead of triggering an automated replan for this failure.
                currentPlanState = planManager.currentPlan.value ?: break
                continue
            } else if (actionResult is ActionResult.UnknownAction) {
                planManager.updateStepStatus(
                    stepToExecute.stepId,
                    StepStatus.FAILED,
                    error = actionResult.error ?: "Action execution failed."
                )

                // Update current state of plan to include the failed step status
                currentPlanState = planManager.currentPlan.value ?: break

                // Trigger learning extraction
                reEvalEngine.get().extractLearning(stepToExecute.action, currentPlanState.goal)

                // Trigger silent replanning
                val completed = currentPlanState.steps.filter { it.status == StepStatus.COMPLETED }
                val remaining = currentPlanState.steps.filter { it.status == StepStatus.PENDING }

                val replan = try {
                    reEvalEngine.get().replanAfterUnknownAction(
                        originalGoal = currentPlanState.goal,
                        failedStep = stepToExecute,
                        completedSteps = completed,
                        remainingSteps = remaining,
                        planId = currentPlanState.planId
                    )
                } catch (e: LLMException) {
                    // Nothing ever resumes a PAUSED plan - mark it FAILED so the Plan
                    // tab shows a truthful terminal state; the error card still offers
                    // the retry path.
                    planManager.updatePlanStatus(PlanStatus.FAILED)
                    publishChatError(
                        ChatErrorUiState.fromException(
                            sessionId = sessionId,
                            requestId = currentPlanState.planId,
                            runId = UUID.randomUUID().toString(),
                            failure = e
                        )
                    )
                    return
                }

                if (replan.speech.isNotEmpty()) {
                    onSpeakCallback?.invoke(replan.speech)
                }

                when (replan.decision.uppercase()) {
                    "ABANDON" -> {
                        planManager.updatePlanStatus(PlanStatus.FAILED)
                        speakAndSaveSummary(currentPlanState, false, sessionId)
                        return
                    }
                    "MODIFY" -> {
                        if (replan.updatedPlan != null) {
                            // Audit-2 #1: merge + approval re-gate shared with
                            // the generic re-evaluation MODIFY below — injected
                            // steps must never run past the approval the user
                            // actually gave, in AUTO or manual mode alike.
                            val mayContinue = applyModifiedPlan(
                                currentPlanState, replan.updatedPlan, context, sessionId, autoApproved
                            )
                            if (!mayContinue) return
                        }
                    }
                    else -> {
                        planManager.updatePlanStatus(PlanStatus.FAILED)
                        speakAndSaveSummary(currentPlanState, false, sessionId)
                        return
                    }
                }

                // Refresh current state of plan and continue
                currentPlanState = planManager.currentPlan.value ?: break
                continue
            } else {
                // Try fallback action
                if (actionSequenceExecutor.shouldAttemptFallback(actionResult, stepToExecute)) {
                    val fallbackResult = try {
                        actionSequenceExecutor.dispatch(stepToExecute.fallback, resolvedParams, context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ActionResult(false, null, e.localizedMessage ?: "Unknown execution error")
                    }
                    if (fallbackResult.success) {
                        planManager.updateStepStatus(
                            stepToExecute.stepId,
                            StepStatus.COMPLETED,
                            result = "Primary failed: ${actionResult.error}. Fallback execution succeeded: ${fallbackResult.data}"
                        )
                    } else {
                        planManager.updateStepStatus(
                            stepToExecute.stepId,
                            StepStatus.FAILED,
                            error = "Primary failed: ${actionResult.error}. Fallback failed: ${fallbackResult.error}"
                        )
                    }
                } else {
                    planManager.updateStepStatus(
                        stepToExecute.stepId,
                        StepStatus.FAILED,
                        error = actionResult.error ?: "Action execution failed."
                    )
                }
            }

            // Refresh current state of plan
            currentPlanState = planManager.currentPlan.value ?: break

            // Re-evaluate Plan Loop
            val completed = currentPlanState.steps.filter { it.status == StepStatus.COMPLETED }
            val failed = currentPlanState.steps.filter { it.status == StepStatus.FAILED }
            val remaining = currentPlanState.steps.filter { it.status == StepStatus.PENDING }

            if (failed.isEmpty() && remaining.isEmpty()) {
                continue
            }

            val reEval = try {
                reEvalEngine.get().evaluateStepResult(
                    originalGoal = currentPlanState.goal,
                    completedSteps = completed,
                    failedSteps = failed,
                    remainingSteps = remaining,
                    planId = currentPlanState.planId
                )
            } catch (e: LLMException) {
                // Same rationale as the replan failure above: PAUSED is a dead end, so
                // fail the plan and let the error card drive recovery.
                planManager.updatePlanStatus(PlanStatus.FAILED)
                publishChatError(
                    ChatErrorUiState.fromException(
                        sessionId = sessionId,
                        requestId = currentPlanState.planId,
                        runId = UUID.randomUUID().toString(),
                        failure = e
                    )
                )
                return
            }

            // Speak post-step evaluation speech if any
            if (reEval.speech.isNotEmpty()) {
                onSpeakCallback?.invoke(reEval.speech)
            }

            when (reEval.decision.uppercase()) {
                "ABANDON" -> {
                    planManager.updatePlanStatus(PlanStatus.FAILED)
                    speakAndSaveSummary(currentPlanState, false, sessionId)
                    return
                }
                "MODIFY" -> {
                    if (reEval.updatedPlan != null) {
                        // Audit-2 #1: this branch previously merged the
                        // LLM's steps and executed them with NO approval
                        // re-check in any mode. Route through the same
                        // merge + re-gate as the UnknownAction branch.
                        val mayContinue = applyModifiedPlan(
                            currentPlanState, reEval.updatedPlan, context, sessionId, autoApproved
                        )
                        if (!mayContinue) return
                        currentPlanState = planManager.currentPlan.value ?: break
                    }
                }
                "CONTINUE" -> {
                    // Do nothing, continue to next step
                }
            }
        }
    }

    /**
     * True only when the user's query refers to what is currently on screen,
     * so a screenshot is genuinely needed as context.
     */
    private fun needsScreenContext(query: String): Boolean {
        val lower = query.lowercase()
        val screenPhrases = listOf(
            "screen", "screenshot", "looking at", "this page", "this app",
            "what am i", "what's this", "what is this", "read this",
            "displayed", "showing", "what do you see", "remember this",
            "save this", "save meeting details", "save the meeting", "important information",
            "save to notes", "add to notes", "add this to my notes", "read and remember"
        )
        return screenPhrases.any { lower.contains(it) }
    }

    private data class NeedsInputRetry(
        val result: ActionResult,
        val params: Map<String, String>
    )

    private suspend fun resolveNeedsInput(
        initialResult: ActionResult,
        actionName: String,
        initialParams: Map<String, String>,
        context: Context,
        sessionId: String
    ): ActionResult {
        var result = initialResult
        var params = initialParams

        repeat(MAX_NEEDS_INPUT_PROMPTS) {
            val needsInput = result as? ActionResult.NeedsInput ?: return result
            val retry = if (needsInput.metadata["type"] == "contact_picker") {
                handleContactPicker(needsInput, actionName, params, context, sessionId)
            } else {
                handleNeedsInput(needsInput, actionName, params, context, sessionId)
            }
            result = retry.result
            params = retry.params
        }

        return ActionResult.Failure(
            errorMsg = "Too many input prompts for $actionName",
            fallback = "Try the command again with all required details."
        )
    }

    /**
     * Handle contact disambiguation when an action returns NeedsInput with contact_picker metadata.
     * Shows options to user, waits for selection, stores preference, re-executes action.
     */
    private suspend fun handleContactPicker(
        pickerResult: ActionResult.NeedsInput,
        actionName: String,
        originalParams: Map<String, String>,
        context: Context,
        sessionId: String
    ): NeedsInputRetry {
        val meta = pickerResult.metadata
        val matchesJson = meta["matches"] ?: return NeedsInputRetry(pickerResult, originalParams)
        val query = meta["query"] ?: ""

        // Parse the matches back from JSON
        val matches: List<Map<String, String>> = try {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<List<Map<String, String>>>(matchesJson)
        } catch (e: Exception) {
            android.util.Log.e("AgentLoop", "Failed to parse contact matches: ${e.message}")
            return NeedsInputRetry(pickerResult, originalParams)
        }

        if (matches.isEmpty()) return NeedsInputRetry(pickerResult, originalParams)

        // Audit-2 #10: tag the persisted picker payload with this prompt's
        // requestId (one extra key per match map — the card parser ignores
        // unknown keys). ChatScreen only enables the card whose tag equals
        // the currently pending prompt's id, so historical pickers render
        // inert and their taps can never be misdelivered to a later prompt.
        val promptRequestId = UUID.randomUUID().toString()
        val taggedPickerJson = json.encodeToString(
            kotlinx.serialization.serializer<List<Map<String, String>>>(),
            matches.map { it + ("requestId" to promptRequestId) }
        )

        // Show picker question to user via chat
        val optionsText = pickerResult.options.joinToString("\n")
        val pickerMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "${pickerResult.question}\n\n$optionsText",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System",
            contactPickerData = taggedPickerJson
        )
        conversationRepository.insertMessage(sessionId, pickerMsg)
        onSpeakCallback?.invoke(pickerResult.question)

        // Wait for user response
        val userSelection = awaitUserResponse(sessionId, promptRequestId)

        // Audit-2 #9: do NOT persist the selection again here — the delivery
        // path in processQuery already stored this reply as a user message
        // (and in memory) before completing the pending prompt's deferred.
        // Inserting it a second time duplicated every picker answer in the
        // transcript and in the history later sent to the LLM.

        // Resolve user selection to a contact
        val selectedContact = when {
            // User typed a number: "1", "2", "3"
            userSelection.trim().toIntOrNull() != null -> {
                val index = userSelection.trim().toInt() - 1
                matches.getOrNull(index)
            }

            // User said "first", "second", "third"
            userSelection.lowercase().contains("first") -> matches.getOrNull(0)
            userSelection.lowercase().contains("second") -> matches.getOrNull(1)
            userSelection.lowercase().contains("third") -> matches.getOrNull(2)
            userSelection.lowercase().contains("fourth") -> matches.getOrNull(3)
            userSelection.lowercase().contains("fifth") -> matches.getOrNull(4)

            // User typed the name
            else -> {
                matches.find { contact ->
                    userSelection.contains(contact["name"] ?: "", ignoreCase = true)
                } ?: matches.find { contact ->
                    (contact["name"] ?: "").contains(userSelection.trim(), ignoreCase = true)
                }
            }
        }

        if (selectedContact == null) {
            // Couldn't match — tell user and fail gracefully
            val failMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = "I didn't catch that. Please try again with the contact's name.",
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "System"
            )
            conversationRepository.insertMessage(sessionId, failMsg)
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "Contact selection not understood",
                    fallback = "Please try the command again"
                ),
                originalParams
            )
        }

        val phone = selectedContact["phone"] ?: return NeedsInputRetry(
            ActionResult.Failure(
                errorMsg = "No phone number for selected contact",
                fallback = "Try again"
            ),
            originalParams
        )
        val name = selectedContact["name"] ?: "Contact"

        // Remember this choice for next time
        memoryManager.storeContactPreference(
            query = query,
            contact = Contact(name = name, phoneNumber = phone)
        )

        // Confirm selection to user
        val confirmMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "Got it! Using $name.",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(sessionId, confirmMsg)

        // Re-execute the original action with resolved contact
        val resolvedParams = originalParams.toMutableMap()
        resolvedParams["contact"] = phone
        if (meta.containsKey("message")) {
            resolvedParams["message"] = meta["message"]!!
        }

        return NeedsInputRetry(
            actionDispatcher.execute(actionName, resolvedParams, context),
            resolvedParams
        )
    }

    /**
     * Generic missing-parameter prompt. Shows the question, waits for the user's
     * reply, and re-executes the action with the answer injected.
     */
    private suspend fun handleNeedsInput(
        needsInput: ActionResult.NeedsInput,
        actionName: String,
        originalParams: Map<String, String>,
        context: Context,
        sessionId: String
    ): NeedsInputRetry {
        val optionsText = if (needsInput.options.isNotEmpty()) {
            "\n\n" + needsInput.options.joinToString("\n") { "- $it" }
        } else {
            ""
        }
        val promptMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = needsInput.question + optionsText,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(sessionId, promptMsg)
        onSpeakCallback?.invoke(needsInput.question)

        val answer = awaitUserResponse(sessionId).trim()
        if (answer.isEmpty()) {
            return NeedsInputRetry(
                ActionResult.Failure(
                    errorMsg = "No value provided for $actionName",
                    fallback = "Try the command again with the missing detail."
                ),
                originalParams
            )
        }

        // Audit-2 #9: no duplicate insert of the answer — processQuery's
        // delivery path already persisted it before completing the deferred
        // (see handleContactPicker).

        val paramKey = paramKeyForNeedsInput(needsInput, actionName)
        val newParams = originalParams.toMutableMap().apply { put(paramKey, answer) }
        return NeedsInputRetry(
            actionDispatcher.execute(actionName, newParams, context),
            newParams
        )
    }

    /**
     * Tell the user that a step is now waiting on them. The agent cannot observe the
     * user's tap, so this is a hand-off notice, not a prompt that blocks the plan.
     */
    private suspend fun handlePendingUserAction(
        pending: ActionResult.PendingUserAction,
        sessionId: String
    ) {
        val pendingMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = pending.message,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        conversationRepository.insertMessage(sessionId, pendingMsg)
        onSpeakCallback?.invoke(pending.message)
    }

    /**
     * Auto-approved plans never show the approval modal, so leave a badged
     * trace message in the chat instead - the audit trail the spec requires.
     * Speech leads with "Running:" so a hands-free user knows no approval
     * prompt is coming.
     */
    private suspend fun recordAutoApprovedTrace(plan: Plan, mode: AutoMode, sessionId: String) {
        val badge = if (mode == AutoMode.YOLO) "YOLO" else "Auto-approved"
        val stepLines = plan.steps.joinToString("\n") { "• ${it.description}" }
        val traceMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = "Running: ${plan.goal} (${plan.steps.size} steps)\n$stepLines",
            sender = ChatMessage.Sender.AGENT,
            modelBadge = badge
        )
        conversationRepository.insertMessage(sessionId, traceMsg)
        memoryManager.storeMessage(traceMsg, sessionId)
        onSpeakCallback?.invoke("Running: ${plan.goal}")
    }

    private suspend fun speakAndSaveSummary(plan: Plan, isSuccess: Boolean, sessionId: String) {
        val summaryText = if (isSuccess) {
            // Build a natural, human-sounding summary from step results
            val stepSummaries = plan.steps
                .filter { it.status == StepStatus.COMPLETED && !it.result.isNullOrBlank() }
                .mapNotNull { step ->
                    val result = step.result ?: return@mapNotNull null
                    when {
                        result.length > 5 && !result.startsWith("{") -> result
                        else -> null
                    }
                }
            if (stepSummaries.isNotEmpty()) {
                stepSummaries.joinToString(". ")
            } else {
                humanizeGoalDone(plan.goal)
            }
        } else {
            // Log the technical errors but DON'T show them to the user
            val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }
            failedSteps.forEach { step ->
                android.util.Log.e("AgentLoop",
                    "Step '${step.action}' failed: ${step.error ?: "unknown"}")
            }
            
            // Check if any failed step has a user-friendly error message
            // (e.g. "I've opened the chat... please tap send")
            val userFacingError = failedSteps.firstNotNullOfOrNull { step ->
                step.error?.takeIf { error ->
                    // Include errors that contain actionable guidance for the user
                    error.contains("opened", ignoreCase = true) ||
                    error.contains("please", ignoreCase = true) ||
                    error.contains("check", ignoreCase = true) ||
                    error.contains("tap", ignoreCase = true) ||
                    error.contains("couldn't confirm", ignoreCase = true)
                }
            }
            
            userFacingError ?: humanizeFailure(plan.goal)
        }

        val assistantMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = summaryText,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = "System"
        )
        memoryManager.storeMessage(assistantMsg, sessionId)
        conversationRepository.insertMessage(sessionId, assistantMsg)

        _agentState.value = AgentState.Speaking(summaryText)
        onSpeakCallback?.invoke(summaryText)
    }

    private fun formatStreamedReply(text: String): String {
        if (!text.startsWith("Error streaming")) return text
        val technical = text.substringAfter(": ", text)
        return NetworkErrorFormatter.toUserMessage(technical)
    }

    private fun parsePlanFromLlmResponse(raw: String, userGoal: String): Plan {
        val stripped = stripMarkdownFences(raw)

        // Prefer wrapper action before unwrapping plan — handles {"action":"...","plan":null}.
        try {
            val root = json.parseToJsonElement(stripped)
            if (root is JsonObject) {
                val action = root["action"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val planElement = root["plan"]
                val hasPlanObject = planElement is JsonObject

                if (action != null && !hasPlanObject) {
                    val params = jsonObjectToStringMap(root["params"]?.jsonObject)
                    return buildSingleStepPlan(userGoal, action, params)
                }

                if (hasPlanObject) {
                    return normalizePlan(json.decodeFromString<Plan>(planElement.toString()))
                }
            }
        } catch (_: Exception) {
            // Fall through to direct Plan parsing
        }

        val cleaned = cleanPlanJson(raw)
        try {
            return normalizePlan(json.decodeFromString<Plan>(cleaned))
        } catch (_: Exception) {
            // Continue with wrapper/single-action parsing below
        }

        val root = json.parseToJsonElement(cleaned)
        if (root is JsonObject) {
            root["plan"]?.let { planElement ->
                if (planElement is JsonObject) {
                    try {
                        return normalizePlan(json.decodeFromString<Plan>(planElement.toString()))
                    } catch (_: Exception) {
                        // fall through
                    }
                }
            }

            val action = root["action"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (action != null) {
                val params = jsonObjectToStringMap(root["params"]?.jsonObject)
                return buildSingleStepPlan(userGoal, action, params)
            }
        }

        throw IllegalArgumentException("Could not parse a valid plan from LLM response")
    }

    private fun stripMarkdownFences(raw: String): String {
        var content = raw.trim()
        if (content.startsWith("```json")) {
            content = content.removePrefix("```json")
        } else if (content.startsWith("```")) {
            content = content.removePrefix("```")
        }
        if (content.endsWith("```")) {
            content = content.removeSuffix("```")
        }
        return content.trim()
    }

    private fun normalizePlan(plan: Plan): Plan {
        // Audit-2 #8: a plan with zero steps is a malformed planner result
        // (a known LLM failure mode: valid JSON, `"steps": []`). Reject it
        // here so generatePlan's failure path re-plans / degrades honestly
        // instead of the empty plan being proposed, approved and instantly
        // "completed" with a success summary for work that was never done.
        require(plan.steps.isNotEmpty()) { "Plan has no steps" }
        return plan.copy(
            planId = plan.planId.ifBlank { UUID.randomUUID().toString() },
            goal = plan.goal.ifBlank { "User request" },
            estimatedSteps = if (plan.estimatedSteps > 0) plan.estimatedSteps else plan.steps.size.coerceAtLeast(1),
            steps = plan.steps.map { step ->
                step.copy(
                    stepId = step.stepId.ifBlank { "s${step.order}" },
                    fallback = step.fallback
                )
            }.let { ensureUniqueStepIds(it) }
        )
    }

    /**
     * Audit-2 #7: stepIds are the join key for status updates, `$stepId`
     * parameter references and dependency lookups, so duplicates corrupt
     * execution (updating one step marks every step sharing its id, while
     * readers only ever find the first). The LLM both repeats explicit ids
     * and repeats `order` values (which the blank-id fill above turns into
     * colliding `s<order>` ids). Suffix later occurrences deterministically;
     * references to the original id bind to the first occurrence.
     */
    private fun ensureUniqueStepIds(steps: List<PlanStep>): List<PlanStep> {
        val seen = mutableMapOf<String, Int>()
        return steps.map { step ->
            val count = (seen[step.stepId] ?: 0) + 1
            seen[step.stepId] = count
            if (count == 1) step else step.copy(stepId = "${step.stepId}_$count")
        }
    }

    private fun buildSingleStepPlan(goal: String, action: String, params: Map<String, String>): Plan {
        return Plan(
            planId = UUID.randomUUID().toString(),
            goal = goal,
            estimatedDuration = "instant",
            estimatedSteps = 1,
            steps = listOf(
                PlanStep(
                    stepId = "s1",
                    order = 1,
                    description = "Execute $action",
                    action = action,
                    params = params,
                    fallback = ""
                )
            )
        )
    }

    private fun jsonObjectToStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: value.toString().trim('"')
        }
    }

    private fun cleanPlanJson(raw: String): String {
        var content = stripMarkdownFences(raw)
        try {
            val jsonElement = json.parseToJsonElement(content)
            if (jsonElement is JsonObject) {
                val planElement = jsonElement["plan"]
                // Only unwrap when plan is an object — preserve wrappers with "plan": null.
                if (planElement is JsonObject) {
                    return planElement.toString()
                }
            }
        } catch (e: Exception) {
            // Silently ignore parsing errors here and let downstream deserialization report them if needed
        }
        return content
    }

    /**
     * Convert raw technical error messages into friendly, user-facing text.
     * Prevents raw Java stack traces, permission denial strings, and
     * intent resolution errors from being spoken to the user.
     */
    private fun formatErrorForUser(action: String, rawError: String): String {
        // Log the technical error for debugging
        android.util.Log.e("AgentLoop", "Action $action error: $rawError")

        // Return only a short, friendly message
        return when {
            rawError.contains("Permission", ignoreCase = true) ||
            rawError.contains("SecurityException", ignoreCase = true) ->
                "I need a permission for that. Check your app settings and try again."

            rawError.contains("ActivityNotFound", ignoreCase = true) ->
                "Looks like the app I need isn't installed."

            rawError.contains("IOException", ignoreCase = true) ->
                "I'm having trouble connecting. Check your internet?"

            else ->
                "Something didn't work out. Mind trying again?"
        }
    }

    /**
     * Generate a natural pre-execution speech line based on the action.
     */
    private fun humanizePreSpeech(action: String): String {
        return when (action) {
            "TOGGLE_FLASHLIGHT" -> "Got it, toggling your flashlight."
            "SET_ALARM" -> "Sure, setting that alarm for you."
            "SET_TIMER" -> "Alright, starting a timer."
            "TAKE_SCREENSHOT" -> "Taking a screenshot now."
            "LOCK_SCREEN" -> "Locking your screen."
            "TOGGLE_WIFI" -> "Alright, switching your WiFi."
            "TOGGLE_BLUETOOTH" -> "On it, toggling Bluetooth."
            "TOGGLE_DND" -> "Got it, changing Do Not Disturb."
            "TOGGLE_HOTSPOT" -> "Sure, toggling your hotspot."
            "TOGGLE_MOBILE_DATA" -> "Alright, switching mobile data."
            "SET_VOLUME" -> "Got it, adjusting the volume."
            "SET_BRIGHTNESS" -> "Sure, adjusting brightness."
            "OPEN_APP" -> "Opening that for you."
            "ANALYZE_SCREENSHOT" -> "Let me take a look at your screen."
            "READ_AND_REMEMBER_SCREEN" -> "Reading your screen and saving the important details."
            "READ_NOTES" -> "Let me look up your notes."
            "RECALL_MEMORY" -> "Searching your saved memories."
            "ADD_NOTE" -> "Saving that note for you."
            "SET_RINGER_MODE" -> "Changing your ringer mode."
            "PLAY_MUSIC" -> "Let me play that for you."
            "MAKE_CALL" -> "Calling now."
            else -> {
                val readable = action.lowercase().replace("_", " ")
                "On it! Let me $readable."
            }
        }
    }

    /**
     * Generate a natural success message when no step result is available.
     */
    private fun humanizeGoalDone(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("alarm") -> "All set! Your alarm is ready."
            lower.contains("flash") || lower.contains("torch") -> "Done! Flashlight's been toggled."
            lower.contains("wifi") -> "WiFi's been updated."
            lower.contains("bluetooth") -> "Bluetooth's been switched."
            lower.contains("volume") -> "Volume's adjusted."
            lower.contains("brightness") -> "Brightness updated."
            lower.contains("screenshot") -> "Screenshot taken!"
            lower.contains("timer") -> "Timer's set and running."
            lower.contains("open") -> "Done, it should be open now."
            lower.contains("call") -> "Calling now."
            lower.contains("message") || lower.contains("whatsapp") -> "Message sent!"
            else -> "All done!"
        }
    }

    /**
     * Generate a natural failure message. Technical details go to logs only.
     */
    private fun humanizeFailure(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("alarm") -> "Sorry, I couldn't set that alarm. Maybe check your Clock app?"
            lower.contains("flash") || lower.contains("torch") -> "Hmm, the flashlight didn't work. Try again?"
            lower.contains("call") -> "I wasn't able to make that call. Want to try again?"
            lower.contains("message") || lower.contains("whatsapp") -> "The message didn't go through. Want me to retry?"
            lower.contains("wifi") || lower.contains("bluetooth") -> "Couldn't change that setting. You might need to do it manually."
            else -> "Sorry, that didn't work out. Want me to try again?"
        }
    }
}
