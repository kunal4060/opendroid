package com.opendroid.ai.core.usage

import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.LLMResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventFactoryTest {

    private fun response(
        tokensUsed: Int,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        usageEstimated: Boolean = false
    ) = LLMResponse(
        content = "ok",
        tokensUsed = tokensUsed,
        model = "gpt-4o",
        provider = "OpenAI",
        latencyMs = 1,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        usageEstimated = usageEstimated
    )

    @Test
    fun `reported split is kept exactly as reported`() {
        val event = UsageEventFactory.fromResponse(
            response(tokensUsed = 30, promptTokens = 10, completionTokens = 20)
        )
        assertEquals(10, event.promptTokens)
        assertEquals(20, event.completionTokens)
        assertEquals(0, event.unsplitTokens)
    }

    @Test
    fun `total-only report stays unsplit instead of being allocated`() {
        val event = UsageEventFactory.fromResponse(response(tokensUsed = 42))
        assertNull(event.promptTokens)
        assertNull(event.completionTokens)
        assertEquals(42, event.unsplitTokens)
    }

    @Test
    fun `remainder beyond the reported split stays unsplit`() {
        // e.g. a provider whose total also counts cached/reasoning tokens it
        // did not attribute: the extra must not be assigned to a side.
        val event = UsageEventFactory.fromResponse(
            response(tokensUsed = 50, promptTokens = 10, completionTokens = 20)
        )
        assertEquals(10, event.promptTokens)
        assertEquals(20, event.completionTokens)
        assertEquals(20, event.unsplitTokens)
    }

    @Test
    fun `estimated flag is carried through`() {
        val event = UsageEventFactory.fromResponse(
            response(tokensUsed = 5, promptTokens = 2, completionTokens = 3, usageEstimated = true)
        )
        assertTrue(event.estimated)
    }

    @Test
    fun `stream estimate factory marks the event estimated`() {
        val event = UsageEventFactory.fromStreamEstimate("OpenAI", "gpt-4o", 7, 11)
        assertEquals(7, event.promptTokens)
        assertEquals(11, event.completionTokens)
        assertEquals(0, event.unsplitTokens)
        assertTrue(event.estimated)
    }

    @Test
    fun `openai style usage parses full split`() {
        val usage = JsonParser.parseString(
            """{"prompt_tokens": 12, "completion_tokens": 34, "total_tokens": 46}"""
        ).asJsonObject
        val parsed = parseOpenAiStyleUsage(usage)
        assertEquals(12, parsed.promptTokens)
        assertEquals(34, parsed.completionTokens)
        assertEquals(46, parsed.totalTokens)
    }

    @Test
    fun `openai style usage with total only keeps split unknown`() {
        val usage = JsonParser.parseString("""{"total_tokens": 46}""").asJsonObject
        val parsed = parseOpenAiStyleUsage(usage)
        assertNull(parsed.promptTokens)
        assertNull(parsed.completionTokens)
        assertEquals(46, parsed.totalTokens)
    }

    @Test
    fun `missing usage object yields unknown split and zero total`() {
        val parsed = parseOpenAiStyleUsage(null)
        assertNull(parsed.promptTokens)
        assertNull(parsed.completionTokens)
        assertEquals(0, parsed.totalTokens)
    }

    @Test
    fun `total falls back to split sum when total is absent`() {
        val usage = JsonParser.parseString(
            """{"prompt_tokens": 3, "completion_tokens": 4}"""
        ).asJsonObject
        val parsed = parseOpenAiStyleUsage(usage)
        assertEquals(7, parsed.totalTokens)
    }
}
