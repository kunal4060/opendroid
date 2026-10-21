package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.usage.UsageSink
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3: the wrapper is the metering choke point — exactly one usage record
 * per logical call, failures record nothing, streams are counted once.
 */
class WrappedLLMProviderUsageTest {

    private class RecordingSink : UsageSink {
        val responses = mutableListOf<LLMResponse>()
        data class StreamRecord(val provider: String, val model: String, val prompt: Int, val completion: Int)
        val streams = mutableListOf<StreamRecord>()

        override fun recordResponse(response: LLMResponse) {
            responses += response
        }

        override fun recordStreamEstimate(provider: String, model: String, promptTokens: Int, completionTokens: Int) {
            streams += StreamRecord(provider, model, promptTokens, completionTokens)
        }
    }

    private class FakeProvider(
        override val name: String,
        private val completion: suspend (LLMRequest) -> LLMResponse = { request ->
            LLMResponse("ok", 30, request.model.orEmpty(), name, 1, promptTokens = 10, completionTokens = 20)
        },
        private val stream: ((LLMRequest) -> Flow<String>)? = null
    ) : LLMProvider {
        override val availableModels: List<String> = emptyList()
        override suspend fun complete(request: LLMRequest): LLMResponse = completion(request)
        override fun streamComplete(request: LLMRequest): Flow<String> =
            stream?.invoke(request) ?: flow { emit(completion(request).content) }
        override suspend fun isAvailable(): Boolean = true
    }

    private fun wrapper(delegate: LLMProvider, sink: UsageSink) = WrappedLLMProvider(
        delegate = delegate,
        configProvider = {
            LLMConfig(
                activeProvider = delegate.name,
                activeModel = ProviderCatalog.defaultModel(delegate.name),
                apiKeys = mapOf(delegate.name to "test-key")
            )
        },
        retryRuntime = RetryRuntime(nowMillis = { 0L }, delayMillis = {}, jitterMillis = { 0L }),
        usageSink = sink
    )

    private fun request() = LLMRequest(
        systemPrompt = "constant",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        responseFormat = ResponseFormat.TEXT
    )

    @Test
    fun `complete records exactly one usage record with the provider split`() = runBlocking {
        val sink = RecordingSink()
        val result = wrapper(FakeProvider("OpenAI"), sink).complete(request())
        assertEquals(1, sink.responses.size)
        assertEquals(result, sink.responses.single())
        assertEquals(10, sink.responses.single().promptTokens)
        assertEquals(20, sink.responses.single().completionTokens)
        assertTrue(sink.streams.isEmpty())
    }

    @Test
    fun `complete records once even when the first attempt is retried`() = runBlocking {
        val sink = RecordingSink()
        var attempts = 0
        val delegate = FakeProvider("OpenAI", completion = { request ->
            attempts++
            if (attempts == 1) {
                throw LLMException(
                    error = LLMError.ServerError,
                    provider = "OpenAI",
                    model = request.model.orEmpty(),
                    status = 503,
                    retryable = true
                )
            }
            LLMResponse("pong", 30, request.model.orEmpty(), "OpenAI", 1, promptTokens = 10, completionTokens = 20)
        })
        wrapper(delegate, sink).complete(request())
        assertEquals(2, attempts)
        assertEquals(1, sink.responses.size)
    }

    @Test
    fun `failed complete records nothing`() = runBlocking {
        val sink = RecordingSink()
        val delegate = FakeProvider("OpenAI", completion = {
            throw LLMException(
                error = LLMError.RateLimited,
                provider = "OpenAI",
                model = "gpt-4o",
                status = 429,
                retryable = true
            )
        })
        runCatching {
            wrapper(delegate, sink).complete(request().copy(retryPolicy = RetryPolicy.NONE))
        }
        assertTrue(sink.responses.isEmpty())
        assertTrue(sink.streams.isEmpty())
    }

    @Test
    fun `stream records one counting estimate for the completed stream`() = runBlocking {
        val sink = RecordingSink()
        val delegate = FakeProvider(
            "OpenAI",
            stream = { flow { emit("hello "); emit("world") } }
        )
        val chunks = wrapper(delegate, sink).streamComplete(request()).toList()
        assertEquals(listOf("hello ", "world"), chunks)
        assertEquals(1, sink.streams.size)
        val record = sink.streams.single()
        assertEquals("OpenAI", record.provider)
        assertEquals("gpt-4o", record.model)
        // The completion side is the accumulated text run through the same
        // estimator the wrapper uses.
        assertEquals(PromptBudget.estimateTokens("hello world"), record.completion)
        assertTrue(record.prompt > 0)
        assertTrue(sink.responses.isEmpty())
    }

    @Test
    fun `failed stream records nothing`() = runBlocking {
        val sink = RecordingSink()
        val delegate = FakeProvider(
            "OpenAI",
            stream = {
                flow {
                    throw LLMException(
                        error = LLMError.Network,
                        provider = "OpenAI",
                        model = "gpt-4o",
                        retryable = false
                    )
                }
            }
        )
        runCatching { wrapper(delegate, sink).streamComplete(request()).toList() }
        assertTrue(sink.streams.isEmpty())
    }

    @Test
    fun `metering is optional - wrapper without a sink still completes`() = runBlocking {
        val plain = WrappedLLMProvider(
            delegate = FakeProvider("OpenAI"),
            configProvider = {
                LLMConfig(
                    activeProvider = "OpenAI",
                    activeModel = "gpt-4o",
                    apiKeys = mapOf("OpenAI" to "test-key")
                )
            },
            retryRuntime = RetryRuntime(nowMillis = { 0L }, delayMillis = {}, jitterMillis = { 0L })
        )
        assertEquals("ok", plain.complete(request()).content)
    }
}
