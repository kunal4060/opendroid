package com.opendroid.ai.core.mcp

/**
 * Incremental Server-Sent Events parser (line-based, per the SSE spec).
 *
 * Both MCP HTTP transports carry JSON-RPC inside SSE: Streamable HTTP may
 * answer a POST with an event stream, and the legacy transport delivers all
 * responses over one long-lived stream. The parser is fed one line at a
 * time and emits complete events on blank lines; comment lines (`: …`,
 * used as keep-alives) and CRLF endings are handled. Pure — unit-tested
 * without any HTTP.
 */
class SseEventParser {

    data class Event(
        val event: String?,
        val data: String,
        val id: String?
    )

    private val dataLines = mutableListOf<String>()
    private var eventType: String? = null
    private var eventId: String? = null

    /** Feeds one line (without its line terminator). Returns an event when a blank line completes one. */
    fun feed(rawLine: String): Event? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> dataLines += value
            "event" -> eventType = value
            "id" -> eventId = value
            // "retry" and unknown fields are ignored per spec.
        }
        return null
    }

    /** Flushes a trailing event when the stream ends without a final blank line. */
    fun flush(): Event? = dispatch()

    private fun dispatch(): Event? {
        if (dataLines.isEmpty() && eventType == null) {
            eventId = null
            return null
        }
        val event = Event(
            event = eventType,
            data = dataLines.joinToString("\n"),
            id = eventId
        )
        dataLines.clear()
        eventType = null
        // Per spec the last-event-id persists across events; keep eventId.
        return event
    }
}
