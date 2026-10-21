package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.VisionSupport

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
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
class OllamaProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Ollama"
    override val availableModels: List<String> = listOf("llama3", "mistral", "phi3", "gemma", "codellama")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)
        val baseUrl = UrlUtils.formatBaseUrl(
            request.providerConfig?.endpoint?.takeIf { it.isNotBlank() } ?: config.ollamaUrl,
            ""
        )
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("Ollama server URL is not configured. Set it in Settings.")
        }
        val endpoint = "$baseUrl/api/chat"

        val startTime = System.currentTimeMillis()

        // Ollama's /api/chat takes plain-string content plus a separate
        // per-message images array of raw base64 — NOT the OpenAI
        // image_url parts array, which it rejects (every vision request
        // failed with HTTP 400). Build the native shape directly.
        val visionCapable = VisionSupport.supportsImage(name, selectedModel)
        val messagesList = buildOllamaMessages(request, visionCapable)

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList,
            "stream" to false,
            "options" to mapOf(
                "temperature" to request.temperature,
                "num_predict" to request.maxTokens
            )
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            requestBodyMap["format"] = "json"
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody(mediaType))

        // Add optional authorization if a bearer token key is configured
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() } ?: config.apiKeys[name]
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return withContext(Dispatchers.IO) {
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.OLLAMA,
                    request = request,
                    knownSecrets = listOfNotNull(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from Ollama")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Empty response body from Ollama")
            // Type-checked extraction: a 200 lacking message/content (or
            // carrying them as JSON null) is a malformed response, not an
            // Unknown crash via ClassCastException/NPE.
            val content = jsonResponse.get("message")
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            if (content.isNullOrBlank()) {
                throw LLMErrorMapper.malformed(name, selectedModel)
            }

            // Ollama reports prompt/eval counts natively; keep the split
            // for B3 metering. Absent fields stay null (unknown).
            val promptEvalCount = jsonResponse.get("prompt_eval_count")?.takeIf { it.isJsonPrimitive }?.asInt
            val evalCount = jsonResponse.get("eval_count")?.takeIf { it.isJsonPrimitive }?.asInt

            LLMResponse(
                content = content,
                tokensUsed = (promptEvalCount ?: 0) + (evalCount ?: 0),
                promptTokens = promptEvalCount,
                completionTokens = evalCount,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            val response = complete(request)
            val words = response.content.split(" ")
            for (word in words) {
                emit("$word ")
                kotlinx.coroutines.delay(50)
            }
        } catch (e: CancellationException) {
            // Cancellation must propagate so the collecting coroutine unwinds;
            // it must never surface as the assistant's reply.
            throw e
        } catch (e: LLMException) {
            // Typed API errors reach the collector's error handlers unchanged.
            throw e
        } catch (e: IllegalStateException) {
            // Configuration failures belong in the error state, not in
            // persisted assistant content.
            throw e
        } catch (e: Exception) {
            throw IOException("Ollama streaming failed.", e)
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return config.ollamaUrl.trim().isNotEmpty()
    }

    /**
     * Builds messages in Ollama's native /api/chat shape: string content,
     * with image payloads (raw base64, no data: prefix) in a separate
     * `images` array on the message — sent only for vision-capable models
     * and image attachments, matching the gating the OpenAI shape applies.
     */
    private fun buildOllamaMessages(
        request: LLMRequest,
        visionCapable: Boolean
    ): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to request.systemPrompt)
        )
        request.messages.forEach { msg ->
            val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
            val entry = mutableMapOf<String, Any>("role" to role, "content" to msg.text)
            if (msg.imageBase64 != null && role == "user" && visionCapable &&
                VisionSupport.isImageMime(msg.attachmentMimeType ?: "image/jpeg")
            ) {
                entry["images"] = listOf(msg.imageBase64)
            }
            result.add(entry)
        }
        return result
    }

}
