package com.opendroid.ai.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalIntentContractTest {

    private fun parsed(extras: Map<String, String?>, action: String) =
        ExternalIntentContract.parse(action, extras)
            as ExternalIntentContract.ParseResult.Parsed

    private fun malformed(extras: Map<String, String?>, action: String?) =
        ExternalIntentContract.parse(action, extras)
            as ExternalIntentContract.ParseResult.Malformed

    // ── RUN_COMMAND ──────────────────────────────────────────────────────

    @Test
    fun `run command parses with required extras only`() {
        val request = parsed(
            mapOf(ExternalIntentContract.EXTRA_INSTRUCTION to "Turn on the torch"),
            ExternalIntentContract.ACTION_RUN_COMMAND
        ).request as ExternalIntentContract.Request.RunCommand
        assertEquals("Turn on the torch", request.instruction)
        assertNull(request.sessionId)
        assertNull(request.authToken)
    }

    @Test
    fun `run command extracts token session and request id`() {
        val request = parsed(
            mapOf(
                ExternalIntentContract.EXTRA_INSTRUCTION to "Open my notes",
                ExternalIntentContract.EXTRA_AUTH_TOKEN to "tok123",
                ExternalIntentContract.EXTRA_SESSION to "session-9",
                ExternalIntentContract.EXTRA_REQUEST_ID to "req-1"
            ),
            ExternalIntentContract.ACTION_RUN_COMMAND
        ).request as ExternalIntentContract.Request.RunCommand
        assertEquals("tok123", request.authToken)
        assertEquals("session-9", request.sessionId)
        assertEquals("req-1", request.requestId)
    }

    @Test
    fun `run command without an instruction is malformed`() {
        val result = malformed(
            emptyMap(), ExternalIntentContract.ACTION_RUN_COMMAND
        )
        assertTrue(result.reason.contains("instruction"))
    }

    @Test
    fun `run command with a blank instruction is malformed`() {
        malformed(
            mapOf(ExternalIntentContract.EXTRA_INSTRUCTION to "   "),
            ExternalIntentContract.ACTION_RUN_COMMAND
        )
    }

    @Test
    fun `run command with an oversized instruction is malformed`() {
        malformed(
            mapOf(
                ExternalIntentContract.EXTRA_INSTRUCTION to
                    "x".repeat(ExternalIntentContract.MAX_INSTRUCTION_LENGTH + 1)
            ),
            ExternalIntentContract.ACTION_RUN_COMMAND
        )
    }

    // ── RUN_MACRO ────────────────────────────────────────────────────────

    @Test
    fun `run macro parses by name and by id`() {
        val byName = parsed(
            mapOf(ExternalIntentContract.EXTRA_MACRO_NAME to "Morning routine"),
            ExternalIntentContract.ACTION_RUN_MACRO
        ).request as ExternalIntentContract.Request.RunMacro
        assertEquals("Morning routine", byName.macroName)
        assertNull(byName.macroId)

        val byId = parsed(
            mapOf(ExternalIntentContract.EXTRA_MACRO_ID to "macro-42"),
            ExternalIntentContract.ACTION_RUN_MACRO
        ).request as ExternalIntentContract.Request.RunMacro
        assertEquals("macro-42", byId.macroId)
    }

    @Test
    fun `run macro with neither id nor name is malformed`() {
        malformed(emptyMap(), ExternalIntentContract.ACTION_RUN_MACRO)
    }

    // ── QUERY_STATUS / general ───────────────────────────────────────────

    @Test
    fun `query status parses with no extras`() {
        val request = parsed(emptyMap(), ExternalIntentContract.ACTION_QUERY_STATUS)
            .request
        assertTrue(request is ExternalIntentContract.Request.QueryStatus)
    }

    @Test
    fun `unknown action is malformed`() {
        val result = malformed(emptyMap(), "com.example.DO_SOMETHING")
        assertTrue(result.reason.contains("unknown action"))
    }

    @Test
    fun `missing action is malformed`() {
        malformed(emptyMap(), null)
    }

    @Test
    fun `blank token parses as absent so the gateway can reject it as auth failure`() {
        val request = parsed(
            mapOf(
                ExternalIntentContract.EXTRA_INSTRUCTION to "Hi",
                ExternalIntentContract.EXTRA_AUTH_TOKEN to "   "
            ),
            ExternalIntentContract.ACTION_RUN_COMMAND
        ).request as ExternalIntentContract.Request.RunCommand
        assertNull(request.authToken)
    }

    @Test
    fun `oversized request id is malformed`() {
        malformed(
            mapOf(
                ExternalIntentContract.EXTRA_REQUEST_ID to
                    "r".repeat(ExternalIntentContract.MAX_REQUEST_ID_LENGTH + 1)
            ),
            ExternalIntentContract.ACTION_QUERY_STATUS
        )
    }

    // ── Reply target ─────────────────────────────────────────────────────

    @Test
    fun `reply target defaults to the command result action`() {
        val request = parsed(
            mapOf(
                ExternalIntentContract.EXTRA_INSTRUCTION to "Hi",
                ExternalIntentContract.EXTRA_REPLY_PACKAGE to "com.example.tasker"
            ),
            ExternalIntentContract.ACTION_RUN_COMMAND
        ).request
        assertEquals("com.example.tasker", request.replyTarget?.packageName)
        assertEquals(
            ExternalIntentContract.ACTION_COMMAND_RESULT,
            request.replyTarget?.action
        )
    }

    @Test
    fun `no reply target when no reply extras are present`() {
        val request = parsed(
            mapOf(ExternalIntentContract.EXTRA_INSTRUCTION to "Hi"),
            ExternalIntentContract.ACTION_RUN_COMMAND
        ).request
        assertNull(request.replyTarget)
    }
}
