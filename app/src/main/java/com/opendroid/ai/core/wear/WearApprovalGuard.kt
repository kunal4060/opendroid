package com.opendroid.ai.core.wear

/**
 * First-answer-wins guard for watch approval decisions.
 *
 * There is deliberately NO separate approval state machine for the watch:
 * the phone's `AgentLoop.agentState` is the single source of truth for what
 * sits at the approval gate, and both the phone UI and the watch answer
 * through the same AgentLoop entry points. A watch decision therefore only
 * applies while the gate still holds exactly the plan the watch approved —
 * if the user already answered on the phone (or the plan timed out / was
 * replaced), the decision is stale and must do nothing. This function is
 * the whole rule, kept pure so it is unit-testable on the JVM.
 */
object WearApprovalGuard {

    enum class Verdict {
        /** Apply the decision through AgentLoop now. */
        APPLY,

        /** The app lock is engaged: the wrist is not an unlock surface. */
        APP_LOCKED,

        /** No plan is at the gate (already resolved elsewhere, or none was). */
        NO_PENDING_PLAN,

        /** A different plan is at the gate — this decision is for an old card. */
        STALE_PLAN,

        /** The card the user acted on had already passed its published TTL. */
        EXPIRED
    }

    fun evaluate(
        isLocked: Boolean,
        pendingPlanId: String?,
        decision: WearApprovalDecision,
        nowMs: Long = System.currentTimeMillis()
    ): Verdict = when {
        isLocked -> Verdict.APP_LOCKED
        pendingPlanId == null -> Verdict.NO_PENDING_PLAN
        pendingPlanId != decision.planId -> Verdict.STALE_PLAN
        // The watch echoes the expiry of the card it rendered; a decision
        // stamped after that moment (or arriving after it) acted on a
        // card that was no longer live (finding #7). Decisions without a
        // stamped expiry (old watch builds) skip the check.
        decision.expiresAtMs > 0L &&
            (decision.decidedAtMs > decision.expiresAtMs || nowMs > decision.expiresAtMs) ->
            Verdict.EXPIRED
        else -> Verdict.APPLY
    }
}
