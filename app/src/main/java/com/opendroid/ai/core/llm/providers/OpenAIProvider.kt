package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.VisionSupport
import com.opendroid.ai.core.usage.parseOpenAiStyleUsage

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Defensive extraction of the first choice's message content from an
 * OpenAI-style chat-completions response, shared by every provider that
 * parses this shape. A 200 with an empty `choices` array (content-filter
 * finishes on some gateways) or a message with null/absent `content`
 * (tool-call replies) yields an empty string instead of crashing the
 * parse with IndexOutOfBounds/NPE — callers must treat that empty result
 * as a malformed response, never as a successful empty reply. Content in
 * array-of-parts form (multi-part replies) is concatenated from its text
 * parts, not collapsed to empty.
 */
internal fun JsonObject.firstChoiceContent(): String {
    val choices = get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
    val message = choices.firstOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject
        ?.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return ""
    val content = message.get("content") ?: return ""
    return when {
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.joinToString("") { part ->
            part.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        }
        else -> ""
    }
}

/**
 * Safe access to the `usage` member of an OpenAI-style response:
 * `getAsJsonObject("usage")` throws ClassCastException when the member is
 * JSON null or any non-object (a shape real gateways send), discarding an
 * otherwise valid reply into a non-retryable Unknown error.
 */
internal fun JsonObject.usageObjectOrNull(): JsonObject? =
    get("usage")?.takeIf { it.isJsonObject }?.asJsonObject

@Singleton
class OpenAIProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "OpenAI"
    override val availableModels: List<String> = listOf("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo", "o1-preview", "o3-mini")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "gpt-4o"

        // Build messages payload. Reasoning models (o1/o3) reject the
        // system role (o1-preview), so the system prompt is delivered as a
        // user turn for that model instead.
        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt, visionCapable = VisionSupport.supportsImage(name, selectedModel))
            .let { messages ->
                if (selectedModel == "o1-preview") {
                    messages.map { message ->
                        if (message["role"] == "system") {
                            mapOf("role" to "user", "content" to (message["content"] ?: ""))
                        } else {
                            message
                        }
                    }
                } else {
                    messages
                }
            }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList
        )
        if (isReasoningModel(selectedModel)) {
            // Reasoning models reject max_tokens (max_completion_tokens is
            // required) and reject any non-default temperature, so both
            // sampling fields are omitted rather than sent at values the
            // API refuses.
            requestBodyMap["max_completion_tokens"] = request.maxTokens
        } else {
            requestBodyMap["temperature"] = request.temperature
            requestBodyMap["max_tokens"] = request.maxTokens
        }

        // o1-preview also rejects response_format; o3-mini accepts it.
        if (request.responseFormat == ResponseFormat.JSON && selectedModel != "o1-preview") {
            requestBodyMap["response_format"] = mapOf("type" to "json_object")
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.OPENAI,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                throw IOException("Empty response body from OpenAI")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Empty response body from OpenAI")
            val content = jsonResponse.firstChoiceContent()
            if (content.isBlank()) {
                // A 200 with no answer (content filter, refusal, tool-call
                // only) is a malformed result, not a successful empty reply.
                throw LLMErrorMapper.malformed(name, selectedModel)
            }

            val usage = parseOpenAiStyleUsage(jsonResponse.usageObjectOrNull())

            LLMResponse(
                content = content,
                tokensUsed = usage.totalTokens,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        // Fallback simple streaming mock or raw API line-by-line stream.
        // For simplicity and completeness, we will fetch complete first and emit,
        // or parse SSE streams if needed. Let's execute complete and stream it.
        val response = complete(request)
        // Stream chunks
        val words = response.content.split(" ")
        for (word in words) {
            emit("$word ")
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }

    private fun isReasoningModel(model: String): Boolean =
        model == "o1" || model.startsWith("o1-") || model.startsWith("o3-")
}
