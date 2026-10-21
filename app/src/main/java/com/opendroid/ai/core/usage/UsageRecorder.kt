package com.opendroid.ai.core.usage

import com.opendroid.ai.core.llm.LLMResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The metering hook [com.opendroid.ai.core.llm.WrappedLLMProvider] reports
 * through. Kept as an interface so the wrapper (and its tests) never depend
 * on storage directly.
 */
interface UsageSink {
    /** A completed non-streaming call, with the provider's own usage figures. */
    fun recordResponse(response: LLMResponse)

    /** A completed streamed call, metered by counting the exchanged text. */
    fun recordStreamEstimate(provider: String, model: String, promptTokens: Int, completionTokens: Int)
}

/**
 * Fire-and-forget [UsageSink]: recording happens on its own scope and every
 * failure is swallowed — metering must never slow down, fail, or otherwise
 * disturb an LLM call.
 */
@Singleton
class UsageRecorder @Inject constructor(
    private val repository: UsageRepository
) : UsageSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun recordResponse(response: LLMResponse) {
        val event = UsageEventFactory.fromResponse(response)
        scope.launch {
            runCatching { repository.record(event) }
        }
    }

    override fun recordStreamEstimate(
        provider: String,
        model: String,
        promptTokens: Int,
        completionTokens: Int
    ) {
        val event = UsageEventFactory.fromStreamEstimate(provider, model, promptTokens, completionTokens)
        scope.launch {
            runCatching { repository.record(event) }
        }
    }
}
