package com.opendroid.ai.core.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Legacy HTTP+SSE dialect against a hand-rolled socket fake: the GET stream
 * announces an endpoint, POSTs are accepted with 202, and JSON-RPC answers
 * are pushed back over the stream — exactly the 2024-11-05 shape the
 * in-repo McpServer's generation speaks.
 */
class McpLegacySseTransportTest {

    /** Reads one HTTP request (head + Content-Length body) from [socket]. Returns the body text. */
    private fun readRequestBody(socket: Socket): String {
        val input = socket.getInputStream()
        val head = readUntil(input, "\r\n\r\n")
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.toInt() ?: 0
        if (length == 0) return ""
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(body, Charsets.UTF_8)
    }

    private fun readUntil(input: InputStream, marker: String): String {
        val target = marker.toByteArray(Charsets.US_ASCII)
        val buffer = StringBuilder()
        var matched = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            buffer.append(byte.toChar())
            matched = if (byte == target[matched].toInt()) matched + 1 else 0
            if (matched == target.size) break
        }
        return buffer.toString()
    }

    @Test
    fun `legacy handshake lists tools and a call round-trips over the stream`() = runBlocking {
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val serverError = AtomicReference<Throwable?>()

        val serverThread = Thread {
            try {
                val getSocket = serverSocket.accept()
                readUntil(getSocket.getInputStream(), "\r\n\r\n")
                val stream = getSocket.getOutputStream()
                fun sendStream(text: String) {
                    synchronized(stream) {
                        stream.write(text.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
                sendStream("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n")
                sendStream("event: endpoint\ndata: /message?session=abc\r\n\r\n")
                while (!serverSocket.isClosed) {
                    val post = serverSocket.accept()
                    val body = readRequestBody(post)
                    post.getOutputStream().apply {
                        write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    post.close()
                    val request = JSONObject(body)
                    if (!request.has("id")) continue // notification: no answer
                    val id = request.getLong("id")
                    val result = when (request.getString("method")) {
                        "initialize" ->
                            """{"protocolVersion":"2024-11-05","capabilities":{},
                               "serverInfo":{"name":"legacy","version":"0.1"}}""".replace("\n", "")
                        "tools/list" ->
                            """{"tools":[{"name":"ping_tool","description":"Ping",
                               "inputSchema":{"type":"object"}}]}""".replace("\n", "")
                        "tools/call" ->
                            """{"content":[{"type":"text","text":"pong"}],"isError":false}"""
                        else -> "{}"
                    }
                    sendStream(
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\r\n\r\n"
                    )
                }
            } catch (error: Exception) {
                if (!serverSocket.isClosed) serverError.set(error)
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val transport = LegacySseTransport(
                baseClient = OkHttpClient(),
                url = "http://127.0.0.1:$port/mcp",
                headers = emptyMap()
            )
            val client = McpClient(transport)
            val tools = withTimeout(15_000) { client.connect("test-version") }

            assertEquals(listOf("ping_tool"), tools.map { it.name })
            assertEquals("legacy 0.1", client.serverInfo)
            assertEquals("2024-11-05", client.negotiatedProtocolVersion)

            val outcome = withTimeout(15_000) { client.callTool("ping_tool", JSONObject()) }
            assertEquals("pong", outcome.text)
            assertEquals(false, outcome.isError)

            client.close()
        } finally {
            serverSocket.close()
        }
        assertNull(serverError.get())
    }
}
