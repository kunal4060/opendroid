package com.opendroid.ai.core.wear

/**
 * Wire contract for the Wear OS companion (feature D7).
 *
 * The Data Layer only connects apps with an identical package name AND
 * signing certificate, so the `:wear` module carries
 * `applicationId "com.opendroid.aiagent"` and the same env-driven release
 * signing as `:app` (see wear/build.gradle). These constants are deliberately
 * duplicated in the `:wear` module (com.opendroid.ai.wear.WearProtocol) — the
 * research decision was a tiny duplicated constants file over a shared module
 * for a handful of strings. KEEP THE TWO COPIES IN SYNC: a path or capability
 * renamed on one side only fails silently (nothing connects, no error).
 *
 * Transport split, by semantics:
 *  - Commands and approval decisions are moment-in-time events →
 *    MessageClient (fire-and-forget; correlation ids + timeouts live in app
 *    code, see [WearCommandHandler] and the watch's repository).
 *  - Agent status and the pending-approval card are state →
 *    DataClient items, which sync and persist across transient disconnects.
 *    The phone is the only writer; the watch only reads/deletes nothing.
 */
object WearProtocol {
    /** Capability the phone advertises (app/src/main/res/values/wear.xml). */
    const val CAPABILITY_AGENT = "opendroid_agent_v1"

    /** Watch → phone: run this text as an agent command (WearCommandRequest). */
    const val PATH_COMMAND = "/opendroid/command"

    /** Phone → watch: the outcome of a command (WearCommandResult). */
    const val PATH_COMMAND_RESULT = "/opendroid/command-result"

    /** Watch → phone: approve/deny a pending plan (WearApprovalDecision). */
    const val PATH_APPROVAL_DECISION = "/opendroid/approval-decision"

    /** DataClient item: the plan currently parked at the approval gate, if any. */
    const val PATH_PENDING_APPROVAL = "/opendroid/pending-approval"

    /** DataClient item: latest agent status snapshot (WearStatusSnapshot). */
    const val PATH_STATUS = "/opendroid/status"

    /** DataMap key under which every DataClient item carries its JSON payload. */
    const val DATA_KEY_PAYLOAD = "payload"

    /** Chat session watch commands run in — visible in the phone's chat list. */
    const val WEAR_SESSION_ID = "wear-commands"
    const val WEAR_SESSION_TITLE = "Wear commands"

    /** A wrist-typed/dictated command longer than this is rejected, not truncated. */
    const val MAX_COMMAND_CHARS = 1000

    /** How long a published pending approval stays actionable on the wrist. */
    const val APPROVAL_TTL_MS = 10L * 60L * 1000L

    /** Status snapshot carries at most this many recent task rows. */
    const val RECENT_TASKS_LIMIT = 5

    /** Server-side wait for a watch command before reporting TIMED_OUT. */
    const val COMMAND_TIMEOUT_MS = 5L * 60L * 1000L
}
