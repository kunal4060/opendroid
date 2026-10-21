package com.opendroid.ai.core.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcTest {

    @Test
    fun `request framing carries jsonrpc id method and params`() {
        val request = McpJsonRpc.buildRequest(7, "tools/call", JSONObject().put("name", "x"))
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(7, request.getInt("id"))
        assertEquals("tools/call", request.getString("method"))
        assertEquals("x", request.getJSONObject("params").getString("name"))
    }

    @Test
    fun `notification framing has no id`() {
        val notification = McpJsonRpc.buildNotification("notifications/initialized", null)
        assertEquals("notifications/initialized", notification.getString("method"))
        assertTrue(!notification.has("id"))
    }

    @Test
    fun `result and error frames parse with their ids`() {
        val result = McpJsonRpc.parseMessage(
            """{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""
        ) as McpJsonRpc.Message.Result
        assertEquals(3L, result.id)

        val error = McpJsonRpc.parseMessage(
            """{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"Method not found"}}"""
        ) as McpJsonRpc.Message.Error
        assertEquals(4L, error.id)
        assertEquals(-32601, error.code)
        assertEquals("Method not found", error.message)
    }

    @Test
    fun `server messages and junk do not parse as answers`() {
        val ping = McpJsonRpc.parseMessage(
            """{"jsonrpc":"2.0","id":9,"method":"ping"}"""
        ) as McpJsonRpc.Message.ServerMessage
        assertEquals("ping", ping.method)

        assertNull(McpJsonRpc.parseMessage("not json at all"))
        assertNull(McpJsonRpc.parseMessage("""{"unrelated":true}"""))
    }

    @Test
    fun `tools list parsing skips malformed entries`() {
        val tools = McpJsonRpc.parseTools(
            JSONObject(
                """
                {"tools":[
                  {"name":"read_file","description":"Reads","inputSchema":{"type":"object"}},
                  {"description":"nameless"},
                  {"name":"write_file"}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(listOf("read_file", "write_file"), tools.map { it.name })
        assertEquals("Reads", tools[0].description)
        assertEquals("object", tools[1].inputSchema.getString("type"))
    }

    @Test
    fun `call outcome flattens text parts and honours isError`() {
        val outcome = McpJsonRpc.parseCallOutcome(
            JSONObject(
                """
                {"content":[
                  {"type":"text","text":"line one"},
                  {"type":"image","data":"..."},
                  {"type":"text","text":"line two"}
                ],"isError":false}
                """.trimIndent()
            )
        )
        assertEquals(false, outcome.isError)
        assertTrue(outcome.text.contains("line one"))
        assertTrue(outcome.text.contains("line two"))
        assertTrue(outcome.text.contains("image content"))

        val failed = McpJsonRpc.parseCallOutcome(
            JSONObject("""{"content":[{"type":"text","text":"boom"}],"isError":true}""")
        )
        assertTrue(failed.isError)
        assertEquals("boom", failed.text)
    }

    @Test
    fun `call outcome falls back to structured content`() {
        val outcome = McpJsonRpc.parseCallOutcome(
            JSONObject("""{"content":[],"structuredContent":{"answer":42}}""")
        )
        assertTrue(outcome.text.contains("42"))
    }
}
