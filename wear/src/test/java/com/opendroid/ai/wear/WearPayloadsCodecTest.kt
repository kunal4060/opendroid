package com.opendroid.ai.wear

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch-side mirror of the phone's payload codec test: the two modules
 * carry independent copies of the wire payloads, and these round-trips
 * (plus the pinned protocol strings) are what keep the copies honest.
 */
class WearPayloadsCodecTest {

    private val json = WearJson.instance

    @Test
    fun `command request encodes with the shared field names`() {
        val encoded = json.encodeToString(
            WearCommandRequest(correlationId = "abc-123", text = "Set an alarm for 7 AM", sentAtMs = 42L)
        )
        assertTrue(encoded.contains("\"correlationId\":\"abc-123\""))
        assertTrue(encoded.contains("\"text\":\"Set an alarm for 7 AM\""))
        val decoded = json.decodeFromString<WearCommandRequest>(encoded)
        assertEquals("abc-123", decoded.correlationId)
        assertEquals("Set an alarm for 7 AM", decoded.text)
    }

    @Test
    fun `approval decision round-trips`() {
        val original = WearApprovalDecision(planId = "plan-9", approved = false, decidedAtMs = 5L)
        val decoded = json.decodeFromString<WearApprovalDecision>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `status snapshot from the phone decodes`() {
        val wire = """
            {"agentState":"Waiting for approval","routingLabel":"On-device: Local model",
             "lastTask":{"description":"Set alarm","success":true,"atMs":7},
             "recentTasks":[{"description":"Set alarm","success":true,"atMs":7}],
             "updatedAtMs":10}
        """.trimIndent()
        val decoded = json.decodeFromString<WearStatusSnapshot>(wire)
        assertEquals("Waiting for approval", decoded.agentState)
        assertEquals(1, decoded.recentTasks.size)
        assertEquals("Set alarm", decoded.lastTask?.description)
    }

    @Test
    fun `pending approval from the phone decodes`() {
        val wire = """
            {"planId":"plan-9","goal":"Message Atharv","steps":["SEND_SMS — Text Atharv"],"expiresAtMs":999}
        """.trimIndent()
        val decoded = json.decodeFromString<WearPendingApproval>(wire)
        assertEquals("plan-9", decoded.planId)
        assertEquals(listOf("SEND_SMS — Text Atharv"), decoded.steps)
    }

    @Test
    fun `protocol constants match the phone copy`() {
        assertEquals("opendroid_agent_v1", WearProtocol.CAPABILITY_AGENT)
        assertEquals("/opendroid/command", WearProtocol.PATH_COMMAND)
        assertEquals("/opendroid/command-result", WearProtocol.PATH_COMMAND_RESULT)
        assertEquals("/opendroid/approval-decision", WearProtocol.PATH_APPROVAL_DECISION)
        assertEquals("/opendroid/pending-approval", WearProtocol.PATH_PENDING_APPROVAL)
        assertEquals("/opendroid/status", WearProtocol.PATH_STATUS)
    }
}
