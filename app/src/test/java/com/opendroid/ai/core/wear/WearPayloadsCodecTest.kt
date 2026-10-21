package com.opendroid.ai.core.wear

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format pinning for the D7 watch link: the phone and watch modules
 * carry independent copies of these payloads, so round-trips here (and the
 * mirrored test in :wear) are what keep the two copies honest.
 */
class WearPayloadsCodecTest {

    private val json = WearJson.instance

    @Test
    fun `command request round-trips`() {
        val original = WearCommandRequest(correlationId = "abc-123", text = "Set an alarm for 7 AM", sentAtMs = 42L)
        val decoded = json.decodeFromString<WearCommandRequest>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `command result round-trips`() {
        val original = WearCommandResult("abc-123", WearCommandOutcome.COMPLETED, "Done — alarm set for 7 AM")
        val decoded = json.decodeFromString<WearCommandResult>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `pending approval round-trips with steps`() {
        val original = WearPendingApproval(
            planId = "plan-9",
            goal = "Message Atharv the build update",
            steps = listOf("SEND_SMS — Text Atharv", "OPEN_APP — Open Messages"),
            expiresAtMs = 999L
        )
        val decoded = json.decodeFromString<WearPendingApproval>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `status snapshot round-trips with recent tasks`() {
        val original = WearStatusSnapshot(
            agentState = "Idle",
            routingLabel = "Cloud: GPT-4o",
            lastTask = WearTaskSummary("Set alarm", success = true, atMs = 7L),
            recentTasks = listOf(
                WearTaskSummary("Set alarm", success = true, atMs = 7L),
                WearTaskSummary("Denied plan step", success = false, atMs = 3L)
            ),
            updatedAtMs = 10L
        )
        val decoded = json.decodeFromString<WearStatusSnapshot>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown fields from a newer peer are tolerated`() {
        val wire = """{"planId":"p1","approved":true,"decidedAtMs":5,"futureField":"x"}"""
        val decoded = json.decodeFromString<WearApprovalDecision>(wire)
        assertEquals("p1", decoded.planId)
        assertTrue(decoded.approved)
    }

    @Test
    fun `protocol constants stay on the shared paths`() {
        // These strings are the wire contract with the :wear module's copy.
        assertEquals("opendroid_agent_v1", WearProtocol.CAPABILITY_AGENT)
        assertEquals("/opendroid/command", WearProtocol.PATH_COMMAND)
        assertEquals("/opendroid/command-result", WearProtocol.PATH_COMMAND_RESULT)
        assertEquals("/opendroid/approval-decision", WearProtocol.PATH_APPROVAL_DECISION)
        assertEquals("/opendroid/pending-approval", WearProtocol.PATH_PENDING_APPROVAL)
        assertEquals("/opendroid/status", WearProtocol.PATH_STATUS)
    }
}
