package com.opendroid.ai.core.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.schedule.AgentCommandRunner
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.data.db.dao.ChatSessionDao
import com.opendroid.ai.data.db.entities.ChatSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side brain of the D7 watch link: everything a paired watch sends
 * lands here (via [WearListenerService]) and is routed onto the EXISTING
 * agent paths — never a parallel executor:
 *
 *  - Commands run through [AgentCommandRunner], the same headless runner
 *    C4's scheduled tasks use, pinned to the dedicated "Wear commands" chat
 *    session. Planning, risk tiers and the per-chat approval policy apply
 *    exactly as if the user had typed the command in that chat.
 *  - Approval decisions call [AgentLoop.approveProposedPlan] /
 *    [AgentLoop.rejectProposedPlan] — the identical entry points the phone's
 *    own approval UI uses (rejection is audit-logged by AgentLoop itself,
 *    feature B4). [WearApprovalGuard] enforces first-answer-wins against the
 *    live `AgentState`, so watch and phone can never double-resolve a plan.
 *
 * Lock posture mirrors the C7 external gateway: while the A5 app lock is
 * engaged, commands are refused with an explicit APP_LOCKED result and
 * approval decisions are dropped — a locked phone must not become drivable
 * from the wrist. (Status publication is outbound and unaffected: the watch
 * only ever receives summaries the phone chose to publish.)
 */
@Singleton
class WearCommandHandler @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val agentLoop: AgentLoop,
    private val agentCommandRunner: AgentCommandRunner,
    private val appLockManager: AppLockManager,
    private val chatSessionDao: ChatSessionDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = WearJson.instance

    /** Entry from [WearListenerService]; returns immediately, work is async. */
    fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearProtocol.PATH_COMMAND ->
                scope.launch { handleCommand(event.sourceNodeId, event.data) }
            WearProtocol.PATH_APPROVAL_DECISION ->
                scope.launch { handleApprovalDecision(event.data) }
            else -> Log.d(TAG, "Ignoring message on unknown path ${event.path}")
        }
    }

    private suspend fun handleCommand(sourceNodeId: String, data: ByteArray) {
        val request = try {
            json.decodeFromString<WearCommandRequest>(data.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "Malformed watch command", e)
            return
        }

        if (request.text.isBlank() || request.text.length > WearProtocol.MAX_COMMAND_CHARS) {
            sendResult(
                sourceNodeId,
                WearCommandResult(
                    correlationId = request.correlationId,
                    outcome = WearCommandOutcome.REJECTED,
                    message = "Command was empty or too long (max ${WearProtocol.MAX_COMMAND_CHARS} characters)."
                )
            )
            return
        }

        if (appLockManager.isLocked.value) {
            sendResult(
                sourceNodeId,
                WearCommandResult(
                    correlationId = request.correlationId,
                    outcome = WearCommandOutcome.APP_LOCKED,
                    message = "Phone is locked — unlock OpenDroid on the phone to run watch commands."
                )
            )
            return
        }

        ensureWearSession()

        val outcome = agentCommandRunner.run(
            instruction = request.text,
            context = appContext,
            sessionId = WearProtocol.WEAR_SESSION_ID,
            timeoutMillis = WearProtocol.COMMAND_TIMEOUT_MS
        )
        val result = when (outcome) {
            is AgentCommandRunner.Outcome.Completed -> WearCommandResult(
                request.correlationId, WearCommandOutcome.COMPLETED,
                outcome.summary.take(MAX_RESULT_CHARS)
            )
            is AgentCommandRunner.Outcome.Failed -> WearCommandResult(
                request.correlationId, WearCommandOutcome.FAILED,
                outcome.message.take(MAX_RESULT_CHARS)
            )
            is AgentCommandRunner.Outcome.NeedsApproval -> WearCommandResult(
                request.correlationId, WearCommandOutcome.NEEDS_APPROVAL,
                "Plan is waiting for approval — approve it from the watch card or the phone."
            )
            is AgentCommandRunner.Outcome.TimedOut -> WearCommandResult(
                request.correlationId, WearCommandOutcome.TIMED_OUT,
                "The command timed out on the phone before finishing."
            )
        }
        sendResult(sourceNodeId, result)
    }

    private suspend fun handleApprovalDecision(data: ByteArray) {
        val decision = try {
            json.decodeFromString<WearApprovalDecision>(data.decodeToString())
        } catch (e: Exception) {
            Log.w(TAG, "Malformed approval decision", e)
            return
        }

        val pendingPlanId = (agentLoop.agentState.value as? AgentState.PlanProposed)?.plan?.planId
        when (WearApprovalGuard.evaluate(appLockManager.isLocked.value, pendingPlanId, decision)) {
            WearApprovalGuard.Verdict.APPLY -> {
                if (decision.approved) {
                    agentLoop.approveProposedPlan(appContext)
                } else {
                    agentLoop.rejectProposedPlan()
                }
            }
            WearApprovalGuard.Verdict.APP_LOCKED ->
                Log.i(TAG, "Watch approval decision dropped: app is locked")
            WearApprovalGuard.Verdict.NO_PENDING_PLAN ->
                Log.i(TAG, "Watch approval decision dropped: no plan at the gate (already resolved?)")
            WearApprovalGuard.Verdict.STALE_PLAN ->
                Log.i(TAG, "Watch approval decision dropped: plan ${decision.planId} is no longer proposed")
            WearApprovalGuard.Verdict.EXPIRED ->
                Log.i(TAG, "Watch approval decision dropped: the watch card for plan ${decision.planId} had expired")
        }
        // No reply message: resolution is state, published by
        // WearStatusPublisher — the pending-approval DataItem disappears and
        // the status snapshot moves on, which is what the watch observes.
    }

    private suspend fun sendResult(nodeId: String, result: WearCommandResult) {
        try {
            val bytes = json.encodeToString(result).encodeToByteArray()
            withContext(Dispatchers.IO) {
                Tasks.await(
                    Wearable.getMessageClient(appContext)
                        .sendMessage(nodeId, WearProtocol.PATH_COMMAND_RESULT, bytes)
                )
            }
        } catch (e: Exception) {
            // The watch may have gone out of range mid-run; the result is
            // lost by design (MessageClient is fire-and-forget) and the
            // watch shows its own timeout state rather than a fake success.
            Log.w(TAG, "Could not deliver command result to watch node $nodeId", e)
        }
    }

    /** Same shape as C7's dedicated-session bootstrap, for the wear session. */
    private suspend fun ensureWearSession() {
        val exists = chatSessionDao.getAllSessions().first()
            .any { it.id == WearProtocol.WEAR_SESSION_ID }
        if (!exists) {
            val now = System.currentTimeMillis()
            chatSessionDao.insert(
                ChatSessionEntity(
                    id = WearProtocol.WEAR_SESSION_ID,
                    title = WearProtocol.WEAR_SESSION_TITLE,
                    createdAt = now,
                    updatedAt = now,
                    isCurrent = false
                )
            )
        }
    }

    companion object {
        private const val TAG = "WearCommandHandler"
        private const val MAX_RESULT_CHARS = 500
    }
}
