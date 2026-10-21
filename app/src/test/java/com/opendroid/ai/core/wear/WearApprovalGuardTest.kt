package com.opendroid.ai.core.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the first-answer-wins rule for watch approval decisions (D7): a
 * decision applies only while the phone's approval gate still holds the
 * exact plan the watch card showed, and never while the app is locked.
 */
class WearApprovalGuardTest {

    private fun decision(planId: String, approved: Boolean = true) =
        WearApprovalDecision(planId = planId, approved = approved, decidedAtMs = 123L)

    @Test
    fun `applies when the same plan is still proposed and unlocked`() {
        assertEquals(
            WearApprovalGuard.Verdict.APPLY,
            WearApprovalGuard.evaluate(isLocked = false, pendingPlanId = "plan-1", decision = decision("plan-1"))
        )
    }

    @Test
    fun `applies to a deny decision as well`() {
        assertEquals(
            WearApprovalGuard.Verdict.APPLY,
            WearApprovalGuard.evaluate(isLocked = false, pendingPlanId = "plan-1", decision = decision("plan-1", approved = false))
        )
    }

    @Test
    fun `locked phone wins over everything`() {
        assertEquals(
            WearApprovalGuard.Verdict.APP_LOCKED,
            WearApprovalGuard.evaluate(isLocked = true, pendingPlanId = "plan-1", decision = decision("plan-1"))
        )
        assertEquals(
            WearApprovalGuard.Verdict.APP_LOCKED,
            WearApprovalGuard.evaluate(isLocked = true, pendingPlanId = null, decision = decision("plan-1"))
        )
    }

    @Test
    fun `no pending plan means the decision is a no-op`() {
        assertEquals(
            WearApprovalGuard.Verdict.NO_PENDING_PLAN,
            WearApprovalGuard.evaluate(isLocked = false, pendingPlanId = null, decision = decision("plan-1"))
        )
    }

    @Test
    fun `a different plan at the gate makes the decision stale`() {
        assertEquals(
            WearApprovalGuard.Verdict.STALE_PLAN,
            WearApprovalGuard.evaluate(isLocked = false, pendingPlanId = "plan-2", decision = decision("plan-1"))
        )
    }
}
