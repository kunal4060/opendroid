package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string representing parameters
)

@Serializable
sealed class StreamChunk {
    @Serializable
    data class Content(val text: String) : StreamChunk()
    @Serializable
    data class ToolCall(val name: String, val arguments: String) : StreamChunk()
}

interface AIProvider {
    suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<StreamChunk>
}

interface LLMProvider : AIProvider {
    val name: String
    val availableModels: List<String>
    suspend fun complete(request: LLMRequest): LLMResponse
    fun streamComplete(request: LLMRequest): Flow<String>
    suspend fun isAvailable(): Boolean

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> {
        val systemPrompt = "You are an autonomous AI agent for Android."
        val request = LLMRequest(
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools.map { Tool(it.name, it.description, it.parameters) }
        )
        return kotlinx.coroutines.flow.flow {
            streamComplete(request).collect { text ->
                emit(StreamChunk.Content(text))
            }
        }
    }
}

/**
 * Ephemeral provider configuration captured with a resolved request. It is
 * deliberately excluded from serialization and renders only as redacted text.
 */
class ProviderRequestConfig(
    val apiKey: String,
    val endpoint: String
) {
    override fun toString(): String = "<redacted provider configuration>"
}

@Serializable
data class LLMRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    /**
     * Resolved by [WrappedLLMProvider] from the provider/model pairing. Direct
     * provider callers may leave it null and receive the catalog default.
     */
    val model: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2000,
    val responseFormat: ResponseFormat = ResponseFormat.JSON,
    val tools: List<Tool>? = null,
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    @Transient
    val providerConfig: ProviderRequestConfig? = null
)

enum class ResponseFormat {
    JSON, TEXT
}

enum class RetryPolicy {
    DEFAULT,
    NONE
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string representing parameters
)

@Serializable
data class LLMResponse(
    val content: String,
    val tokensUsed: Int,
    val model: String,
    val provider: String,
    val latencyMs: Long,
    /**
     * B3 usage metering: the prompt/completion split exactly as the provider
     * reported it on the wire. Null means the provider did not report that
     * side (or reported only a combined total, which stays in [tokensUsed]) —
     * consumers must treat null as "unknown", never as zero, and must not
     * reconstruct a split that was not reported.
     */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /**
     * True when the token counts are local estimates (on-device providers and
     * streamed responses metered by counting), not provider-reported figures.
     */
    val usageEstimated: Boolean = false
)

/**
 * Normalizes a stored conversation for providers whose APIs enforce strict
 * user-first role alternation (Gemini generateContent, Anthropic Messages):
 * both reject a conversation whose first turn is not the user's, and both
 * reject two consecutive turns with the same role.
 *
 * The stored history strictly alternates USER, AGENT, USER, …, so a fixed
 * tail window that ends with the current user message starts with an AGENT
 * message whenever the window length is even — a guaranteed HTTP 400 from
 * the sixth exchange onward. Leading non-user turns (the tail of an older
 * exchange) are therefore dropped, and consecutive same-sender turns —
 * which a failed turn leaves behind (user message stored, no agent reply)
 * — are merged into one turn, keeping both texts and any attachment.
 */
fun List<ChatMessage>.sanitizeForRoleAlternatingApi(): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    for (message in this) {
        val last = result.lastOrNull()
        when {
            last == null -> {
                if (message.sender == ChatMessage.Sender.USER) result.add(message)
            }
            last.sender == message.sender -> {
                result[result.lastIndex] = last.mergedWithNextTurn(message)
            }
            else -> result.add(message)
        }
    }
    return result
}

private fun ChatMessage.mergedWithNextTurn(next: ChatMessage): ChatMessage = copy(
    text = listOf(text, next.text).filter { it.isNotBlank() }.joinToString("\n"),
    imageBase64 = next.imageBase64 ?: imageBase64,
    attachmentMimeType = if (next.imageBase64 != null) next.attachmentMimeType else attachmentMimeType,
    attachmentName = if (next.imageBase64 != null) next.attachmentName else attachmentName
)

/**
 * Builds the OpenAI-shaped message list. Attachment parts (feature B1) are
 * included only for images, only when the provider this payload is being
 * built for is vision-capable ([visionCapable], from [VisionSupport] for
 * that provider's own name/model): a text-only target - e.g. a fallback
 * provider mid-task - gets the text alone rather than a payload it would
 * reject or silently ignore. PDFs are never sent as binary through this
 * family; their text is extracted and inlined upstream instead.
 */
fun List<ChatMessage>.toOpenAIMessages(
    systemPrompt: String,
    visionCapable: Boolean = true
): List<Map<String, Any>> {
    val messagesList = mutableListOf<Map<String, Any>>()
    messagesList.add(mapOf("role" to "system", "content" to systemPrompt))
    this.forEach { msg ->
        val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
        val mimeType = msg.attachmentMimeType ?: "image/jpeg"
        if (msg.imageBase64 != null && role == "user" && visionCapable &&
            VisionSupport.isImageMime(mimeType)
        ) {
            messagesList.add(
                mapOf(
                    "role" to role,
                    "content" to listOf(
                        mapOf("type" to "text", "text" to msg.text),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:$mimeType;base64,${msg.imageBase64}"
                            )
                        )
                    )
                )
            )
        } else {
            messagesList.add(mapOf("role" to role, "content" to msg.text))
        }
    }
    return messagesList
}
