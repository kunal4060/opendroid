package com.opendroid.ai.core.mcp

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Streamable-HTTP dialect against MockWebServer: the dispatcher plays a
 * stateless-looking MCP server that issues a session id at initialize,
 * answers tools/list as JSON, tools/call as an SSE stream, and one call as
 * a JSON-RPC error — the three response shapes the client must accept.
 */
class McpStreamableHttpTransportTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun newServer(): MockWebServer = MockWebServer().also {
        servers += it
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun jsonResponse(body: String, sessionId: Boolean = false): MockResponse {
        val builder = MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
        if (sessionId) builder.setHeader("Mcp-Session-Id", "sess-42")
        return builder.build()
    }

    private fun mcpDispatcher(callAsSse: Boolean, failCall: Boolean = false) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body?.utf8().orEmpty()
            return when {
                body.contains("notifications/initialized") ->
                    MockResponse.Builder().code(202).build()
                body.contains("\"initialize\"") -> jsonResponse(
                    """
                    {"jsonrpc":"2.0","id":1,"result":{
                      "protocolVersion":"2025-03-26",
                      "capabilities":{"tools":{}},
                      "serverInfo":{"name":"test-server","version":"1.0"}
                    }}
                    """.trimIndent(),
                    sessionId = true
                )
                body.contains("\"tools/list\"") -> jsonResponse(
                    """
                    {"jsonrpc":"2.0","id":2,"result":{"tools":[
                      {"name":"echo","description":"Echoes text",
                       "inputSchema":{"type":"object",
                         "properties":{"text":{"type":"string"}},
                         "required":["text"]}}
                    ]}}
                    """.trimIndent()
                )
                body.contains("\"tools/call\"") && failCall -> jsonResponse(
                    """
                    {"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Invalid params"}}
                    """.trimIndent()
                )
                body.contains("\"tools/call\"") && callAsSse -> MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .body(
                        "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{" +
                            "\"content\":[{\"type\":\"text\",\"text\":\"echo: hello\"}]," +
                            "\"isError\":false}}\n\n"
                    )
                    .build()
                body.contains("\"tools/call\"") -> jsonResponse(
                    """
                    {"jsonrpc":"2.0","id":3,"result":{
                      "content":[{"type":"text","text":"echo: hello"}],"isError":false}}
                    """.trimIndent()
                )
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun connectedClient(server: MockWebServer): McpClient {
        val transport = StreamableHttpTransport(
            baseClient = OkHttpClient(),
            url = server.url("/mcp").toString(),
            headers = mapOf("Authorization" to "Bearer test-token")
        )
        return McpClient(transport)
    }

    @Test
    fun `handshake lists tools and later requests carry session and protocol headers`() = runBlocking {
        val server = newServer()
        server.dispatcher = mcpDispatcher(callAsSse = false)
        val client = connectedClient(server)

        val tools = client.connect("test-version")

        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals("test-server 1.0", client.serverInfo)
        assertEquals("2025-03-26", client.negotiatedProtocolVersion)

        val initialize = server.takeRequest()
        assertEquals("Bearer test-token", initialize.headers["Authorization"])
        assertTrue(initialize.body?.utf8().orEmpty().contains("\"initialize\""))
        server.takeRequest() // notifications/initialized
        val list = server.takeRequest()
        assertTrue(list.body?.utf8().orEmpty().contains("\"tools/list\""))
        assertEquals("sess-42", list.headers["Mcp-Session-Id"])
        assertEquals("2025-03-26", list.headers["MCP-Protocol-Version"])
        client.close()
    }

    @Test
    fun `tools call answered over an SSE stream is parsed`() = runBlocking {
        val server = newServer()
        server.dispatcher = mcpDispatcher(callAsSse = true)
        val client = connectedClient(server)
        client.connect("test-version")

        val outcome = client.callTool("echo", JSONObject().put("text", "hello"))

        assertEquals(false, outcome.isError)
        assertEquals("echo: hello", outcome.text)
        client.close()
    }

    @Test
    fun `tools call answered as plain JSON is parsed`() = runBlocking {
        val server = newServer()
        server.dispatcher = mcpDispatcher(callAsSse = false)
        val client = connectedClient(server)
        client.connect("test-version")

        val outcome = client.callTool("echo", JSONObject().put("text", "hello"))

        assertEquals("echo: hello", outcome.text)
        client.close()
    }

    @Test
    fun `a JSON-RPC error frame becomes an Rpc exception`() {
        val server = newServer()
        server.dispatcher = mcpDispatcher(callAsSse = false, failCall = true)
        val client = connectedClient(server)
        runBlocking { client.connect("test-version") }

        val error = assertThrows(McpException.Rpc::class.java) {
            runBlocking { client.callTool("echo", JSONObject().put("text", "hello")) }
        }
        assertEquals(-32602, error.code)
        assertTrue(error.message.orEmpty().contains("Invalid params"))
    }

    @Test
    fun `a refused initialize surfaces the HTTP status for dialect fallback`() {
        val server = newServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(405).build()
        }
        val transport = StreamableHttpTransport(OkHttpClient(), server.url("/mcp").toString(), emptyMap())

        val error = assertThrows(McpException.Http::class.java) {
            runBlocking { transport.request("initialize", JSONObject(), 5_000) }
        }
        assertEquals(405, error.status)
    }

    @Test
    fun `an unreachable server is a transport failure not a hang`() {
        val transport = StreamableHttpTransport(
            OkHttpClient(),
            "http://127.0.0.1:1/mcp",
            emptyMap()
        )
        assertThrows(McpException.Transport::class.java) {
            runBlocking { transport.request("initialize", JSONObject(), 5_000) }
        }
    }
}
