package com.opendroid.ai.core.mcp

import android.net.Uri
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.service.McpConfigStore
import com.opendroid.ai.core.service.McpEndpointConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the live MCP sessions: one cached [McpClient] per enabled server,
 * created lazily (first tool use, explicit Test connection, or the tool
 * bridge's startup pass) and dropped on failure, disable, or removal.
 *
 * Connection state is exposed as a [StateFlow] so Settings can render the
 * real handshake state — a server whose handshake fails lands in
 * [McpServerState.Error] with its tools withdrawn; the agent simply
 * degrades to built-in actions.
 */
@Singleton
class McpClientManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val configStore: McpConfigStore
) {

    private data class Session(val client: McpClient, val tools: List<McpTool>)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()

    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val states: StateFlow<Map<String, McpServerState>> = _states.asStateFlow()

    /** Tools of the live session for [serverName], or empty when not connected. */
    fun toolsFor(serverName: String): List<McpTool> = sessions[serverName]?.tools.orEmpty()

    fun stateFor(serverName: String): McpServerState =
        _states.value[serverName] ?: McpServerState.Disconnected

    /**
     * Connects (or returns the live session's tools) for [config]. On AUTO
     * transport, a Streamable `initialize` refused with 404/405 retries as
     * legacy HTTP+SSE — the dialect split the research calls out.
     */
    suspend fun connect(config: McpEndpointConfig): List<McpTool> {
        requireEnabled(config)
        sessions[config.name]?.let { return it.tools }
        val lock = connectLocks.getOrPut(config.name) { Mutex() }
        return lock.withLock {
            sessions[config.name]?.let { return@withLock it.tools }
            setState(config.name, McpServerState.Connecting)
            try {
                val (client, tools) = openSession(config)
                sessions[config.name] = Session(client, tools)
                setState(
                    config.name,
                    McpServerState.Connected(toolCount = tools.size, serverInfo = client.serverInfo)
                )
                tools
            } catch (error: Exception) {
                setState(config.name, McpServerState.Error(error.userMessage()))
                throw error
            }
        }
    }

    /** Re-runs the handshake and refreshes the tool list for [config]. */
    suspend fun reconnect(config: McpEndpointConfig): List<McpTool> {
        disconnect(config.name)
        return connect(config)
    }

    /** Drops the session (if any). State follows the config's enabled flag. */
    suspend fun disconnect(serverName: String) {
        sessions.remove(serverName)?.let { session ->
            try {
                session.client.close()
            } catch (_: Exception) {
                // Closing a broken session must never throw into callers.
            }
        }
        val stillEnabled = configStore.list().any { it.name == serverName && it.enabled }
        setState(serverName, if (stillEnabled) McpServerState.Disconnected else McpServerState.Disabled)
    }

    /** Marks a server disabled without touching any session bookkeeping beyond the drop. */
    suspend fun onServerDisabled(serverName: String) {
        sessions.remove(serverName)?.let { session ->
            try {
                session.client.close()
            } catch (_: Exception) {
            }
        }
        setState(serverName, McpServerState.Disabled)
    }

    /**
     * Calls [toolName] on [serverName], connecting on first use. The config
     * is re-read and re-checked here so a call can never slip through after
     * the server was disabled or the tool was unticked in Settings.
     */
    suspend fun callTool(
        serverName: String,
        toolName: String,
        arguments: JSONObject,
        timeoutMs: Long = McpClient.DEFAULT_CALL_TIMEOUT_MS
    ): McpCallOutcome {
        val config = configStore.list().firstOrNull { it.name == serverName }
            ?: throw McpException.Transport("MCP server '$serverName' is no longer configured")
        requireEnabled(config)
        if (!config.isToolEnabled(toolName)) {
            throw McpException.Validation("Tool '$toolName' is disabled for MCP server '$serverName'")
        }
        connect(config)
        val session = sessions[serverName]
            ?: throw McpException.Transport("MCP server '$serverName' is not connected")
        return try {
            session.client.callTool(toolName, arguments, timeoutMs)
        } catch (error: McpException) {
            // A broken session is dropped: state shows the error and the
            // bridge withdraws the tools on its next refresh.
            sessions.remove(serverName)
            setState(serverName, McpServerState.Error(error.userMessage()))
            throw error
        }
    }

    private suspend fun openSession(config: McpEndpointConfig): Pair<McpClient, List<McpTool>> {
        enforceCleartextPolicy(config)
        val kinds = when (config.transport) {
            McpTransportKind.STREAMABLE -> listOf(McpTransportKind.STREAMABLE)
            McpTransportKind.LEGACY_SSE -> listOf(McpTransportKind.LEGACY_SSE)
            McpTransportKind.AUTO -> listOf(McpTransportKind.STREAMABLE, McpTransportKind.LEGACY_SSE)
        }
        var lastHttpError: McpException.Http? = null
        for (kind in kinds) {
            val transport = when (kind) {
                McpTransportKind.STREAMABLE ->
                    StreamableHttpTransport(okHttpClient, config.url, config.headers)
                McpTransportKind.LEGACY_SSE ->
                    LegacySseTransport(okHttpClient, config.url, config.headers)
                McpTransportKind.AUTO ->
                    throw IllegalStateException("AUTO is resolved to concrete transports above")
            }
            val client = McpClient(transport)
            try {
                val tools = client.connect(BuildConfig.VERSION_NAME)
                return client to tools
            } catch (error: McpException.Http) {
                client.close()
                lastHttpError = error
                // Only a refused initialize (404/405) suggests the other dialect.
                if (error.status != 404 && error.status != 405) throw error
            } catch (error: Exception) {
                client.close()
                throw error
            }
        }
        throw lastHttpError ?: McpException.Transport("Could not connect to MCP server at ${config.url}")
    }

    private fun requireEnabled(config: McpEndpointConfig) {
        if (!config.enabled) throw McpException.Validation("MCP server '${config.name}' is disabled")
    }

    /**
     * Cleartext policy — the same one A10's local-model path ships: the
     * platform network-security config already permits plain HTTP only to
     * localhost-class hosts. On top of that, this client refuses ANY plain
     * HTTP unless the user ticked the per-server "allow insecure" switch,
     * so a LAN server is a deliberate, warned choice — never a silent one.
     */
    private fun enforceCleartextPolicy(config: McpEndpointConfig) {
        val uri = Uri.parse(config.url)
        if (uri.scheme.equals("https", ignoreCase = true)) return
        if (!config.allowInsecureLocal) {
            throw McpException.Transport(
                "Plain HTTP is off for '${config.name}'. Enable \"Allow insecure local server\" " +
                    "in its settings if this is a server on your own network."
            )
        }
    }

    private fun setState(serverName: String, state: McpServerState) {
        _states.value = _states.value + (serverName to state)
    }

    private fun Exception.userMessage(): String = when (this) {
        is McpException -> message ?: "MCP error"
        else -> message ?: javaClass.simpleName
    }
}
