package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.VisionSupport
import com.opendroid.ai.core.usage.parseOpenAiStyleUsage

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.util.UrlUtils
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

@Singleton
class CustomOpenAIProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Custom OpenAI Compatible"
    override val availableModels: List<String> = listOf("custom-model")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() } ?: config.apiKeys[name] ?: ""
        // Credentialed cloud endpoint: a scheme-less entry must default to
        // https, never plaintext http (the Bearer key rides on this URL).
        val baseUrl = request.providerConfig?.endpoint?.takeIf { it.isNotBlank() }
            ?.let { UrlUtils.formatBaseUrl(it, "https://api.openai.com/v1", defaultScheme = "https") }
            ?: UrlUtils.formatBaseUrl(config.customEndpoints[name] ?: "", "https://api.openai.com/v1", defaultScheme = "https")

        val startTime = System.currentTimeMillis()
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "gpt-4o"

        // Build messages payload
        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt, visionCapable = VisionSupport.supportsImage(name, selectedModel))

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList,
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )

        if (request.responseFormat == ResponseFormat.JSON) {
            requestBodyMap["response_format"] = mapOf("type" to "json_object")
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            // Omit the header when keyless: a literal "Bearer " is rejected
            // with 401 by strict servers that accept a missing header.
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.executeCancellable(httpRequest).use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.CUSTOM_OPENAI,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                throw IOException("Empty response body from Custom OpenAI provider")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Empty response body from Custom OpenAI provider")
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
        // No streaming endpoint is wired for this provider: deliver the
        // completed response verbatim, in one chunk. The previous
        // word-by-word re-emission at 50 ms/word fabricated the pacing
        // (a ~17 s artificial tail on a 500-token reply) and mutated the
        // text — whitespace re-joined, trailing space appended (Finding 14).
        emit(complete(request).content)
    }

    override suspend fun isAvailable(): Boolean {
        // Like every sibling, availability must reflect configuration:
        // this provider exists only when the user configured an endpoint
        // for it (a key is optional — keyless local servers are valid).
        val config = settingsRepository.llmConfig.first()
        return config.customEndpoints[name].orEmpty().isNotBlank()
    }

}
