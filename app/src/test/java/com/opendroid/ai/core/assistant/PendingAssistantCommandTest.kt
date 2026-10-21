package com.opendroid.ai.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingAssistantCommandTest {

    @Test
    fun `offered query is delivered exactly once`() {
        val slot = PendingAssistantCommand()
        slot.offer("what's the weather")
        assertEquals("what's the weather", slot.pendingQuery.value)
        assertEquals("what's the weather", slot.consume())
        assertNull(slot.pendingQuery.value)
        assertNull(slot.consume())
    }

    @Test
    fun `blank offers are ignored`() {
        val slot = PendingAssistantCommand()
        slot.offer("   ")
        assertNull(slot.pendingQuery.value)
    }

    @Test
    fun `offers are trimmed`() {
        val slot = PendingAssistantCommand()
        slot.offer("  set a timer  ")
        assertEquals("set a timer", slot.consume())
    }

    @Test
    fun `a newer invocation replaces an undelivered older one`() {
        val slot = PendingAssistantCommand()
        slot.offer("first")
        slot.offer("second")
        assertEquals("second", slot.consume())
    }
}
