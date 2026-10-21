package com.opendroid.ai.core.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.data.db.dao.ChatSessionDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.ChatSessionEntity
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.MemoryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which exported entry point delivered the intent (recorded in the audit log). */
enum class ExternalCaller { BROADCAST, ACTIVITY }

/** The result of handling one external intent. */
data class ExternalOutcome(
    val success: Boolean,
    /** One of [ExternalIntentContract]'s CODE_* values. */
    val code: String,
    val summary: String,
    val requestId: String?,
    /** Extra status fields (QUERY_STATUS only), merged into [resultExtras]. */
    val statusExtras: Map<String, String> = emptyMap()
) {
    /** The flat string map used for result-broadcast extras and activity result extras. */
    fun resultExtras(): Map<String, String> = buildMap {
        put(ExternalIntentContract.EXTRA_RESULT_SUCCESS, success.toString())
        put(ExternalIntentContract.EXTRA_RESULT_CODE, code)
        put(ExternalIntentContract.EXTRA_RESULT_SUMMARY, summary)
        requestId?.let { put(ExternalIntentContract.EXTRA_RESULT_REQUEST_ID, it) }
        putAll(statusExtras)
    }
}

/**
 * The single funnel every external intent passes through (feature C7).
 *
 * Pipeline, in order — every step can reject, and every outcome (accepted or
 * rejected) is written to the task history via [MemoryRepository], which is
 * what the in-app history/audit surfaces read:
 *  1. Shape: [ExternalIntentContract.parse] — unknown action, missing or
 *     oversized extras → MALFORMED, no crash, nothing executes.
 *  2. Master toggle: Settings → External automation must be ON → API_DISABLED.
 *  3. Token: the `auth_token` extra must verify against the stored salted
 *     hash record → AUTH_FAILED. "No token configured", "no token sent" and
 *     "wrong token" all produce the identical outcome, so callers learn
 *     nothing about which one happened. The token value itself is never
 *     logged — the audit entry records only present/absent/invalid.
 *  4. App lock: while the A5 app lock is engaged, execution requests are
 *     refused (APP_LOCKED) — a locked phone must not become remotely
 *     drivable because a toggle was left on. QUERY_STATUS still answers,
 *     but with coarse state only (no labels/summaries/task descriptions).
 *  5. Routing — deliberately onto the EXISTING execution paths, never a
 *     parallel executor:
 *      - RUN_COMMAND → [AgentLoop.processQuery] in the dedicated
 *        "External commands" chat session (or the caller-named session),
 *        inheriting planning, risk tiers and the per-chat AutoMode approval
 *        policy exactly as if the user had typed it there.
 *      - RUN_MACRO → `ActionDispatcher.execute("RUN_MACRO", …)`, the same
 *        action the agent itself invokes.
 *      - QUERY_STATUS → the last external run record + the most recent
 *        task-history row.
 *
 * If the caller supplied a reply target, the outcome is also sent as an
 * explicit broadcast (component- or package-addressed) — never an implicit
 * broadcast, because results can contain personal data.
 */
