package com.opendroid.ai.core.research

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/** One search hit exactly as the provider returned it — never synthesised locally. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Double? = null
)

/** A search or fetch failed in a way the user should hear about honestly. */
class ResearchException(message: String) : Exception(message)

/**
 * A web-search backend for research runs (feature D3).
 *
 * Implementations are deliberately small and swappable: Tavily is the
 * primary (one vendor covers search + full-text extract), Brave is the
 * alternate BYOK provider, and SearXNG is the keyless option for users
 * who run or trust an instance. Nothing above this interface knows which
 * backend produced a result, so a vendor can be dropped without touching
 * the pipeline, the citation store, or the UI.
 */
interface SearchProvider {
    val kind: ResearchProviderKind

    /** Ranked results for [query]; throws [ResearchException] on provider/transport failure. */
    suspend fun search(query: String, maxResults: Int): List<SearchResult>

    /**
     * Full page text for the given URLs, keyed by URL. URLs that fail to
     * fetch are simply absent from the map — the caller falls back to the
     * search snippet for those sources and says so in the run record.
     */
    suspend fun fetchContent(urls: List<String>): Map<String, String>
}

private val researchJson = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Caps one fetched page's text so a huge page can't blow the model's context. */
internal const val MAX_FETCHED_TEXT_CHARS = 24_000

internal fun capFetchedText(text: String): String =
    if (text.length <= MAX_FETCHED_TEXT_CHARS) text else text.substring(0, MAX_FETCHED_TEXT_CHARS)

/**
 * Tavily (https://api.tavily.com) — built for agents: `/search` returns
 * ranked results with relevance scores and extracted snippets, `/extract`
 * returns cleaned page text, so no on-device parsing is needed on this
 * path. The API key travels as a Bearer token and is never logged.
 */
class TavilySearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String
) : SearchProvider {

    override val kind: ResearchProviderKind = ResearchProviderKind.TAVILY

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("query", query)
                put("max_results", maxResults)
                put("search_depth", "basic")
                put("include_answer", false)
                put("include_raw_content", false)
            }
            val root = postJson("$BASE_URL/search", body)
            val results = root["results"]?.jsonArray ?: return@withContext emptyList()
            results.mapNotNull { element ->
                val obj = element.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SearchResult(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: url,
                    url = url,
                    snippet = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                    score = obj["score"]?.jsonPrimitive?.doubleOrNull
                )
            }
        }

    override suspend fun fetchContent(urls: List<String>): Map<String, String> {
        if (urls.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonArray("urls") { urls.forEach { add(it) } }
                put("extract_depth", "basic")
            }
            val root = try {
                postJson("$BASE_URL/extract", body)
            } catch (e: ResearchException) {
                // Extract is an enhancement over the snippets — losing it
                // shrinks the grounding text, it must not kill the run.
                Log.w(TAG, "Tavily extract failed, falling back to snippets: ${e.message}")
                return@withContext emptyMap()
            }
            val out = mutableMapOf<String, String>()
            root["results"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val text = obj["raw_content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@forEach
                out[url] = capFetchedText(text)
            }
            out
        }
    }

    private fun postJson(url: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ResearchException(
                    if (response.code == 401 || response.code == 403) {
                        "Tavily rejected the API key (HTTP ${response.code}). " +
                            "Check the key in Settings → Web research."
                    } else {
                        "Tavily search failed (HTTP ${response.code})."
                    }
                )
            }
            return try {
                researchJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ResearchException("Tavily returned an unreadable response.")
            }
        }
    }

    companion object {
        private const val TAG = "TavilySearch"
        private const val BASE_URL = "https://api.tavily.com"
    }
}

/**
 * Brave Search API (https://api.search.brave.com) — an independent index.
 * Search only returns snippets, so page text comes from the on-device
 * [JsoupPageFetcher], the same path SearXNG uses.
 */
class BraveSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val pageFetcher: PageFetcher = JsoupPageFetcher(client)
) : SearchProvider {

    override val kind: ResearchProviderKind = ResearchProviderKind.BRAVE

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.search.brave.com")
                .addPathSegments("res/v1/web/search")
                .addQueryParameter("q", query)
                .addQueryParameter("count", maxResults.toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ResearchException(
                        if (response.code == 401 || response.code == 403) {
                            "Brave Search rejected the API key (HTTP ${response.code}). " +
                                "Check the key in Settings → Web research."
                        } else {
                            "Brave Search failed (HTTP ${response.code})."
                        }
                    )
                }
                val root = try {
                    researchJson.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw ResearchException("Brave Search returned an unreadable response.")
                }
                val results = root["web"]?.jsonObject?.get("results")?.jsonArray
                    ?: return@withContext emptyList()
                results.mapNotNull { element ->
                    val obj = element.jsonObject
                    val resultUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SearchResult(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: resultUrl,
                        url = resultUrl,
                        snippet = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            }
        }

    override suspend fun fetchContent(urls: List<String>): Map<String, String> =
        pageFetcher.fetch(urls)
}

/**
 * SearXNG — the keyless option. The user supplies the base URL of an
 * instance they run or trust (Settings → Web research); there is no
 * account and no key. The JSON API (`format=json`) must be enabled on the
 * instance — when it isn't, the instance answers 403 and the run reports
 * exactly that instead of inventing results.
 */
class SearxngSearchProvider(
    private val client: OkHttpClient,
    private val instanceUrl: String,
    private val pageFetcher: PageFetcher = JsoupPageFetcher(client)
) : SearchProvider {

    override val kind: ResearchProviderKind = ResearchProviderKind.SEARXNG

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val base = instanceUrl.trimEnd('/').toHttpUrlOrNull()
                ?: throw ResearchException(
                    "The SearXNG instance URL doesn't look valid. Fix it in Settings → Web research."
                )
            val url = base.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .build()
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ResearchException(
                        if (response.code == 403) {
                            "The SearXNG instance refused the request (HTTP 403) — its JSON " +
                                "API is probably disabled. Enable format=json on the instance, " +
                                "or pick another provider in Settings → Web research."
                        } else {
                            "SearXNG search failed (HTTP ${response.code})."
                        }
                    )
                }
                val root = try {
                    researchJson.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw ResearchException("The SearXNG instance returned an unreadable response.")
                }
                val results = root["results"]?.jsonArray ?: return@withContext emptyList()
                results.take(maxResults).mapNotNull { element ->
                    val obj = element.jsonObject
                    val resultUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SearchResult(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: resultUrl,
                        url = resultUrl,
                        snippet = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        score = obj["score"]?.jsonPrimitive?.doubleOrNull
                    )
                }
            }
        }

    override suspend fun fetchContent(urls: List<String>): Map<String, String> =
        pageFetcher.fetch(urls)
}

/** Fetches readable page text on-device for providers that only return snippets. */
interface PageFetcher {
    suspend fun fetch(urls: List<String>): Map<String, String>
}

/**
 * OkHttp + Jsoup readability-lite extraction: drops scripts, styles and
 * page furniture, then takes the remaining text. Crude next to a hosted
 * extractor, but honest — what it returns is text really present on the
 * fetched page, and failures simply omit the URL.
 */
class JsoupPageFetcher(
    private val client: OkHttpClient
) : PageFetcher {

    override suspend fun fetch(urls: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val out = mutableMapOf<String, String>()
            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val html = response.body?.string().orEmpty()
                        if (html.isBlank()) return@use
                        val doc = Jsoup.parse(html, url)
                        doc.select("script, style, nav, footer, header, noscript, iframe, svg, form")
                            .remove()
                        val text = doc.body()?.text()?.replace(WHITESPACE, " ")?.trim().orEmpty()
                        if (text.isNotBlank()) out[url] = capFetchedText(text)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Page fetch failed for $url: ${e.message}")
                }
            }
            out
        }

    companion object {
        private const val TAG = "JsoupPageFetcher"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0 Mobile Safari/537.36 OpenDroidResearch/1.0"
        private val WHITESPACE = Regex("\\s+")
    }
}
