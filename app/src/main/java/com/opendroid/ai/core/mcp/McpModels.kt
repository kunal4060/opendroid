package com.opendroid.ai.core.mcp

import org.json.JSONObject

/**
 * D5 — MCP client (Model Context Protocol), client side.
 *
 * The app already ships an MCP *server* (`core/service/McpServer.kt`) and a
 * Keystore-backed config store (`core/service/McpConfigStore.kt`) that
 * persisted endpoint configs nobody consumed. This package is the missing
 * client: it connects to the remote servers in that store, runs the MCP
 * handshake, and bridges their tools into the agent's action set.
 *
 * Remote servers only. stdio servers cannot run on Android (no `npx`/`uvx`
 * runtimes exist inside the app sandbox) — users expose them over HTTP on
 * their PC/LAN (e.g. via `mcp-proxy`), which the Settings help text states.
 */

/** Wire transport for a configured server. AUTO = Streamable HTTP, legacy fallback. */
enum class McpTransportKind { AUTO, STREAMABLE, LEGACY_SSE }

/** One tool as returned by `tools/list`. [inputSchema] is the raw JSON Schema object. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/** Result of a `tools/call`: the flattened text content plus the MCP error flag. */
data class McpCallOutcome(
    val text: String,
    val isError: Boolean
)

/** Live connection state for one configured server, surfaced in Settings. */
sealed interface McpServerState {
    /** Server toggle is off — never connected, tools withdrawn. */
    data object Disabled : McpServerState

    /** Enabled but no session yet (never connected, or dropped). */
    data object Disconnected : McpServerState

    /** Handshake in flight. */
    data object Connecting : McpServerState

    /** Handshake done and tools listed. */
    data class Connected(val toolCount: Int, val serverInfo: String?) : McpServerState

    /** Last connect/call failed; tools are withdrawn and the agent degrades to built-ins. */
    data class Error(val message: String) : McpServerState
}

/** Error taxonomy for the MCP client — every failure mode is one of these. */
sealed class McpException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Network-level failure: unreachable host, TLS, HTTP status, broken stream. */
    class Transport(message: String, cause: Throwable? = null) : McpException(message, cause)

    /** HTTP status failure (kept distinct so AUTO mode can fall back on 404/405). */
    class Http(val status: Int, message: String) : Transport(message)

    /** The peer spoke invalid JSON-RPC / violated the protocol shape. */
    class Protocol(message: String) : McpException(message)

    /** The server answered with a JSON-RPC error object. */
    class Rpc(val code: Int, rpcMessage: String) : McpException("MCP error $code: $rpcMessage")

    /** No response within the per-call timeout. */
    class Timeout(message: String) : McpException(message)

    /** Argument coercion failed before anything was sent. */
    class Validation(message: String) : McpException(message)
}
