package com.opendroid.ai.core.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One MCP session's wire. Implementations own request-id correlation: the
 * caller asks for a method and gets its `result` object back (or a typed
 * [McpException]). All calls are made from IO contexts; implementations use
 * the house idiom of blocking OkHttp calls bounded by per-call timeouts.
 */
interface McpTransport {
    val kind: McpTransportKind

    /** Server-issued session id (Streamable HTTP), null for stateless/legacy sessions. */
    val sessionId: String?

    /** Negotiated protocol revision, set by [McpClient] after `initialize`. */
    var protocolVersion: String?

    suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject

    suspend fun notify(method: String, params: JSONObject?)

    suspend fun close()
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal fun IOException.toMcpException(context: String): McpException = when (this) {
    is InterruptedIOException -> McpException.Timeout("$context timed out")
    else -> McpException.Transport("$context failed: ${message ?: javaClass.simpleName}", this)
}

internal fun Request.Builder.applyMcpHeaders(
    headers: Map<String, String>,
    sessionId: String?,
    protocolVersion: String?
): Request.Builder {
    headers.forEach { (name, value) -> header(name, value) }
    if (sessionId != null) header("Mcp-Session-Id", sessionId)
    if (protocolVersion != null) header("MCP-Protocol-Version", protocolVersion)
    return this
}

/**
 * Streamable HTTP transport (spec 2025-03-26 / 2025-06-18).
 *
 * Every request is its own POST. The server answers either a plain JSON body
 * or an SSE stream carrying the response — both are accepted. The
 * `Mcp-Session-Id` response header is captured when the server issues one
 * (many servers are stateless and never do — the header is simply absent)
 * and echoed on later requests; closing the session sends a best-effort
 * HTTP DELETE.
 */
class StreamableHttpTransport(
    private val baseClient: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>
) : McpTransport {

    override val kind: McpTransportKind = McpTransportKind.STREAMABLE

    @Volatile
    override var sessionId: String? = null
        private set

    @Volatile
    override var protocolVersion: String? = null

    private val ids = AtomicLong(0)

    override suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        val id = ids.incrementAndGet()
        val body = McpJsonRpc.buildRequest(id, method, params).toString()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .applyMcpHeaders(headers, sessionId, protocolVersion)
                .header("Accept", "application/json, text/event-stream")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val call = baseClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            try {
                call.execute().use { response -> readResponse(response, id, method) }
            } catch (error: IOException) {
                throw error.toMcpException("MCP $method request")
            }
        }
    }

    private fun readResponse(response: Response, id: Long, method: String): JSONObject {
        // Capture a session id whenever the server (re)issues one.
        response.header("Mcp-Session-Id")?.let { sessionId = it }
        if (!response.isSuccessful) {
            throw McpException.Http(response.code, "MCP server answered HTTP ${response.code} for $method")
        }
        val contentType = response.header("Content-Type").orEmpty()
        val body = response.body ?: throw McpException.Protocol("MCP $method response had no body")
        if (contentType.contains("text/event-stream")) {
            val parser = SseEventParser()
            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                val event = parser.feed(line) ?: continue
                val message = McpJsonRpc.parseMessage(event.data) ?: continue
                when (message) {
                    is McpJsonRpc.Message.Result -> if (message.id == id) return message.result
                    is McpJsonRpc.Message.Error -> if (message.id == id) {
                        throw McpException.Rpc(message.code, message.message)
                    }
                    // Server-initiated requests/notifications are not served in v1.
                    is McpJsonRpc.Message.ServerMessage -> Unit
                }
            }
            parser.flush()?.let { event ->
                val message = McpJsonRpc.parseMessage(event.data)
                if (message is McpJsonRpc.Message.Result && message.id == id) return message.result
                if (message is McpJsonRpc.Message.Error && message.id == id) {
                    throw McpException.Rpc(message.code, message.message)
                }
            }
            throw McpException.Protocol("MCP $method stream ended without a response for request $id")
        }
        val text = body.string()
        return when (val message = McpJsonRpc.parseMessage(text)) {
            is McpJsonRpc.Message.Result -> {
                if (message.id != id) {
                    throw McpException.Protocol("MCP $method response id ${message.id} does not match request $id")
                }
                message.result
            }
            is McpJsonRpc.Message.Error -> throw McpException.Rpc(message.code, message.message)
            else -> throw McpException.Protocol("MCP $method response was not a JSON-RPC result")
        }
    }

    override suspend fun notify(method: String, params: JSONObject?) {
        val body = McpJsonRpc.buildNotification(method, params).toString()
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .applyMcpHeaders(headers, sessionId, protocolVersion)
                .header("Accept", "application/json, text/event-stream")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                baseClient.newBuilder()
                    .callTimeout(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { /* 202 Accepted expected; any 2xx is fine, body ignored */ }
            } catch (error: IOException) {
                throw error.toMcpException("MCP $method notification")
            }
        }
    }

    override suspend fun close() {
        val session = sessionId ?: return
        sessionId = null
        // Best-effort session termination (spec: HTTP DELETE). Stateless
        // servers never issued an id, so there is nothing to terminate.
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .applyMcpHeaders(headers, session, protocolVersion)
                .delete()
                .build()
            try {
                baseClient.newBuilder()
                    .callTimeout(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { }
            } catch (_: Exception) {
                // Termination is best-effort by design.
            }
        }
    }

    private companion object {
        const val NOTIFY_TIMEOUT_MS = 10_000L
    }
}

/**
 * Legacy HTTP+SSE transport (spec 2024-11-05) — the dialect generation the
 * in-repo `McpServer` belongs to and many self-hosted servers still speak.
 *
 * The client holds one long-lived GET stream open. The server first sends
 * an `endpoint` event naming the POST URL; requests are POSTed there (202
 * Accepted) and their responses arrive back over the stream as `message`
 * events, correlated by JSON-RPC id.
 */
