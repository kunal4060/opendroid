package com.opendroid.ai.core.usage

import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * Usage accumulated for one calendar day, one provider and one model.
 * This is the persisted shape (B3): daily aggregates keep the store small
 * while still answering today / 7-day / 30-day / all-time questions exactly.
 *
 * [promptTokens] and [completionTokens] sum only the counts providers
 * actually split. Tokens reported as a bare total accumulate in
 * [unsplitTokens]; [unsplitRequests] counts how many calls contributed
 * them, so the UI can disclose that part of the total has no split.
 */
@Serializable
data class DailyModelUsage(
    val date: String, // ISO-8601 local date, yyyy-MM-dd
    val provider: String,
    val model: String,
    val requests: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val unsplitTokens: Long = 0,
    val estimatedRequests: Long = 0,
    val unsplitRequests: Long = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens + unsplitTokens
}

enum class UsageRange(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("7 days"),
    LAST_30_DAYS("30 days"),
    ALL_TIME("All time")
}

/** One provider+model line of a dashboard summary, across the chosen range. */
data class ModelUsageSummary(
    val provider: String,
    val model: String,
    val requests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val unsplitTokens: Long,
    val estimatedRequests: Long,
    val unsplitRequests: Long
) {
    val totalTokens: Long get() = promptTokens + completionTokens + unsplitTokens
}

object UsageAggregator {

    /** Local date a timestamp belongs to; usage is a per-day household stat. */
    fun dateOf(timestampMillis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(timestampMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()

    /**
     * Folds [event] into [entries], returning a new list. Rows are keyed by
     * (date, provider, model); the provider name is canonicalised by the
     * caller so aliases cannot split one provider into two rows.
     */
    fun applyEvent(entries: List<DailyModelUsage>, event: UsageEvent): List<DailyModelUsage> {
        val date = dateOf(event.timestampMillis).toString()
        val index = entries.indexOfFirst {
            it.date == date && it.provider == event.provider && it.model == event.model
        }
        val hasSplit = event.promptTokens != null || event.completionTokens != null
        val updated = if (index >= 0) {
            val row = entries[index]
            row.copy(
                requests = row.requests + 1,
                promptTokens = row.promptTokens + (event.promptTokens ?: 0),
                completionTokens = row.completionTokens + (event.completionTokens ?: 0),
                unsplitTokens = row.unsplitTokens + event.unsplitTokens,
                estimatedRequests = row.estimatedRequests + if (event.estimated) 1 else 0,
                unsplitRequests = row.unsplitRequests + if (hasSplit) 0 else 1
            )
        } else {
            DailyModelUsage(
                date = date,
                provider = event.provider,
                model = event.model,
                requests = 1,
                promptTokens = (event.promptTokens ?: 0).toLong(),
                completionTokens = (event.completionTokens ?: 0).toLong(),
                unsplitTokens = event.unsplitTokens.toLong(),
                estimatedRequests = if (event.estimated) 1L else 0L,
                unsplitRequests = if (hasSplit) 0L else 1L
            )
        }
        return if (index >= 0) {
            entries.toMutableList().also { it[index] = updated }
        } else {
            entries + updated
        }
    }

    /** Rows whose day falls inside [range] relative to [today]. */
    fun filterRange(entries: List<DailyModelUsage>, range: UsageRange, today: LocalDate): List<DailyModelUsage> {
        if (range == UsageRange.ALL_TIME) return entries
        val earliest = when (range) {
            UsageRange.TODAY -> today
            UsageRange.LAST_7_DAYS -> today.minusDays(6)
            UsageRange.LAST_30_DAYS -> today.minusDays(29)
            UsageRange.ALL_TIME -> return entries
        }
        // ISO date strings order lexicographically, so range checks are exact.
        val from = earliest.toString()
        val to = today.toString()
        return entries.filter { it.date >= from && it.date <= to }
    }

    /** Groups daily rows into one summary line per provider+model, biggest first. */
    fun summarize(entries: List<DailyModelUsage>): List<ModelUsageSummary> =
        entries
            .groupBy { it.provider to it.model }
            .map { (key, rows) ->
                ModelUsageSummary(
                    provider = key.first,
                    model = key.second,
                    requests = rows.sumOf { it.requests },
                    promptTokens = rows.sumOf { it.promptTokens },
                    completionTokens = rows.sumOf { it.completionTokens },
                    unsplitTokens = rows.sumOf { it.unsplitTokens },
                    estimatedRequests = rows.sumOf { it.estimatedRequests },
                    unsplitRequests = rows.sumOf { it.unsplitRequests }
                )
            }
            .sortedWith(compareByDescending<ModelUsageSummary> { it.totalTokens }.thenBy { it.provider }.thenBy { it.model })

    /** Drops rows older than [retentionDays] before [today]; keeps the store bounded. */
    fun prune(entries: List<DailyModelUsage>, today: LocalDate, retentionDays: Long = 400): List<DailyModelUsage> {
        val cutoff = today.minusDays(retentionDays).toString()
        return entries.filter { it.date >= cutoff }
    }
}
