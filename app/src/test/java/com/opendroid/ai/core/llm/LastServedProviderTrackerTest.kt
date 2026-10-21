package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastServedProviderTrackerTest {

    @Test
    fun `starts empty before any response`() {
        assertNull(LastServedProviderTracker().lastAnsweredBy.value)
    }

    @Test
    fun `records the serving provider`() {
        val tracker = LastServedProviderTracker()
        tracker.record("OpenAI")
        assertEquals("OpenAI", tracker.lastAnsweredBy.value)
    }

    @Test
    fun `canonicalises legacy provider names`() {
        val tracker = LastServedProviderTracker()
        tracker.record("Gemma 4 (On-device)")
        assertEquals("On-Device AI", tracker.lastAnsweredBy.value)
    }

    @Test
    fun `blank names are ignored`() {
        val tracker = LastServedProviderTracker()
        tracker.record("OpenAI")
        tracker.record("")
        assertEquals("OpenAI", tracker.lastAnsweredBy.value)
    }
}
