package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.data.models.resolveClaudeModelOrNull
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
class ClaudeProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Anthropic Claude"
    override val availableModels: List<String> = ClaudeModelCatalog.models.map { it.id }

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        // Model IDs are untrusted input: accept catalog entries (incl. legacy
        // aliases) and IDs Anthropic listed via /v1/models (cached on LLMConfig),
        // never an arbitrary persisted string.
        val requestedModel = request.model?.takeIf { it.isNotBlank() }
        val selectedModel = if (requestedModel == null) {
            ClaudeModelCatalog.defaultModelId
        } else {
            config.resolveClaudeModelOrNull(requestedModel)
                ?: throw IllegalStateException(
                    "The selected Claude model \"$requestedModel\" is no longer supported. " +
                        "Please pick another model in Settings."
                )
        }

        // The Messages API requires the first message to use the user role
        // and roles to alternate, so the stored window is sanitized first
        // (see sanitizeForRoleAlternatingApi).
        val messagesList = mutableListOf<Map<String, Any>>()
        request.messages.sanitizeForRoleAlternatingApi().forEach { msg ->
            val role = if (msg.sender == com.opendroid.ai.data.models.ChatMessage.Sender.USER) "user" else "assistant"
            if (msg.imageBase64 != null && role == "user") {
                // B1: PDFs go as a native document block; images as an image
                // block carrying the payload's real media type. No metadata
                // means a legacy screenshot, historically image/jpeg.
                val mimeType = msg.attachmentMimeType ?: "image/jpeg"
                val attachmentBlock: Map<String, Any> = if (mimeType == "application/pdf") {
                    mapOf(
                        "type" to "document",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to "application/pdf",
                            "data" to msg.imageBase64
                        )
                    )
                } else {
                    mapOf(
                        "type" to "image",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to mimeType,
                            "data" to msg.imageBase64
                        )
                    )
                }
                messagesList.add(
                    mapOf(
                        "role" to role,
                        "content" to listOf(
                            mapOf("type" to "text", "text" to msg.text),
                            attachmentBlock
                        )
                    )
                )
            } else {
                messagesList.add(mapOf("role" to role, "content" to msg.text))
            }
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "system" to request.systemPrompt,
            "messages" to messagesList,
            "max_tokens" to request.maxTokens
        )
        // Current Opus-tier models reject sampling parameters with HTTP 400.
        if (ClaudeModelCatalog.acceptsSamplingParameters(selectedModel)) {
            requestBodyMap["temperature"] = request.temperature
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.executeCancellable(httpRequest).use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.CLAUDE,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from Claude")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Empty response body from Claude")
            // Concatenate every text block in order: the array can be
            // empty, a non-text block (thinking/tool_use) has no "text"
            // member to read, and keeping only the first text block
            // silently truncates replies split around such blocks. No text
            // anywhere is a malformed result, not a successful "" reply.
            val contentArray = jsonResponse.get("content")
                ?.takeIf { it.isJsonArray }?.asJsonArray
            val content = contentArray?.joinToString("") { block ->
                block.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            } ?: ""
            if (content.isBlank()) {
                throw LLMErrorMapper.malformed(name, selectedModel)
            }

            // usage may be JSON null / non-object and a count may arrive
            // as a non-number primitive: getAsJsonObject / asInt would
            // throw on those shapes and discard a valid reply.
            val usage = jsonResponse.get("usage")
                ?.takeIf { it.isJsonObject }?.asJsonObject
            // Anthropic reports the split natively; keep it split for B3
            // metering instead of collapsing it. Absent fields stay null
            // (unknown), never reconstructed.
            val inputTokens = usage?.get("input_tokens")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            val outputTokens = usage?.get("output_tokens")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

            LLMResponse(
                content = content,
                tokensUsed = (inputTokens ?: 0) + (outputTokens ?: 0),
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        // complete() throws the typed failure and the flow fails with it
        // unchanged: cancellation propagates as cancellation, LLMException
        // keeps its classification, configuration failures reach the error
        // state. The previous catch-all re-wrapped everything else as
        // IOException("Claude streaming failed."), mis-classifying parse
        // failures (MalformedResponse) as Network — and its word-by-word
        // loop fabricated streaming like the rest of the family's
        // (Findings 14 and 15).
        emit(complete(request).content)
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
