package com.opendroid.ai.core.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal JSON-RPC 2.0 framing for the MCP client, mirroring the wire style
 * of the in-repo `McpServer` (org.json, no new serialization stack).
 *
 * Pure functions — the transport feeds raw texts in and takes framed
 * requests out, which keeps every framing decision unit-testable.
 */
object McpJsonRpc {

    const val VERSION = "2.0"

    /** MCP protocol revisions this client can speak, newest first. */
    const val PROTOCOL_STREAMABLE = "2025-03-26"
    const val PROTOCOL_LEGACY = "2024-11-05"

    fun buildRequest(id: Long, method: String, params: JSONObject?): JSONObject =
        JSONObject()
            .put("jsonrpc", VERSION)
            .put("id", id)
            .put("method", method)
            .apply { if (params != null) put("params", params) }

    fun buildNotification(method: String, params: JSONObject?): JSONObject =
        JSONObject()
            .put("jsonrpc", VERSION)
            .put("method", method)
            .apply { if (params != null) put("params", params) }

    /** A parsed inbound JSON-RPC message. */
    sealed interface Message {
        val id: Long?

        /** A `result` for one of our requests. */
        data class Result(override val id: Long, val result: JSONObject) : Message

        /** An `error` for one of our requests. */
        data class Error(override val id: Long, val code: Int, val message: String) : Message

        /**
         * A server→client request or notification (ping, list_changed, …).
         * v1 does not serve server-initiated requests; callers ignore these.
         */
        data class ServerMessage(override val id: Long?, val method: String?) : Message
    }

    /**
     * Parses one raw text as a JSON-RPC message. Returns null when the text is
     * not a JSON object at all (SSE keep-alive junk, partial frames).
     */
    fun parseMessage(text: String): Message? {
        val json = try {
            JSONObject(text.trim())
        } catch (_: Exception) {
            return null
        }
        val hasId = json.has("id") && !json.isNull("id")
        val id = if (hasId) json.optLong("id", Long.MIN_VALUE) else null
        val validId = id?.takeIf { it != Long.MIN_VALUE }
        return when {
            json.has("result") -> {
                val resultId = validId ?: return null
                Message.Result(resultId, json.optJSONObject("result") ?: JSONObject())
            }
            json.has("error") -> {
                val errorId = validId ?: return null
                val error = json.optJSONObject("error")
                Message.Error(
                    id = errorId,
                    code = error?.optInt("code", -32603) ?: -32603,
                    message = error?.optString("message", "MCP server error") ?: "MCP server error"
                )
            }
            json.has("method") -> Message.ServerMessage(validId, json.optString("method"))
            else -> null
        }
    }

    /** Extracts the tool list from a `tools/list` result object. */
    fun parseTools(result: JSONObject): List<McpTool> {
        val tools = result.optJSONArray("tools") ?: JSONArray()
        return (0 until tools.length()).mapNotNull { index ->
            val entry = tools.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name")
            if (name.isBlank()) return@mapNotNull null
            McpTool(
                name = name,
                description = entry.optString("description"),
                inputSchema = entry.optJSONObject("inputSchema") ?: JSONObject()
                    .put("type", "object")
            )
        }
    }

    /**
     * Flattens a `tools/call` result into display text. Text parts join with
     * newlines; non-text parts are summarised, never silently dropped;
     * `structuredContent` is appended when there is no text at all.
     */
    fun parseCallOutcome(result: JSONObject): McpCallOutcome {
        val isError = result.optBoolean("isError", false)
        val parts = mutableListOf<String>()
        val content = result.optJSONArray("content") ?: JSONArray()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> parts += item.optString("text")
                "image" -> parts += "[image content returned by the tool]"
                "audio" -> parts += "[audio content returned by the tool]"
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val text = resource?.optString("text").orEmpty()
                    if (text.isNotBlank()) parts += text
                    else parts += "[embedded resource: ${resource?.optString("uri") ?: "unknown"}]"
                }
                "resource_link" -> parts += "[resource link: ${item.optString("uri")}]"
                else -> parts += "[${item.optString("type")} content returned by the tool]"
            }
        }
        if (parts.isEmpty()) {
            result.optJSONObject("structuredContent")?.let { parts += it.toString() }
        }
        return McpCallOutcome(
            text = parts.joinToString("\n").ifBlank { "(tool returned no content)" },
            isError = isError
        )
    }
}
