package com.opendroid.ai.wear

/**
 * Wire contract for the Wear OS companion (feature D7) — WATCH-SIDE COPY.
 *
 * Deliberately duplicated from the phone module
 * (com.opendroid.ai.core.wear.WearProtocol): the research decision was a
 * tiny duplicated constants file in both modules over a shared module for a
 * handful of strings. KEEP THE TWO COPIES IN SYNC — a path or capability
 * renamed on one side only fails silently (nothing connects, no error),
 * because the Data Layer matches on package name + signing certificate and
 * routes on these exact path strings.
 */
object WearProtocol {
    /** Capability the phone advertises (its res/values/wear.xml). */
    const val CAPABILITY_AGENT = "opendroid_agent_v1"

    /** Watch → phone: run this text as an agent command. */
    const val PATH_COMMAND = "/opendroid/command"

    /** Phone → watch: the outcome of a command. */
    const val PATH_COMMAND_RESULT = "/opendroid/command-result"

    /** Watch → phone: approve/deny a pending plan. */
    const val PATH_APPROVAL_DECISION = "/opendroid/approval-decision"

    /** DataClient item: the plan currently parked at the phone's approval gate. */
    const val PATH_PENDING_APPROVAL = "/opendroid/pending-approval"

    /** DataClient item: latest agent status snapshot from the phone. */
    const val PATH_STATUS = "/opendroid/status"

    /** DataMap key under which every DataClient item carries its JSON payload. */
    const val DATA_KEY_PAYLOAD = "payload"
}