@Singleton
class ExternalCommandGateway @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: ExternalAutomationStore,
    private val agentLoop: AgentLoop,
    private val actionDispatcher: ActionDispatcher,
    private val macroDao: MacroDao,
    private val chatSessionDao: ChatSessionDao,
    private val taskHistoryDao: TaskHistoryDao,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val appLockManager: AppLockManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Incremented per accepted command run; stale status watchers stop when it moves. */
    private val runGeneration = AtomicLong(0)

    suspend fun handle(intent: Intent, caller: ExternalCaller): ExternalOutcome {
        val extras = extractExtras(intent)
        val outcome = when (
            val parsed = ExternalIntentContract.parse(intent.action, extras)
        ) {
            is ExternalIntentContract.ParseResult.Malformed ->
                reject(
                    code = ExternalIntentContract.CODE_MALFORMED,
                    reason = parsed.reason,
                    action = intent.action,
                    caller = caller,
                    requestId = extras[ExternalIntentContract.EXTRA_REQUEST_ID],
                    authState = AUTH_MALFORMED
                )
            is ExternalIntentContract.ParseResult.Parsed ->
                handleParsed(parsed.request, intent.action, caller)
        }
        replyTargetFrom(extras)?.let { target ->
            sendResultBroadcast(target, outcome)
        }
        return outcome
    }

    private suspend fun handleParsed(
        request: ExternalIntentContract.Request,
        action: String?,
        caller: ExternalCaller
    ): ExternalOutcome {
        if (!store.isEnabled()) {
            return reject(
                ExternalIntentContract.CODE_API_DISABLED,
                "External automation is turned off in Settings.",
                action, caller, request.requestId, authState = AUTH_NOT_CHECKED
            )
        }
        val tokenRecord = store.tokenRecord()
        val presented = request.authToken
        if (tokenRecord == null || presented == null ||
            !ExternalApiToken.verify(presented, tokenRecord)
        ) {
            return reject(
                ExternalIntentContract.CODE_AUTH_FAILED,
                "Missing or invalid auth token.",
                action, caller, request.requestId,
                authState = if (presented == null) AUTH_MISSING else AUTH_INVALID
            )
        }
        return when (request) {
            is ExternalIntentContract.Request.QueryStatus -> queryStatus(request, caller)
            is ExternalIntentContract.Request.RunCommand -> {
                if (appLockManager.isLocked.value) {
                    reject(
                        ExternalIntentContract.CODE_APP_LOCKED,
                        "App is locked; external commands are refused while locked.",
                        action, caller, request.requestId, authState = AUTH_OK
                    )
                } else {
                    runCommand(request, action, caller)
                }
            }
            is ExternalIntentContract.Request.RunMacro -> {
                if (appLockManager.isLocked.value) {
                    reject(
                        ExternalIntentContract.CODE_APP_LOCKED,
                        "App is locked; external commands are refused while locked.",
                        action, caller, request.requestId, authState = AUTH_OK
                    )
                } else {
                    runMacro(request, action, caller)
                }
            }
        }
    }

    // ── RUN_COMMAND ──────────────────────────────────────────────────────

    private suspend fun runCommand(
        request: ExternalIntentContract.Request.RunCommand,
        action: String?,
        caller: ExternalCaller
    ): ExternalOutcome {
        val sessionId = request.sessionId ?: ExternalIntentContract.EXTERNAL_SESSION_ID
        val sessionReady = if (request.sessionId == null) {
            ensureExternalSession()
            true
        } else {
            chatSessionDao.getAllSessions().first().any { it.id == sessionId }
        }
        if (!sessionReady) {
            return reject(
                ExternalIntentContract.CODE_NOT_FOUND,
                "Unknown chat session: $sessionId",
                action, caller, request.requestId, authState = AUTH_OK
            )
        }

        val startedAt = System.currentTimeMillis()
        val generation = runGeneration.incrementAndGet()
        store.setLastRun(
            LastExternalRun(
                requestId = request.requestId,
                kind = KIND_COMMAND,
                label = request.instruction.take(LABEL_MAX_LENGTH),
                state = ExternalIntentContract.STATE_RUNNING,
                summary = null,
                startedAtMs = startedAt,
                updatedAtMs = startedAt
            )
        )
        // The one and only execution path: exactly what typing in chat does.
        agentLoop.processQuery(request.instruction, appContext, explicitSessionId = sessionId)
        audit(
            action = action, caller = caller, requestId = request.requestId,
            authState = AUTH_OK, success = true, code = ExternalIntentContract.CODE_OK,
            description = "External command accepted: ${request.instruction.take(LABEL_MAX_LENGTH)}"
        )
        trackCommandRun(generation, request.requestId, request.instruction, startedAt, sessionId)
        return ExternalOutcome(
            success = true,
            code = ExternalIntentContract.CODE_OK,
            summary = "Command accepted; it is running in the '${
                if (request.sessionId == null) ExternalIntentContract.EXTERNAL_SESSION_TITLE else sessionId
            }' chat session. Use QUERY_STATUS for the outcome.",
            requestId = request.requestId
        )
    }

    /**
     * Follows the shared [AgentLoop.agentState] while an externally started
     * command is outstanding and keeps the stored last-run record current, so
     * QUERY_STATUS can report RUNNING / AWAITING_APPROVAL / COMPLETED /
     * FAILED. Heuristic by nature — AgentLoop is a singleton shared with the
     * UI — and superseded watchers stop as soon as a newer external run (or
     * the tracking timeout) arrives.
     */
    private fun trackCommandRun(
        generation: Long,
        requestId: String?,
        instruction: String,
        startedAt: Long,
        sessionId: String
    ) {
        scope.launch {
            var sawActive = false
            var lastState: String? = null
            while (System.currentTimeMillis() - startedAt < TRACK_TIMEOUT_MS) {
                if (runGeneration.get() != generation) return@launch
                when (val state = agentLoop.agentState.value) {
                    is AgentState.Error -> {
                        updateTrackedRun(generation, requestId, instruction, startedAt,
                            ExternalIntentContract.STATE_FAILED, state.message)
                        return@launch
                    }
                    is AgentState.PlanProposed -> {
                        sawActive = true
                        if (lastState != ExternalIntentContract.STATE_AWAITING_APPROVAL) {
                            lastState = ExternalIntentContract.STATE_AWAITING_APPROVAL
                            updateTrackedRun(generation, requestId, instruction, startedAt,
                                ExternalIntentContract.STATE_AWAITING_APPROVAL,
                                "A plan is proposed and waiting for approval in the app.")
                        }
                    }
                    AgentState.Idle -> {
                        // Only a reply produced by THIS run counts (timestamp
                        // at/after the run start) — the session keeps older
                        // replies from previous external runs.
                        val reply = conversationRepository.getLastMessages(sessionId, 3)
                            .lastOrNull {
                                it.sender == ChatMessage.Sender.AGENT &&
                                    it.timestamp >= startedAt
                            }
                        if (sawActive || reply != null) {
                            updateTrackedRun(generation, requestId, instruction, startedAt,
                                ExternalIntentContract.STATE_COMPLETED, reply?.text)
                            return@launch
                        }
                    }
                    else -> {
                        sawActive = true
                        if (lastState != ExternalIntentContract.STATE_RUNNING) {
                            lastState = ExternalIntentContract.STATE_RUNNING
                            updateTrackedRun(generation, requestId, instruction, startedAt,
                                ExternalIntentContract.STATE_RUNNING, null)
                        }
                    }
                }
                delay(TRACK_POLL_MS)
            }
        }
    }

    private fun updateTrackedRun(
        generation: Long,
        requestId: String?,
        instruction: String,
        startedAt: Long,
        state: String,
        summary: String?
    ) {
        if (runGeneration.get() != generation) return
        store.setLastRun(
            LastExternalRun(
                requestId = requestId,
                kind = KIND_COMMAND,
                label = instruction.take(LABEL_MAX_LENGTH),
                state = state,
                summary = summary?.take(SUMMARY_MAX_LENGTH),
                startedAtMs = startedAt,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    // ── RUN_MACRO ────────────────────────────────────────────────────────

    private suspend fun runMacro(
        request: ExternalIntentContract.Request.RunMacro,
        action: String?,
        caller: ExternalCaller
    ): ExternalOutcome {
        val macro = when {
            request.macroId != null -> macroDao.getMacroById(request.macroId)
            else -> macroDao.getMacroByName(request.macroName!!)
        } ?: return reject(
            ExternalIntentContract.CODE_NOT_FOUND,
            "No such macro: ${request.macroName ?: request.macroId}",
            action, caller, request.requestId, authState = AUTH_OK
        )
        if (!macro.isEnabled) {
            return reject(
                ExternalIntentContract.CODE_EXECUTION_FAILED,
                "Macro '${macro.name}' is disabled.",
                action, caller, request.requestId, authState = AUTH_OK
            )
        }

        val startedAt = System.currentTimeMillis()
        store.setLastRun(
            LastExternalRun(
                requestId = request.requestId,
                kind = KIND_MACRO,
                label = macro.name.take(LABEL_MAX_LENGTH),
                state = ExternalIntentContract.STATE_RUNNING,
                summary = null,
                startedAtMs = startedAt,
                updatedAtMs = startedAt
            )
        )
        // The same RUN_MACRO action the agent itself dispatches — macro steps
        // execute through ActionSequenceExecutor / ActionDispatcher as usual.
        val result = actionDispatcher.execute(
            "RUN_MACRO", mapOf("macroName" to macro.name), appContext
        )
        val summary = (result.data ?: result.error ?: "Macro finished.")
            .take(SUMMARY_MAX_LENGTH)
        store.setLastRun(
            LastExternalRun(
                requestId = request.requestId,
                kind = KIND_MACRO,
                label = macro.name.take(LABEL_MAX_LENGTH),
                state = if (result.success) {
                    ExternalIntentContract.STATE_COMPLETED
                } else {
                    ExternalIntentContract.STATE_FAILED
                },
                summary = summary,
                startedAtMs = startedAt,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        audit(
            action = action, caller = caller, requestId = request.requestId,
            authState = AUTH_OK, success = result.success,
            code = if (result.success) {
                ExternalIntentContract.CODE_OK
            } else {
                ExternalIntentContract.CODE_EXECUTION_FAILED
            },
            description = "External macro run '${macro.name}': $summary"
        )
        return ExternalOutcome(
            success = result.success,
            code = if (result.success) {
                ExternalIntentContract.CODE_OK
            } else {
                ExternalIntentContract.CODE_EXECUTION_FAILED
            },
            summary = summary,
            requestId = request.requestId
        )
    }

    // ── QUERY_STATUS ─────────────────────────────────────────────────────

    private suspend fun queryStatus(
        request: ExternalIntentContract.Request.QueryStatus,
        caller: ExternalCaller
    ): ExternalOutcome {
        val locked = appLockManager.isLocked.value
        val lastRun = store.lastRun()
        val lastTask = taskHistoryDao.getTaskHistoryFlow().first().firstOrNull()
        val extras = buildMap {
            if (lastRun != null) {
                put(ExternalIntentContract.EXTRA_STATUS_STATE, lastRun.state)
                put(ExternalIntentContract.EXTRA_STATUS_KIND, lastRun.kind)
                put(
                    ExternalIntentContract.EXTRA_STATUS_UPDATED_AT,
                    lastRun.updatedAtMs.toString()
                )
                if (!locked) {
                    put(ExternalIntentContract.EXTRA_STATUS_LABEL, lastRun.label)
                    lastRun.summary?.let {
                        put(ExternalIntentContract.EXTRA_STATUS_SUMMARY, it)
                    }
                }
            } else {
                put(ExternalIntentContract.EXTRA_STATUS_STATE, ExternalIntentContract.STATE_NONE)
            }
            if (lastTask != null) {
                put(
                    ExternalIntentContract.EXTRA_LAST_TASK_SUCCESS,
                    lastTask.success.toString()
                )
                put(
                    ExternalIntentContract.EXTRA_LAST_TASK_TIMESTAMP,
                    lastTask.timestamp.toString()
                )
                if (!locked) {
                    put(ExternalIntentContract.EXTRA_LAST_TASK_DESCRIPTION, lastTask.description)
                    put(ExternalIntentContract.EXTRA_LAST_TASK_ACTION, lastTask.actionType)
                }
            }
        }
        audit(
            action = ExternalIntentContract.ACTION_QUERY_STATUS, caller = caller,
            requestId = request.requestId, authState = AUTH_OK, success = true,
            code = ExternalIntentContract.CODE_OK,
            description = "External status query answered."
        )
        return ExternalOutcome(
            success = true,
            code = ExternalIntentContract.CODE_OK,
            summary = if (lastRun != null) {
                "Last external run: ${lastRun.state}"
            } else {
                "No external run recorded yet."
            },
            requestId = request.requestId,
            statusExtras = extras
        )
    }

    // ── Rejection + audit ────────────────────────────────────────────────

    private suspend fun reject(
        code: String,
        reason: String,
        action: String?,
        caller: ExternalCaller,
        requestId: String?,
        authState: String
    ): ExternalOutcome {
        Log.w(TAG, "External intent rejected [$code]: $reason")
        audit(
            action = action, caller = caller, requestId = requestId,
            authState = authState, success = false, code = code,
            description = "External intent rejected: $reason"
        )
        return ExternalOutcome(
            success = false, code = code, summary = reason, requestId = requestId
        )
    }

    /**
     * Writes one task-history row — the same store the in-app history/audit
     * surfaces read. The token is never included; only its auth state.
     * Logging failures are swallowed: an audit hiccup must not change the
     * outcome of the request itself.
     */
    private suspend fun audit(
        action: String?,
        caller: ExternalCaller,
        requestId: String?,
        authState: String,
        success: Boolean,
        code: String,
        description: String
    ) {
        try {
            memoryRepository.logTaskExecution(
                stepId = "external",
                planId = "external-api",
                description = description.take(LABEL_MAX_LENGTH),
                actionType = ACTION_TYPE_AUDIT,
                params = buildMap {
                    put("action", action ?: "null")
                    put("caller", caller.name)
                    put("auth", authState)
                    put("code", code)
                    requestId?.let { put("request_id", it) }
                },
                success = success,
                resultData = null,
                errorMessage = if (success) null else code
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to audit external intent", e)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun ensureExternalSession() {
        val exists = chatSessionDao.getAllSessions().first()
            .any { it.id == ExternalIntentContract.EXTERNAL_SESSION_ID }
        if (!exists) {
            val now = System.currentTimeMillis()
            chatSessionDao.insert(
                ChatSessionEntity(
                    id = ExternalIntentContract.EXTERNAL_SESSION_ID,
                    title = ExternalIntentContract.EXTERNAL_SESSION_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    isCurrent = false
                )
            )
        }
    }

    /** Reads only the contract's known extras; unknown or non-String extras are ignored. */
    private fun extractExtras(intent: Intent): Map<String, String?> {
        val keys = listOf(
            ExternalIntentContract.EXTRA_AUTH_TOKEN,
            ExternalIntentContract.EXTRA_INSTRUCTION,
            ExternalIntentContract.EXTRA_SESSION,
            ExternalIntentContract.EXTRA_MACRO_ID,
            ExternalIntentContract.EXTRA_MACRO_NAME,
            ExternalIntentContract.EXTRA_REQUEST_ID,
            ExternalIntentContract.EXTRA_REPLY_PACKAGE,
            ExternalIntentContract.EXTRA_REPLY_ACTION,
            ExternalIntentContract.EXTRA_REPLY_COMPONENT
        )
        return keys.associateWith { key ->
            try {
                intent.getStringExtra(key)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun replyTargetFrom(
        extras: Map<String, String?>
    ): ExternalIntentContract.ReplyTarget? {
        val component = extras[ExternalIntentContract.EXTRA_REPLY_COMPONENT]
            ?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = extras[ExternalIntentContract.EXTRA_REPLY_PACKAGE]
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (component == null && pkg == null) return null
        val action = extras[ExternalIntentContract.EXTRA_REPLY_ACTION]
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: ExternalIntentContract.ACTION_COMMAND_RESULT
        return ExternalIntentContract.ReplyTarget(component, pkg, action)
    }

    /** Explicit-component/package broadcast only — never implicit (results can be personal). */
    private fun sendResultBroadcast(
        target: ExternalIntentContract.ReplyTarget,
        outcome: ExternalOutcome
    ) {
        try {
            val resultIntent = Intent(target.action).apply {
                outcome.resultExtras().forEach { (key, value) -> putExtra(key, value) }
                val componentName = target.component
                    ?.let { ComponentName.unflattenFromString(it) }
                if (componentName != null) {
                    component = componentName
                } else if (target.packageName != null) {
                    setPackage(target.packageName)
                }
            }
            appContext.sendBroadcast(resultIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send external result broadcast", e)
        }
    }

    /** Hilt entry point for the manifest-instantiated receiver/activity. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ExternalCommandEntryPoint {
        fun externalCommandGateway(): ExternalCommandGateway
    }

    companion object {
        private const val TAG = "ExternalCommandGateway"
        private const val ACTION_TYPE_AUDIT = "EXTERNAL_API"
        private const val KIND_COMMAND = "COMMAND"
        private const val KIND_MACRO = "MACRO"

        private const val AUTH_OK = "ok"
        private const val AUTH_MISSING = "missing"
        private const val AUTH_INVALID = "invalid"
        private const val AUTH_NOT_CHECKED = "not_checked"
        private const val AUTH_MALFORMED = "malformed"

        private const val LABEL_MAX_LENGTH = 200
        private const val SUMMARY_MAX_LENGTH = 500
        private const val TRACK_POLL_MS = 1_000L
        private const val TRACK_TIMEOUT_MS = 15 * 60 * 1_000L
    }
}
