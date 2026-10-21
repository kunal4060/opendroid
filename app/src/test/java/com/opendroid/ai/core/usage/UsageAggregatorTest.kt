package com.opendroid.ai.core.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageAggregatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 10, 21)

    private fun millis(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        provider: String = "OpenAI",
        model: String = "gpt-4o",
        prompt: Int? = 10,
        completion: Int? = 20,
        unsplit: Int = 0,
        estimated: Boolean = false,
        on: LocalDate = today
    ) = UsageEvent(
        provider = provider,
        model = model,
        promptTokens = prompt,
        completionTokens = completion,
        unsplitTokens = unsplit,
        estimated = estimated,
        timestampMillis = millis(on)
    )

    @Test
    fun `first event creates a daily row keyed by date provider model`() {
        val rows = UsageAggregator.applyEvent(emptyList(), event())
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(today.toString(), row.date)
        assertEquals(1, row.requests)
        assertEquals(10, row.promptTokens)
        assertEquals(20, row.completionTokens)
        assertEquals(30, row.totalTokens)
    }

    @Test
    fun `events on the same day and model accumulate into one row`() {
        var rows = UsageAggregator.applyEvent(emptyList(), event())
        rows = UsageAggregator.applyEvent(rows, event(prompt = 1, completion = 2))
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().requests)
        assertEquals(11, rows.single().promptTokens)
        assertEquals(22, rows.single().completionTokens)
    }

    @Test
    fun `different day or model creates separate rows`() {
        var rows = UsageAggregator.applyEvent(emptyList(), event())
        rows = UsageAggregator.applyEvent(rows, event(on = today.minusDays(1)))
        rows = UsageAggregator.applyEvent(rows, event(model = "gpt-4o-mini"))
        assertEquals(3, rows.size)
    }

    @Test
    fun `unsplit events are counted and never added to the split`() {
        var rows = UsageAggregator.applyEvent(emptyList(), event(prompt = null, completion = null, unsplit = 42))
        rows = UsageAggregator.applyEvent(rows, event())
        val row = rows.single()
        assertEquals(42, row.unsplitTokens)
        assertEquals(1, row.unsplitRequests)
        assertEquals(10, row.promptTokens)
        assertEquals(72, row.totalTokens)
    }

    @Test
    fun `estimated events are counted separately`() {
        val rows = UsageAggregator.applyEvent(emptyList(), event(estimated = true))
        assertEquals(1, rows.single().estimatedRequests)
    }

    @Test
    fun `range filters keep only the requested window`() {
        val entries = listOf(
            DailyModelUsage(today.toString(), "OpenAI", "gpt-4o", requests = 1),
            DailyModelUsage(today.minusDays(3).toString(), "OpenAI", "gpt-4o", requests = 1),
            DailyModelUsage(today.minusDays(10).toString(), "OpenAI", "gpt-4o", requests = 1),
            DailyModelUsage(today.minusDays(40).toString(), "OpenAI", "gpt-4o", requests = 1)
        )
        assertEquals(1, UsageAggregator.filterRange(entries, UsageRange.TODAY, today).size)
        assertEquals(2, UsageAggregator.filterRange(entries, UsageRange.LAST_7_DAYS, today).size)
        assertEquals(3, UsageAggregator.filterRange(entries, UsageRange.LAST_30_DAYS, today).size)
        assertEquals(4, UsageAggregator.filterRange(entries, UsageRange.ALL_TIME, today).size)
    }

    @Test
    fun `summarize groups across days per provider and model sorted by tokens`() {
        val entries = listOf(
            DailyModelUsage(today.toString(), "OpenAI", "gpt-4o", requests = 1, promptTokens = 10, completionTokens = 5),
            DailyModelUsage(today.minusDays(1).toString(), "OpenAI", "gpt-4o", requests = 2, promptTokens = 20, completionTokens = 5),
            DailyModelUsage(today.toString(), "Groq", "llama-3.3-70b-versatile", requests = 1, promptTokens = 100, completionTokens = 0)
        )
        val summaries = UsageAggregator.summarize(entries)
        assertEquals(2, summaries.size)
        assertEquals("Groq", summaries[0].provider)
        assertEquals(100, summaries[0].totalTokens)
        assertEquals("OpenAI", summaries[1].provider)
        assertEquals(3, summaries[1].requests)
        assertEquals(40, summaries[1].totalTokens)
    }

    @Test
    fun `prune drops rows beyond retention`() {
        val entries = listOf(
            DailyModelUsage(today.minusDays(399).toString(), "OpenAI", "gpt-4o"),
            DailyModelUsage(today.minusDays(401).toString(), "OpenAI", "gpt-4o")
        )
        val pruned = UsageAggregator.prune(entries, today)
        assertEquals(1, pruned.size)
        assertTrue(pruned.single().date == today.minusDays(399).toString())
    }
}
