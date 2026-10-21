package com.opendroid.ai.core.rag

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
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
 * An embeddings backend the RAG index can use (feature D4).
 *
 * [id] identifies the vector space ("provider|model"); it is stored on
 * every chunk so vectors from different models are never compared.
 */
interface EmbeddingBackend {
    val id: String
    val providerName: String
    val model: String

    /** Embeds [texts], returning one vector per input, in order. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * Which configured providers can serve embeddings, and where.
 *
 * Only providers whose embeddings API speaks the OpenAI shape
 * (`POST {base}/embeddings`, `{model, input: [...]}` →
 * `data[].embedding`) are listed. Gemini is reached through Google's
 * documented OpenAI-compatible endpoint; Ollama through its own
 * OpenAI-compatible `/v1/embeddings`. Providers without an embeddings API
 * (Claude, Groq, DeepSeek, Cohere's differently-shaped API, Copilot,
 * Opencode-Go, on-device chat models) are deliberately absent — when none
 * of the listed providers is configured, retrieval falls back to BM25.
 */
object EmbeddingCatalog {

    data class Endpoint(
        val providerName: String,
        val embeddingsUrl: String,
        val defaultModel: String,
        val apiKey: String?,
        val keyRequired: Boolean
    )

    /** Preference order when the active provider can't embed. */
    val PREFERENCE_ORDER = listOf(
        "OpenAI",
        "Google Gemini",
        "Mistral AI",
        "OpenRouter",
        "Together AI",
        "Custom OpenAI Compatible",
        "Ollama"
    )

    /** Resolves the endpoint for [providerName] from [config], or null when unusable. */
    fun endpointFor(providerName: String, config: LLMConfig): Endpoint? {
        fun key(name: String) = config.apiKeys[name]?.takeIf { it.isNotBlank() }
        return when (providerName) {
            "OpenAI" -> Endpoint(
                providerName, "https://api.openai.com/v1/embeddings",
                "text-embedding-3-small", key("OpenAI"), keyRequired = true
            )
            "Google Gemini" -> Endpoint(
                providerName, "https://generativelanguage.googleapis.com/v1beta/openai/embeddings",
                "gemini-embedding-001", key("Google Gemini"), keyRequired = true
            )
            "Mistral AI" -> Endpoint(
                providerName, "https://api.mistral.ai/v1/embeddings",
                "mistral-embed", key("Mistral AI"), keyRequired = true
            )
            "OpenRouter" -> Endpoint(
                providerName, "https://openrouter.ai/api/v1/embeddings",
                "openai/text-embedding-3-small", key("OpenRouter"), keyRequired = true
            )
            "Together AI" -> Endpoint(
                providerName, "https://api.together.xyz/v1/embeddings",
                "BAAI/bge-large-en-v1.5", key("Together AI"), keyRequired = true
            )
            "Custom OpenAI Compatible" -> {
                // No OpenAI fallback here: substituting api.openai.com
                // for a blank custom endpoint made this provider always
                // "configured", shadowing real backends later in
                // PREFERENCE_ORDER (Ollama) with a keyless phantom whose
                // requests were doomed to fail.
                val raw = config.customEndpoints[providerName]
                if (raw.isNullOrBlank()) {
                    null
                } else {
                    val base = UrlUtils.formatBaseUrl(raw, "")
                    if (base.isEmpty()) null else Endpoint(
                        providerName, "$base/embeddings",
                        "text-embedding-3-small", key(providerName), keyRequired = false
                    )
                }
            }
            "Ollama" -> {
                val base = UrlUtils.formatBaseUrl(config.ollamaUrl, "")
                if (base.isEmpty()) null else Endpoint(
                    providerName, "$base/v1/embeddings",
                    "nomic-embed-text", apiKey = null, keyRequired = false
                )
            }
            else -> null
        }
    }

    /** An endpoint counts as usable when its key requirement is satisfied. */
    fun isUsable(endpoint: Endpoint): Boolean =
        !endpoint.keyRequired || !endpoint.apiKey.isNullOrBlank()

    /**
     * Picks the backend to use: the active provider when it can embed,
     * otherwise the first usable provider in [PREFERENCE_ORDER]. Null when
     * no embedding backend is configured at all (→ lexical retrieval).
     */
    fun resolveEndpoint(config: LLMConfig): Endpoint? {
        val active = com.opendroid.ai.core.llm.ProviderCatalog.canonicalName(config.activeProvider)
        endpointFor(active, config)
            ?.takeIf { isUsable(it) }
            ?.let { return it }
        for (name in PREFERENCE_ORDER) {
            if (name == active) continue
            endpointFor(name, config)?.takeIf { isUsable(it) }?.let { return it }
        }
        return null
    }
}

/** OpenAI-shaped embeddings client over the app's shared OkHttp client. */
class OpenAiCompatibleEmbeddingBackend(
    private val client: OkHttpClient,
    private val endpoint: EmbeddingCatalog.Endpoint
) : EmbeddingBackend {

    override val providerName: String get() = endpoint.providerName
    override val model: String get() = endpoint.defaultModel
    override val id: String get() = "${endpoint.providerName}|${endpoint.defaultModel}"

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val vectors = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(BATCH_SIZE)) {
            vectors += embedBatch(batch)
        }
        return vectors
    }

    private suspend fun embedBatch(batch: List<String>): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(
                mapOf("model" to endpoint.defaultModel, "input" to batch)
            )
            val builder = Request.Builder()
                .url(endpoint.embeddingsUrl)
                .post(body.toRequestBody(mediaType))
            endpoint.apiKey?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "Embeddings request to ${endpoint.providerName} failed: HTTP ${response.code}"
                    )
                }
                val payload = response.body.string()
                if (payload.isBlank()) {
                    throw IOException("Empty embeddings response from ${endpoint.providerName}")
                }
                val json = gson.fromJson(payload, JsonObject::class.java)
                val data = json.getAsJsonArray("data")
                    ?: throw IOException("Malformed embeddings response from ${endpoint.providerName}")
                val byIndex = arrayOfNulls<FloatArray>(batch.size)
                for (element in data) {
                    val obj = element.asJsonObject
                    val index = obj.get("index")?.asInt ?: continue
                    val vector = obj.getAsJsonArray("embedding")
                        ?: throw IOException("Malformed embedding entry from ${endpoint.providerName}")
                    if (index in byIndex.indices) {
                        byIndex[index] = FloatArray(vector.size()) { i -> vector[i].asFloat }
                    }
                }
                byIndex.mapIndexed { i, vector ->
                    vector ?: throw IOException(
                        "Embeddings response from ${endpoint.providerName} missed input $i"
                    )
                }
            }
        }

    companion object {
        const val BATCH_SIZE = 32
    }
}

/** Resolves the embedding backend for the current settings, if any. */
@Singleton
class EmbeddingBackendResolver @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    suspend fun resolve(): EmbeddingBackend? {
        val config = settingsRepository.llmConfig.first()
        val endpoint = EmbeddingCatalog.resolveEndpoint(config) ?: return null
        return OpenAiCompatibleEmbeddingBackend(client, endpoint)
    }
}
