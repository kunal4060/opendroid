package com.opendroid.ai.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Payloads for the D7 watch link — WATCH-SIDE COPY of the phone module's
 * com.opendroid.ai.core.wear payloads. Keep field names and defaults in
 * sync on both sides; `ignoreUnknownKeys` means adding a field is safe,
 * renaming one is a breaking wire change.
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

/** Outcome codes for [WearCommandResult.outcome] (mirror of the phone copy). */
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
    val decidedAtMs: Long = 0L,
    /**
     * The expiry of the approval card the user acted on, echoed back so
     * the phone can reject decisions taken after the card's TTL
     * (finding #7). 0 = the sender did not stamp one (old watch build);
     * the phone then applies no expiry check.
     */
    val expiresAtMs: Long = 0L
)

/** DataClient payload on [WearProtocol.PATH_PENDING_APPROVAL]. */
@Serializable
data class WearPendingApproval(
    val planId: String,
    val goal: String,
    val steps: List<String>,
    val expiresAtMs: Long
)

/** One distilled task-history row shown in the recent-task list. */
@Serializable
data class WearTaskSummary(
    val description: String,
    val success: Boolean,
    val atMs: Long
)

/** DataClient payload on [WearProtocol.PATH_STATUS]. */
@Serializable
data class WearStatusSnapshot(
    val agentState: String,
    val routingLabel: String,
    val lastTask: WearTaskSummary? = null,
    val recentTasks: List<WearTaskSummary> = emptyList(),
    val updatedAtMs: Long = 0L
)
