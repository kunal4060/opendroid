package com.opendroid.ai.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventParserTest {

    @Test
    fun `a simple event dispatches on the blank line`() {
        val parser = SseEventParser()
        assertNull(parser.feed("event: message"))
        assertNull(parser.feed("data: {\"a\":1}"))
        val event = parser.feed("")
        assertEquals("message", event?.event)
        assertEquals("{\"a\":1}", event?.data)
    }

    @Test
    fun `multi-line data joins with newlines`() {
        val parser = SseEventParser()
        parser.feed("data: first")
        parser.feed("data: second")
        val event = parser.feed("")
        assertEquals("first\nsecond", event?.data)
    }

    @Test
    fun `comments and keep-alives are ignored`() {
        val parser = SseEventParser()
        assertNull(parser.feed(": ping"))
        assertNull(parser.feed(""))
        parser.feed("data: x")
        assertEquals("x", parser.feed("")?.data)
    }

    @Test
    fun `crlf endings and a leading space in values are handled`() {
        val parser = SseEventParser()
        parser.feed("event: endpoint\r")
        parser.feed("data: /message?session=1\r")
        val event = parser.feed("\r")
        assertEquals("endpoint", event?.event)
        assertEquals("/message?session=1", event?.data)
    }

    @Test
    fun `flush emits a trailing event without a final blank line`() {
        val parser = SseEventParser()
        parser.feed("data: tail")
        assertEquals("tail", parser.flush()?.data)
        assertNull(parser.flush())
    }

    @Test
    fun `back to back events parse independently`() {
        val parser = SseEventParser()
        parser.feed("data: one")
        val first = parser.feed("")
        parser.feed("event: endpoint")
        parser.feed("data: two")
        val second = parser.feed("")
        assertEquals("one", first?.data)
        assertEquals("endpoint", second?.event)
        assertEquals("two", second?.data)
    }
}
