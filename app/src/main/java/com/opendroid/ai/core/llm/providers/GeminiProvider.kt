package com.opendroid.ai.core.llm.providers

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

@Singleton
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Google Gemini"
    // Fallback only (the live list replaces it when the fetch succeeds):
    // dated snapshots get retired for new accounts, so lead with the
    // family-tracking alias.
    override val availableModels: List<String> = listOf("gemini-flash-latest", "gemini-3.8-flash")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)

        // Deliberately no local shortcut for any model id: a previous
        // "gemini-nano" branch fabricated chat replies and a canned plan
        // with no model behind them (Loop-2 audit Area 1, Finding 1). A
        // stale persisted "gemini-nano" selection now reaches the key check
        // below and the real API like any other id, failing honestly
        // (missing key / unknown model) instead of faking a reply.
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")
        val startTime = System.currentTimeMillis()

        // Map roles to user and model. generateContent requires the first
        // content to be a user turn and roles to alternate, so the stored
        // window is sanitized first (see sanitizeForRoleAlternatingApi).
        val contentsList = mutableListOf<Map<String, Any>>()
        request.messages.sanitizeForRoleAlternatingApi().forEach { msg ->
            val role = if (msg.sender == com.opendroid.ai.data.models.ChatMessage.Sender.USER) "user" else "model"
            val partsList = mutableListOf<Map<String, Any>>()
            partsList.add(mapOf("text" to msg.text))
            if (msg.imageBase64 != null && role == "user") {
                // B1: the payload's real mime type - an image, or a PDF,
                // which Gemini accepts natively as inlineData. Messages
                // without attachment metadata are legacy screenshots and
                // keep the historical image/jpeg default.
                partsList.add(
                    mapOf(
                        "inlineData" to mapOf(
                            "mimeType" to (msg.attachmentMimeType ?: "image/jpeg"),
                            "data" to msg.imageBase64
                        )
                    )
                )
            }
            contentsList.add(
                mapOf(
                    "role" to role,
                    "parts" to partsList
                )
            )
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "contents" to contentsList,
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to request.systemPrompt))
            )
        )

        val generationConfig = mutableMapOf<String, Any>(
            "temperature" to request.temperature,
            "maxOutputTokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            generationConfig["responseMimeType"] = "application/json"
        }
        requestBodyMap["generationConfig"] = generationConfig

        val bodyJson = gson.toJson(requestBodyMap)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent"
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.executeCancellable(httpRequest).use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.GEMINI,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                throw IOException("Empty response body from Gemini")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Empty response body from Gemini")
            // A safety-blocked prompt returns 200 with NO candidates (only
            // promptFeedback), and a candidate's parts may interleave text
            // with function calls or inline data. Concatenate every text
            // part in order — keeping only the first silently truncates
            // multi-part replies. No text anywhere is a blocked/empty
            // result: a typed malformed failure, not a successful "" reply.
            val candidates = jsonResponse.get("candidates")
                ?.takeIf { it.isJsonArray }?.asJsonArray
            val firstCandidate = candidates?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val parts = firstCandidate?.get("content")
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
            val text = parts?.joinToString("") { part ->
                part.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            } ?: ""
            if (text.isBlank()) {
                throw LLMErrorMapper.malformed(name, selectedModel)
            }

            // usageMetadata may be JSON null / non-object, and a count may
            // arrive as a non-number primitive: getAsJsonObject / asInt
            // would throw on those shapes and discard a valid reply.
            val usageMetadata = jsonResponse.get("usageMetadata")
                ?.takeIf { it.isJsonObject }?.asJsonObject
            // Gemini reports the split in usageMetadata; keep it split for
            // B3 metering. Absent fields stay null (unknown).
            val promptTokens = usageMetadata?.get("promptTokenCount")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            val completionTokens = usageMetadata?.get("candidatesTokenCount")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            val totalTokens = usageMetadata?.get("totalTokenCount")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                ?: ((promptTokens ?: 0) + (completionTokens ?: 0))

            LLMResponse(
                content = text,
                tokensUsed = totalTokens,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
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
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
