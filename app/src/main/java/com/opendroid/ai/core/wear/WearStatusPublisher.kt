package com.opendroid.ai.core.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.llm.RoutingStatus
import com.opendroid.ai.core.llm.RoutingStatusRepository
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.models.Plan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outbound half of the D7 watch link: keeps the two DataClient items the
 * watch reads ([WearProtocol.PATH_STATUS] and
 * [WearProtocol.PATH_PENDING_APPROVAL]) mirroring real phone state for the
 * whole process lifetime. Started once from `OpenDroidApp`, next to the
 * C1 widget sync, and best-effort in the same way: every write is guarded,
 * so a phone with no paired watch (or no Play services) just logs and
 * moves on.
 *
 * Sources are the app's existing single sources of truth — nothing is
 * re-derived here:
 *  - agent state: [AgentLoop.agentState] (the same flow chat renders);
 *  - last/recent tasks: `task_history` via [TaskHistoryDao] (the table the
 *    B4 audit log reads);
 *  - routing: [RoutingStatusRepository.status] (the A8 badge's flow).
 *
 * The pending-approval item is written URGENTLY (setUrgent) so the watch
 * buzzes promptly, and deleted the moment the gate resolves on any
 * surface; approval expiry is stamped at publication ([WearProtocol.APPROVAL_TTL_MS])
 * and re-checked on the watch before it renders the card.
 */
@Singleton
class WearStatusPublisher @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val agentLoop: AgentLoop,
    private val taskHistoryDao: TaskHistoryDao,
    private val routingStatusRepository: RoutingStatusRepository,
    private val actionDispatcher: ActionDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val json = WearJson.instance

    @Volatile
    private var publishedApprovalPlanId: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { publishStatusLoop() }
        scope.launch { publishApprovalLoop() }
    }

    private suspend fun publishStatusLoop() {
        combine(
            agentLoop.agentState,
            taskHistoryDao.getTaskHistoryFlow(),
            routingStatusRepository.status
        ) { state, history, routing ->
            val recent = history.take(WearProtocol.RECENT_TASKS_LIMIT).map { row ->
                WearTaskSummary(
                    description = row.description.take(MAX_DESCRIPTION_CHARS),
                    success = row.success,
                    atMs = row.timestamp
                )
            }
            WearStatusSnapshot(
                agentState = agentStateLabel(state),
                routingLabel = routingLabel(routing),
                lastTask = recent.firstOrNull(),
                recentTasks = recent,
                updatedAtMs = System.currentTimeMillis()
            )
        }
            .distinctUntilChanged()
            .collectLatest { snapshot -> putDataItem(WearProtocol.PATH_STATUS, json.encodeToString(snapshot), urgent = false) }
    }

    private suspend fun publishApprovalLoop() {
        // agentState is a StateFlow, which already only emits on change —
        // distinctUntilChanged() on it is a deprecated no-op (warningsAsErrors).
        agentLoop.agentState
            .collectLatest { state ->
                val proposed = state as? AgentState.PlanProposed
                if (proposed == null) {
                    if (publishedApprovalPlanId != null) {
                        deleteDataItem(WearProtocol.PATH_PENDING_APPROVAL)
                        publishedApprovalPlanId = null
                    }
                } else if (publishedApprovalPlanId != proposed.plan.planId) {
                    val payload = pendingApprovalPayload(proposed.plan)
                    val payloadJson = json.encodeToString(payload)
                    // Latch the plan id ONLY on a confirmed publish: a
                    // transient DataClient failure must not silently cost
                    // the user the wrist-approval card for this plan
                    // (finding #5). agentState will not re-emit for an
                    // unchanged plan, so retry here while this plan stays
                    // proposed — collectLatest cancels the wait as soon as
                    // the gate state changes.
                    while (publishedApprovalPlanId != proposed.plan.planId) {
                        if (putDataItem(WearProtocol.PATH_PENDING_APPROVAL, payloadJson, urgent = true)) {
                            publishedApprovalPlanId = proposed.plan.planId
                        } else {
                            delay(APPROVAL_PUBLISH_RETRY_MS)
                        }
                    }
                }
            }
    }

    private fun pendingApprovalPayload(plan: Plan): WearPendingApproval =
        WearPendingApproval(
            planId = plan.planId,
            goal = plan.goal.take(MAX_GOAL_CHARS),
            steps = plan.steps.sortedBy { it.order }.take(MAX_APPROVAL_STEPS).map { step ->
                "${actionDispatcher.canonicalActionName(step.action)} — ${step.description.take(MAX_STEP_CHARS)}"
            },
            expiresAtMs = System.currentTimeMillis() + WearProtocol.APPROVAL_TTL_MS
        )

    /** @return true when the item was accepted by the DataClient. */
    private suspend fun putDataItem(path: String, payloadJson: String, urgent: Boolean): Boolean {
        return try {
            val request = PutDataMapRequest.create(path).apply {
                dataMap.putString(WearProtocol.DATA_KEY_PAYLOAD, payloadJson)
            }.asPutDataRequest()
            if (urgent) request.setUrgent()
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                Tasks.await(Wearable.getDataClient(appContext).putDataItem(request))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish $path to watch", e)
            false
        }
    }

    private suspend fun deleteDataItem(path: String) {
        try {
            val uri = Uri.Builder().scheme(WEAR_URI_SCHEME).path(path).build()
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                Tasks.await(Wearable.getDataClient(appContext).deleteDataItems(uri))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete $path from watch data layer", e)
        }
    }

    private fun agentStateLabel(state: AgentState): String = when (state) {
        AgentState.Idle -> "Idle"
        AgentState.Listening -> "Listening"
        AgentState.Thinking -> "Thinking"
        is AgentState.PlanProposed -> "Waiting for approval"
        is AgentState.ExecutingPlan -> "Executing plan"
        is AgentState.Speaking -> "Replying"
        is AgentState.Error -> "Error"
    }

    private fun routingLabel(status: RoutingStatus): String = when (status) {
        is RoutingStatus.Cloud -> "Cloud: ${status.provider}"
        is RoutingStatus.OnDevice -> "On-device: ${status.backend}"
        is RoutingStatus.OfflineWillUseOnDevice -> "Offline — on-device: ${status.backend}"
        RoutingStatus.OfflineNoRoute -> "Offline — no route"
    }

    companion object {
        private const val TAG = "WearStatusPublisher"
        private const val WEAR_URI_SCHEME = "wear"
        private const val MAX_DESCRIPTION_CHARS = 140
        private const val MAX_GOAL_CHARS = 200
        private const val MAX_STEP_CHARS = 120
        private const val MAX_APPROVAL_STEPS = 12

        /** Delay between pending-approval publish retries after a failure. */
        private const val APPROVAL_PUBLISH_RETRY_MS = 5_000L
    }
}
