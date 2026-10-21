package com.opendroid.ai.core.schedule

import android.content.Context
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.agent.AutoApprovalPolicy
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.approvalSettings
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes one claimed firing of a [ScheduledTask] end to end:
 *
 *   claim (CAS in the store — exactly one lane wins an occurrence)
 *   → ensure the task's chat session
 *   → run through the existing execution path for its kind
 *   → write the run into task history + a header/summary trail in the session
 *   → record the outcome on the task, re-arm the next occurrence, notify
 *
 * Execution paths reused, per kind:
 * - INSTRUCTION: [AgentCommandRunner] → `AgentLoop.processQuery` (the full
 *   planner/executor pipeline, with AgentLoop's own approval gate).
 * - MACRO: the macro's stored steps → [ActionSequenceExecutor.execute]
 *   (the exact call `RUN_MACRO` makes), gated BEFORE execution by
 *   [AutoApprovalPolicy] against the live AutoMode/grants. A macro that
 *   may not auto-run is never executed silently: the run stops, an
 *   approval notification is posted, and the session explains which
 *   actions blocked it. (Macros have no proposed-plan UI of their own,
 *   so unlike instructions there is no in-chat plan to approve — the
 *   user runs the macro from the Macros tab or loosens AutoMode. That is
 *   the explicit, consistent policy for this kind.)
 */
@Singleton
class ScheduledTaskExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScheduledTaskStore,
    private val scheduler: ScheduledTaskScheduler,
    private val runner: AgentCommandRunner,
    private val notifier: ScheduledTaskNotifier,
    private val conversationRepository: ConversationRepository,
    private val macroDao: MacroDao,
    private val actionSequenceExecutor: ActionSequenceExecutor,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Claim heartbeat interval: well under the store's 30-min stuck-run window. */
        private const val CLAIM_HEARTBEAT_MILLIS: Long = 5L * 60L * 1000L
    }

    sealed interface ExecutionResult {
        data class Ran(val summary: String) : ExecutionResult
        data class AwaitingApproval(val blockedActions: List<String>) : ExecutionResult
        data class Failed(val message: String) : ExecutionResult

        /** Another lane already fired this occurrence, or the task is gone/disabled. */
        object Skipped : ExecutionResult
    }

    /**
     * Fires [taskId] once. [occurrenceMillis] is the occurrence the
     * enqueueing lane believed was due; it is treated as advisory only —
     * for non-manual runs the due occurrence is always re-derived at
     * execution time, so a worker deferred by Doze past the freshness
     * window never executes a stale occurrence. Null + [manual] means a
     * manual "run now", which bypasses due/staleness checks but still
     * claims.
     */
    suspend fun executeTask(
        taskId: String,
        occurrenceMillis: Long? = null,
        manual: Boolean = false
    ): ExecutionResult {
        val task = store.get(taskId) ?: return ExecutionResult.Skipped
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        // Execution-time revalidation for BOTH lanes: the sweep's explicit
        // occurrence is not trusted blindly (the worker may start hours
        // after it was enqueued), and the alarm lane recomputes the same
        // way. dueOccurrence applies the freshness windows (1h recurring /
        // 24h one-shot) and the already-fired check.
        val occurrence: Long? = when {
            manual -> null
            else -> NextFireCalculator.dueOccurrence(task, now, zone)
        }
        if (!manual && occurrence == null) {
            return ExecutionResult.Skipped
        }

        val claimed = store.tryClaim(taskId, now) ?: return ExecutionResult.Skipped

        val sessionId = ensureSession(claimed)
        postSessionMessage(
            sessionId,
            "⏰ Scheduled run — ${claimed.label}\n" +
                if (claimed.kind == ScheduledTaskKind.INSTRUCTION) claimed.instruction else "Running macro '${claimed.macroName ?: claimed.label}'."
        )

        val result: ExecutionResult = try {
            // Keep the claim fresh while the run executes: without a
            // heartbeat, a run longer than the store's stuck-claim window
            // (macros have no execution timeout of their own) could be
            // reclaimed by the other lane and executed twice concurrently.
            coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(CLAIM_HEARTBEAT_MILLIS)
                        store.touchClaim(taskId, System.currentTimeMillis())
                    }
                }
                try {
                    when (claimed.kind) {
                        ScheduledTaskKind.INSTRUCTION -> runInstruction(claimed, sessionId)
                        ScheduledTaskKind.MACRO -> runMacro(claimed, sessionId)
                    }
                } finally {
                    heartbeat.cancel()
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failed(e.localizedMessage ?: "Unknown error while running the scheduled task.")
        }

        finalizeRun(claimed, sessionId, occurrence, result)
        return result
    }

    private suspend fun runInstruction(task: ScheduledTask, sessionId: String): ExecutionResult {
        return when (val outcome = runner.run(task.instruction, context, sessionId)) {
            is AgentCommandRunner.Outcome.Completed -> ExecutionResult.Ran(outcome.summary)
            is AgentCommandRunner.Outcome.Failed -> ExecutionResult.Failed(outcome.message)
            is AgentCommandRunner.Outcome.NeedsApproval ->
                ExecutionResult.AwaitingApproval(outcome.blockedActions)
            is AgentCommandRunner.Outcome.TimedOut -> ExecutionResult.Failed(
                "Timed out — the task may have been waiting for an answer in its chat. Open the chat to continue it manually."
            )
        }
    }

    private suspend fun runMacro(task: ScheduledTask, sessionId: String): ExecutionResult {
        val macro = task.macroId?.let { macroDao.getMacroById(it) }
            ?: task.macroName?.let { macroDao.getMacroByName(it) }
            ?: return ExecutionResult.Failed("The macro for this schedule no longer exists.")
        val steps: List<PlanStep> = try {
            if (macro.stepsJson.isBlank()) emptyList()
            else json.decodeFromString<List<PlanStep>>(macro.stepsJson)
        } catch (e: Exception) {
            return ExecutionResult.Failed("Macro '${macro.name}' has invalid step data.")
        }
        if (steps.isEmpty()) {
            return ExecutionResult.Failed("Macro '${macro.name}' has no steps to run.")
        }

        // Approval gate, checked BEFORE anything executes — the same
        // policy object the agent loop uses for planned work.
        val approval = settingsRepository.llmConfig.first().approvalSettings()
        val plan = Plan(
            planId = UUID.randomUUID().toString(),
            goal = "Scheduled macro: ${macro.name}",
            estimatedDuration = "",
            estimatedSteps = steps.size,
            steps = steps
        )
        if (!AutoApprovalPolicy.shouldAutoApprove(approval.mode, approval.grantedActions, plan)) {
            return ExecutionResult.AwaitingApproval(
                AutoApprovalPolicy.blockedActions(approval.grantedActions, steps)
            )
        }

        return when (val result = actionSequenceExecutor.execute(steps, context)) {
            is ActionResult.Success -> ExecutionResult.Ran(result.data ?: "Macro '${macro.name}' completed.")
            is ActionResult.NeedsInput -> ExecutionResult.AwaitingApproval(emptyList()).also {
                postSessionMessage(
                    sessionId,
                    "Macro '${macro.name}' needs your input: ${result.question}"
                )
            }
            else -> ExecutionResult.Failed(
                result.error ?: "Macro '${macro.name}' could not be completed."
            )
        }
    }

    /** Records the outcome everywhere it belongs: task row, task history, notifications, next alarm. */
    private suspend fun finalizeRun(
        task: ScheduledTask,
        sessionId: String,
        occurrenceMillis: Long?,
        result: ExecutionResult
    ) {
        val finishedAt = System.currentTimeMillis()
        val summary: String
        val newStatus: ScheduledTaskStatus
        val success: Boolean
        when (result) {
            is ExecutionResult.Ran -> {
                summary = result.summary
                success = true
                newStatus = if (task.recurrence == ScheduleRecurrence.ONCE) {
                    ScheduledTaskStatus.DONE
                } else {
                    ScheduledTaskStatus.ARMED
                }
            }
            is ExecutionResult.AwaitingApproval -> {
                summary = "Waiting for your approval — nothing was executed."
                success = false
                newStatus = ScheduledTaskStatus.AWAITING_APPROVAL
            }
            is ExecutionResult.Failed -> {
                summary = result.message
                success = false
                newStatus = ScheduledTaskStatus.FAILED
            }
            ExecutionResult.Skipped -> return
        }

        val oneShotFinished = task.recurrence == ScheduleRecurrence.ONCE &&
            (newStatus == ScheduledTaskStatus.DONE || newStatus == ScheduledTaskStatus.FAILED)
        // If the task was deleted while this run executed, the update
        // finds nothing: do NOT fall back to the stale claimed snapshot —
        // arming from it would resurrect an alarm (and post notifications)
        // for a task that no longer exists.
        val updated = store.update(task.id) { current ->
            current.copy(
                status = newStatus,
                enabled = if (oneShotFinished) false else current.enabled,
                claimedAtMillis = null,
                sessionId = sessionId,
                lastRunAtMillis = finishedAt,
                lastFiredOccurrenceMillis = occurrenceMillis ?: current.lastFiredOccurrenceMillis,
                lastResultSummary = summary
            )
        } ?: return

        when (result) {
            is ExecutionResult.Ran ->
                notifier.notifyResult(updated, true, summary)
            is ExecutionResult.AwaitingApproval -> {
                postSessionMessage(
                    sessionId,
                    if (result.blockedActions.isNotEmpty()) {
                        "⏸ This scheduled run needs your approval before it can run: " +
                            result.blockedActions.joinToString(", ") +
                            ". Review and approve the plan here, or adjust Auto mode in Settings."
                    } else {
                        "⏸ This scheduled run needs your input before it can continue. Reply here to continue it."
                    }
                )
                notifier.notifyApprovalNeeded(updated, result.blockedActions)
            }
            is ExecutionResult.Failed ->
                notifier.notifyResult(updated, false, summary)
            ExecutionResult.Skipped -> Unit // handled above
        }

        // Every firing lands in the existing task-history store, keyed by
        // the schedule so the Logs tab shows scheduled runs alongside
        // interactive ones. (Instruction runs additionally record their
        // individual plan steps there via AgentLoop, as chat runs do.)
        memoryRepository.logTaskExecution(
            stepId = "schedule-${task.id}",
            planId = "schedule:${task.id}",
            description = task.label,
            actionType = "SCHEDULED_TASK",
            params = mapOf(
                "kind" to task.kind.name,
                "recurrence" to task.recurrence.name,
                "occurrence" to (occurrenceMillis?.toString() ?: "manual")
            ),
            success = success,
            resultData = if (success) summary else null,
            errorMessage = if (success) null else summary
        )

        // Arm the next occurrence (or clear the alarm for a finished one-shot).
        scheduler.arm(updated)
    }

    private suspend fun ensureSession(task: ScheduledTask): String {
        task.sessionId?.let { return it }
        val sessionId = conversationRepository.createSession("⏰ ${task.label}".take(60))
        store.update(task.id) { it.copy(sessionId = sessionId) }
        return sessionId
    }

    private suspend fun postSessionMessage(sessionId: String, text: String) {
        conversationRepository.insertMessage(
            sessionId,
            ChatMessage(
                id = UUID.randomUUID().toString(),
                text = text,
                sender = ChatMessage.Sender.AGENT,
                modelBadge = "Scheduled"
            )
        )
    }
}
