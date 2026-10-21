package com.opendroid.ai.core.mcp

import org.json.JSONObject

/**
 * One MCP session: handshake state machine over an [McpTransport].
 *
 * `connect()` performs the real protocol exchange — `initialize` →
 * `notifications/initialized` → `tools/list` — adopting the protocol
 * revision the server answers with. After that the session serves
 * `tools/list` refreshes and `tools/call`s until closed or broken.
 */
class McpClient(private val transport: McpTransport) {

    val transportKind: McpTransportKind get() = transport.kind

    /** "name version" of the server, from the initialize result (display only). */
    @Volatile
    var serverInfo: String? = null
        private set

    @Volatile
    var negotiatedProtocolVersion: String? = null
        private set

    suspend fun connect(clientVersion: String): List<McpTool> {
        if (transport is LegacySseTransport) transport.open(OPEN_TIMEOUT_MS)
        val initParams = JSONObject()
            .put("protocolVersion", McpJsonRpc.PROTOCOL_STREAMABLE)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo", JSONObject()
                    .put("name", CLIENT_NAME)
                    .put("version", clientVersion)
            )
        val result = transport.request("initialize", initParams, CONNECT_TIMEOUT_MS)
        negotiatedProtocolVersion =
            result.optString("protocolVersion").ifBlank { McpJsonRpc.PROTOCOL_STREAMABLE }
        transport.protocolVersion = negotiatedProtocolVersion
        serverInfo = result.optJSONObject("serverInfo")?.let { info ->
            listOf(info.optString("name"), info.optString("version"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { null }
        }
        transport.notify("notifications/initialized", null)
        return listTools()
    }

    /**
     * The server's full tool catalogue. `tools/list` is paginated in the
     * protocol: a result carrying `nextCursor` must be followed with
     * `params.cursor` until a page arrives without one — stopping at the
     * first page would silently hide the rest of the catalogue
     * (finding #9). The page bound only guards against a server that
     * hands back cursors forever.
     */
    suspend fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_TOOL_PAGES) {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val result = transport.request("tools/list", params, LIST_TIMEOUT_MS)
            tools += McpJsonRpc.parseTools(result)
            pages++
            cursor = McpJsonRpc.parseNextCursor(result) ?: break
        }
        return tools
    }

    suspend fun callTool(
        toolName: String,
        arguments: JSONObject,
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS
    ): McpCallOutcome {
        val params = JSONObject()
            .put("name", toolName)
            .put("arguments", arguments)
        return McpJsonRpc.parseCallOutcome(transport.request("tools/call", params, timeoutMs))
    }

    suspend fun close() = transport.close()

    companion object {
        const val CLIENT_NAME = "OpenDroid"
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val LIST_TIMEOUT_MS = 20_000L
        const val DEFAULT_CALL_TIMEOUT_MS = 60_000L
        private const val OPEN_TIMEOUT_MS = 15_000L

        /** Sanity bound on `tools/list` pagination (see [listTools]). */
        private const val MAX_TOOL_PAGES = 100
    }
}
