package com.opendroid.ai.core.schedule

import android.content.Context
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.AutoApprovalPolicy
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.approvalSettings
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one instruction through the REAL agent loop, headlessly — the same
 * `AgentLoop.processQuery` entry point chat and voice use, pinned to the
 * scheduled task's own session. This class adds no planning or execution
 * logic of its own; it only observes the loop's state until it reaches a
 * terminal state and translates that into a run outcome.
 *
 * Approval interposition is inherited, not re-implemented: AgentLoop
 * itself consults [AutoApprovalPolicy] with the live config before
 * executing any plan, and only parks the plan in `AgentState.PlanProposed`
 * when the policy says it may NOT auto-run. So a scheduled run can never
 * execute a MANUAL-approval action silently — when the loop ends in
 * PlanProposed, the plan stays proposed in the task's session and the
 * user approves it from chat via the normal approve path
 * (`ChatViewModel.approvePlan` → `AgentLoop.approveProposedPlan`).
 *
 * Known interaction (inherited app semantics): AgentLoop is a single-task
 * singleton — starting a scheduled run preempts whatever interactive task
 * is in flight, exactly like sending a new chat message does. Runs are
 * serialised through a Mutex so two scheduled tasks never preempt each
 * other mid-flight.
 */
@Singleton
class AgentCommandRunner @Inject constructor(
    private val agentLoop: AgentLoop,
    private val settingsRepository: SettingsRepository
) {

    sealed interface Outcome {
        /** The loop finished: a spoken/summary reply was produced. */
        data class Completed(val summary: String) : Outcome

        /** The loop reported an error. */
        data class Failed(val message: String) : Outcome

        /**
         * The loop parked a plan for approval instead of executing it.
         * [blockedActions] are the actions the current AutoMode/grants
         * would not auto-approve — the reason it stopped.
         */
        data class NeedsApproval(val plan: Plan, val blockedActions: List<String>) : Outcome

        /**
         * No terminal state within the timeout — most often the run asked
         * the user a question (AgentLoop parks on awaitUserResponse) and
         * nobody was there to answer. The in-flight task is cancelled so
         * it cannot hang the loop forever; the chat session keeps the
         * question for the user to pick up manually.
         */
        data class TimedOut(val lastState: String) : Outcome
    }

    private val runMutex = Mutex()

    suspend fun run(
        instruction: String,
        context: Context,
        sessionId: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): Outcome = runMutex.withLock {
        // Snapshot the state BEFORE starting this run: a fast run can
        // pass through Thinking and land in a terminal state before the
        // collector below subscribes, and StateFlow then replays only
        // that latest value — with `started` never observed, the run
        // would hang until the timeout and be misreported as TimedOut.
        // A terminal state that differs from this pre-run snapshot is
        // this run's own outcome and counts even when `started` was
        // never seen; an identical replay is the previous turn's.
        val preRunState = agentLoop.agentState.value
        agentLoop.processQuery(instruction, context, explicitSessionId = sessionId)
        // The job this call set in flight: a fresh task's own job, or the
        // parked job the instruction is answering. Its completion is a
        // run boundary the state VALUE cannot provide (see below).
        val runJob = agentLoop.currentTaskJob()

        // A terminal state counts once THIS run has actually started
        // (Thinking/ExecutingPlan seen) or when it is not the pre-run
        // replay (see above). agentState is a shared StateFlow and may
        // still hold the previous turn's Speaking/Error when processQuery
        // is called; PlanProposed needs no extra guard — a parked plan
        // from an earlier turn compares equal to the snapshot only if
        // this run re-parked the identical plan, which processQuery's
        // fresh plan never is.
        //
        // One hole remains in the value-based predicate: a near-instant
        // run can pass Thinking -> terminal entirely between processQuery
        // and the collector's first emission (StateFlow conflation), and
        // if that terminal value EQUALS the pre-run snapshot — the steady
        // state under a persistent misconfiguration, e.g. every turn
        // ending in Error("No API key configured") — the predicate can
        // never distinguish it from the previous turn's replay, and the
        // run would be misreported as a 10-minute TimedOut. The job
        // branch closes it: the loop sets its terminal state before its
        // job completes, so once runJob is done, the terminal state
        // visible then IS this run's outcome, identical value or not.
        var started = false
        val terminal = withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                select<AgentState?> {
                    async {
                        agentLoop.agentState.first { state ->
                            if (state is AgentState.Thinking || state is AgentState.ExecutingPlan) {
                                started = true
                            }
                            state.isTerminalState() && (started || state != preRunState)
                        }
                    }.onAwait { it }
                    if (runJob != null) {
                        async {
                            runJob.join()
                            val state = agentLoop.agentState.value
                            // Job ended without a terminal state (e.g.
                            // cancelled and reset to Idle): no signal
                            // from this branch — the state branch (or
                            // the timeout) decides.
                            if (state.isTerminalState()) state else awaitCancellation()
                        }.onAwait { it }
                    }
                }
            }
        }

        when (terminal) {
            is AgentState.Speaking -> Outcome.Completed(terminal.text)
            is AgentState.Error -> Outcome.Failed(terminal.message)
            is AgentState.PlanProposed -> {
                val approval = settingsRepository.llmConfig.first().approvalSettings()
                Outcome.NeedsApproval(
                    plan = terminal.plan,
                    blockedActions = AutoApprovalPolicy.blockedActions(
                        approval.grantedActions,
                        terminal.plan.steps
                    )
                )
            }
            else -> {
                val lastState = agentLoop.agentState.value.javaClass.simpleName
                agentLoop.cancelCurrentTask()
                Outcome.TimedOut(lastState)
            }
        }
    }

    companion object {
        /** Long enough for multi-step plans, short enough to never wedge the agent loop overnight. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10L * 60L * 1000L
    }
}

/** The states a run can end in (a parked plan counts as an ending for the runner). */
private fun AgentState.isTerminalState(): Boolean =
    this is AgentState.Speaking || this is AgentState.Error || this is AgentState.PlanProposed
