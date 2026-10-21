package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.VisionSupport

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
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
class CohereProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Cohere"
    override val availableModels: List<String> = listOf("command-r-plus", "command-r")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "command-r-plus"
        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt, visionCapable = VisionSupport.supportsImage(name, selectedModel))


        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList
        )

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://api.cohere.ai/v2/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.COHERE,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from Cohere")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val messageObj = jsonResponse.getAsJsonObject("message")
            val contentArray = messageObj.getAsJsonArray("content")
            val content = contentArray[0].asJsonObject.get("text").asString

            // Cohere v2 reports usage.tokens.input_tokens / output_tokens;
            // keep the split for B3 metering, falling back to a reported
            // combined total (which then stays unsplit downstream).
            val usageTokens = jsonResponse.getAsJsonObject("usage")?.getAsJsonObject("tokens")
            val promptTokens = usageTokens?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
            val completionTokens = usageTokens?.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
            val tokensUsed = usageTokens?.get("total_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: ((promptTokens ?: 0) + (completionTokens ?: 0))

            LLMResponse(
                content = content,
                tokensUsed = tokensUsed,
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
        val response = complete(request)
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
}
