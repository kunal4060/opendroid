package com.opendroid.ai.core.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Payloads for the D7 watch link. Every message/DataItem body is one JSON
 * document (kotlinx.serialization) — payloads are plan *summaries* and short
 * status lines only, never full plan internals or raw action params, the
 * same redaction posture as the execution-history privacy layer.
 *
 * These classes are duplicated in the `:wear` module
 * (com.opendroid.ai.wear.WearPayloads) for the same reason as
 * [WearProtocol]: keep field names and defaults in sync on both sides.
 * `ignoreUnknownKeys` on both ends means adding a field is safe; renaming
 * one is a breaking wire change.
 */
object WearJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
}

/** Watch → phone on [WearProtocol.PATH_COMMAND]. */
@Serializable
data class WearCommandRequest(
    val correlationId: String,
    val text: String,
    val sentAtMs: Long = 0L
)

/** Outcome codes for [WearCommandResult.outcome]. */
object WearCommandOutcome {
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val NEEDS_APPROVAL = "NEEDS_APPROVAL"
    const val APP_LOCKED = "APP_LOCKED"
    const val TIMED_OUT = "TIMED_OUT"
    const val REJECTED = "REJECTED"
}

/** Phone → watch on [WearProtocol.PATH_COMMAND_RESULT]. */
@Serializable
data class WearCommandResult(
    val correlationId: String,
    val outcome: String,
    val message: String
)

/** Watch → phone on [WearProtocol.PATH_APPROVAL_DECISION]. */
@Serializable
data class WearApprovalDecision(
    val planId: String,
    val approved: Boolean,
    val decidedAtMs: Long = 0L
)

/**
 * DataClient payload on [WearProtocol.PATH_PENDING_APPROVAL]. Present only
 * while a plan sits at the phone's approval gate; the phone deletes the item
 * the moment the gate resolves on ANY surface (first answer wins).
 * [steps] are display strings — canonical action name + step description —
 * never raw params.
 */
@Serializable
data class WearPendingApproval(
    val planId: String,
    val goal: String,
    val steps: List<String>,
    val expiresAtMs: Long
)

/** One distilled task-history row for the watch's recent-task list. */
@Serializable
data class WearTaskSummary(
    val description: String,
    val success: Boolean,
    val atMs: Long
)

/** DataClient payload on [WearProtocol.PATH_STATUS]. */
@Serializable
data class WearStatusSnapshot(
    /** Simple name of the phone's current AgentState (Idle, Thinking, …). */
    val agentState: String,
    /** Human routing line, e.g. "Cloud: GPT-4o", "On-device: Local model". */
    val routingLabel: String,
    val lastTask: WearTaskSummary? = null,
    val recentTasks: List<WearTaskSummary> = emptyList(),
    val updatedAtMs: Long = 0L
)
