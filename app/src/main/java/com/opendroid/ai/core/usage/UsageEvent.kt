package com.opendroid.ai.core.usage

import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.LLMResponse

/**
 * One metered LLM call (B3). Token counts are kept exactly as reported:
 * [promptTokens] / [completionTokens] are null when the provider did not
 * report that side of the split. Tokens a provider reported only as a
 * combined total land in [unsplitTokens] instead of being allocated to a
 * side by guesswork — the dashboard shows them as their own column so the
 * numbers on screen are never invented.
 */
data class UsageEvent(
    val provider: String,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val unsplitTokens: Int = 0,
    val estimated: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)

/** Pure mapping from provider responses / wire payloads to [UsageEvent]s. */
object UsageEventFactory {

    fun fromResponse(response: LLMResponse, timestampMillis: Long = System.currentTimeMillis()): UsageEvent {
        val prompt = response.promptTokens
        val completion = response.completionTokens
        // Whatever the reported total contains beyond the reported split was
        // never split by the provider; keep the remainder unsplit rather
        // than attributing it to a side.
        val accounted = (prompt ?: 0) + (completion ?: 0)
        val unsplit = (response.tokensUsed - accounted).coerceAtLeast(0)
        return UsageEvent(
            provider = response.provider,
            model = response.model,
            promptTokens = prompt,
            completionTokens = completion,
            unsplitTokens = unsplit,
            estimated = response.usageEstimated,
            timestampMillis = timestampMillis
        )
    }

    /** A streamed call metered by counting text (see WrappedLLMProvider). */
    fun fromStreamEstimate(
        provider: String,
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        timestampMillis: Long = System.currentTimeMillis()
    ): UsageEvent = UsageEvent(
        provider = provider,
        model = model,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        unsplitTokens = 0,
        estimated = true,
        timestampMillis = timestampMillis
    )
}

/**
 * Prompt/completion/total counts parsed from an OpenAI-shaped `usage`
 * object (`prompt_tokens` / `completion_tokens` / `total_tokens`), shared by
 * the eight providers speaking that dialect. Missing members stay null —
 * only [ParsedUsage.totalTokens] falls back, to the split sum and then 0,
 * matching the historical `tokensUsed` behaviour.
 */
data class ParsedUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int
)

fun parseOpenAiStyleUsage(usage: JsonObject?): ParsedUsage {
    val prompt = usage.intOrNull("prompt_tokens")
    val completion = usage.intOrNull("completion_tokens")
    val total = usage.intOrNull("total_tokens")
        ?: ((prompt ?: 0) + (completion ?: 0))
    return ParsedUsage(prompt, completion, total)
}

internal fun JsonObject?.intOrNull(member: String): Int? {
    val element = this?.get(member) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
        element.asInt
    } else {
        null
    }
}