class LegacySseTransport(
    private val baseClient: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>
) : McpTransport {

    override val kind: McpTransportKind = McpTransportKind.LEGACY_SSE
    override val sessionId: String? = null

    @Volatile
    override var protocolVersion: String? = null

    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<McpJsonRpc.Message>>()

    /**
     * The current stream generation's announced POST endpoint. Swapped
     * for a fresh deferred whenever a stream dies (see [onStreamDead]),
     * so the next request re-opens a stream and awaits the NEW endpoint
     * instead of POSTing into a session whose stream is gone.
     */
    @Volatile
    private var endpoint = CompletableDeferred<String>()

    @Volatile
    private var streamCall: Call? = null

    @Volatile
    private var closed = false

    /** Opens the GET stream. Idempotent; must complete before requests are accepted. */
    suspend fun open(openTimeoutMs: Long) {
        if (streamCall != null) return
        val request = Request.Builder()
            .url(url)
            .applyMcpHeaders(headers, null, null)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        // The stream is the session: no read/call timeout may kill it.
        val streamClient = baseClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = streamClient.newCall(request)
        streamCall = call
        // Capture THIS generation's endpoint: onStreamDead may swap the
        // field for a fresh deferred before the await below runs, and the
        // opener must observe its own stream's fate (announced endpoint,
        // or the stream's error), never the successor's.
        val announcedEndpoint = endpoint
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                onStreamDead(call, e.toMcpException("MCP legacy stream"))

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onStreamDead(call, McpException.Http(it.code, "MCP legacy stream answered HTTP ${it.code}"))
                        return
                    }
                    try {
                        val parser = SseEventParser()
                        val source = it.body!!.source()
                        while (!closed) {
                            val line = source.readUtf8Line() ?: break
                            val event = parser.feed(line) ?: continue
                            handleEvent(event)
                        }
                        parser.flush()?.let(::handleEvent)
                        if (!closed) onStreamDead(call, McpException.Transport("MCP legacy stream ended"))
                    } catch (error: Exception) {
                        if (!closed) {
                            onStreamDead(call, McpException.Transport("MCP legacy stream failed: ${error.message}", error))
                        }
                    }
                }
            }
        })
        val resolved = withTimeoutOrNull(openTimeoutMs) { announcedEndpoint.await() }
        if (resolved == null) {
            close()
            throw McpException.Timeout("MCP server did not announce an endpoint within ${openTimeoutMs}ms")
        }
    }

    private fun handleEvent(event: SseEventParser.Event) {
        when (event.event) {
            "endpoint" -> {
                val base = url.toHttpUrlOrNull()
                val resolved = base?.resolve(event.data.trim())
                if (resolved != null) endpoint.complete(resolved.toString())
                else endpoint.completeExceptionally(
                    McpException.Protocol("MCP endpoint event was not a resolvable URL: ${event.data}")
                )
            }
            // "message" is the spec name; a bare data event is tolerated too.
            "message", null -> {
                val message = McpJsonRpc.parseMessage(event.data) ?: return
                val id = message.id ?: return // notifications: not served in v1
                pending.remove(id)?.complete(message)
            }
        }
    }

    private fun failAll(error: McpException) {
        if (!endpoint.isCompleted) endpoint.completeExceptionally(error)
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(error) }
    }

    /**
     * The current stream generation died (failure, HTTP error, clean end,
     * or a read error). In-flight work fails fast with [error] via
     * [failAll]; then — unless the transport was closed or a successor
     * stream is already installed — the dead call and its completed
     * endpoint are cleared so the next request/notify re-opens a fresh
     * stream instead of POSTing to a stale endpoint and waiting out the
     * full call timeout for a response that can never arrive (finding #2).
     */
    private fun onStreamDead(call: Call, error: McpException) {
        failAll(error)
        if (!closed && streamCall === call) {
            streamCall = null
            endpoint = CompletableDeferred()
        }
    }

    override suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        if (closed) throw McpException.Transport("MCP legacy transport is closed")
        if (streamCall == null) open(OPEN_TIMEOUT_MS)
        val endpointUrl = endpoint.await()
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<McpJsonRpc.Message>()
        pending[id] = deferred
        try {
            post(endpointUrl, McpJsonRpc.buildRequest(id, method, params).toString(), timeoutMs)
            return when (val message = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw McpException.Timeout("MCP $method did not answer within ${timeoutMs}ms")) {
                is McpJsonRpc.Message.Result -> message.result
                is McpJsonRpc.Message.Error -> throw McpException.Rpc(message.code, message.message)
                is McpJsonRpc.Message.ServerMessage ->
                    throw McpException.Protocol("Unexpected server message answering $method")
            }
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun notify(method: String, params: JSONObject?) {
        if (closed) return
        if (streamCall == null) open(OPEN_TIMEOUT_MS)
        post(endpoint.await(), McpJsonRpc.buildNotification(method, params).toString(), NOTIFY_TIMEOUT_MS)
    }

    private suspend fun post(endpointUrl: String, body: String, timeoutMs: Long) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpointUrl)
                .applyMcpHeaders(headers, null, protocolVersion)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                baseClient.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            throw McpException.Http(response.code, "MCP POST answered HTTP ${response.code}")
                        }
                    }
            } catch (error: IOException) {
                throw error.toMcpException("MCP POST")
            }
        }
    }

    override suspend fun close() {
        closed = true
        streamCall?.cancel()
        streamCall = null
        failAll(McpException.Transport("MCP legacy transport closed"))
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 15_000L
        const val NOTIFY_TIMEOUT_MS = 10_000L
    }
}
