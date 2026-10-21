package com.opendroid.ai.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNamesTest {

    @Test
    fun `names are upper snake with the MCP prefix`() {
        assertEquals(
            "MCP_MY_SERVER_GET_FILE",
            McpNames.baseActionName("My Server", "get-file")
        )
        assertEquals(
            "MCP_CI_CHECK_BUILD_STATUS",
            McpNames.baseActionName("ci", "check_build status")
        )
    }

    @Test
    fun `sanitising collapses separators and trims`() {
        assertEquals("A_B", McpNames.sanitizeSegment(" a--b__ "))
        assertEquals("X", McpNames.sanitizeSegment("!!!"))
        assertEquals("SERVER2", McpNames.sanitizeSegment("server2"))
    }

    @Test
    fun `colliding tool names get a stable hash suffix`() {
        val assigned = McpNames.assignActionNames(
            "srv",
            listOf("get-file", "get_file", "get file")
        )
        assertEquals("MCP_SRV_GET_FILE", assigned["get-file"])
        val second = assigned.getValue("get_file")
        val third = assigned.getValue("get file")
        assertTrue(second.startsWith("MCP_SRV_GET_FILE_"))
        assertTrue(third.startsWith("MCP_SRV_GET_FILE_"))
        assertTrue(second != third)
        // Deterministic: assigning again yields the same names.
        assertEquals(assigned, McpNames.assignActionNames("srv", listOf("get-file", "get_file", "get file")))
    }

    @Test
    fun `distinct tools keep distinct plain names`() {
        val assigned = McpNames.assignActionNames("srv", listOf("read", "write"))
        assertEquals(mapOf("read" to "MCP_SRV_READ", "write" to "MCP_SRV_WRITE"), assigned)
    }

    @Test
    fun `isMcpActionName matches the namespace only`() {
        assertTrue(McpNames.isMcpActionName("MCP_SRV_READ"))
        assertFalse(McpNames.isMcpActionName("READ_FILE"))
        assertFalse(McpNames.isMcpActionName("MCP"))
    }
}
