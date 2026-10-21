package com.opendroid.ai.core.research

import android.util.Log
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.prompts.ResearchPrompts
import com.opendroid.ai.data.db.dao.ResearchDao
import com.opendroid.ai.data.db.entities.ResearchRunEntity
import com.opendroid.ai.data.db.entities.ResearchSourceEntity
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** The terminal state of one research run. Every branch is honest about what happened. */
sealed interface ResearchOutcome {
    /** A grounded answer; [sources] are the stored rows its `[n]` markers resolve to. */
    data class Answer(
        val text: String,
        val sources: List<ResearchSourceEntity>,
        val strippedMarkers: Int
    ) : ResearchOutcome

    /** No provider/key configured — the message tells the user exactly where to set it up. */
    data class NotConfigured(val message: String) : ResearchOutcome

    /** The search ran but produced nothing citable — the answer cites nothing. */
    data class NoSources(val message: String) : ResearchOutcome

    /** Search or the model call failed; the message is user-readable. */
    data class Failed(val message: String) : ResearchOutcome

    /** The text to show the user for any outcome. */
    val displayText: String
        get() = when (this) {
            is Answer -> text
            is NotConfigured -> message
            is NoSources -> message
            is Failed -> message
        }
}

/** Builds the [SearchProvider] for a resolved configuration. A seam so tests can substitute fakes. */
fun interface SearchProviderFactory {
    fun create(config: ResearchConfiguration.Ready): SearchProvider
}

/** Production factory; bound in AppModule (not constructor-injected, to keep one binding). */
class DefaultSearchProviderFactory(
    private val client: OkHttpClient
) : SearchProviderFactory {
    override fun create(config: ResearchConfiguration.Ready): SearchProvider {
        // Extract calls fetch several pages at once; give research a longer
        // read window than the app's shared 15s client.
        val researchClient = client.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
        return when (config.kind) {
            ResearchProviderKind.TAVILY ->
                TavilySearchProvider(researchClient, requireNotNull(config.apiKey))
            ResearchProviderKind.BRAVE ->
                BraveSearchProvider(researchClient, requireNotNull(config.apiKey))
            ResearchProviderKind.SEARXNG ->
                SearxngSearchProvider(researchClient, requireNotNull(config.instanceUrl))
        }
    }
}

/** Produces the grounded answer from a system prompt + assembled source blocks. */
interface ResearchAnswerer {
    suspend fun answer(systemPrompt: String, userMessage: String, configOverride: LLMConfig?): String
}

/** Production answerer over the active LLM provider; bound in AppModule. */
class LlmResearchAnswerer(
    private val llmProviderFactory: dagger.Lazy<LLMProviderFactory>
) : ResearchAnswerer {
    override suspend fun answer(
        systemPrompt: String,
        userMessage: String,
        configOverride: LLMConfig?
    ): String {
        val provider = llmProviderFactory.get().getActiveProvider(configOverride)
        val response = provider.complete(
            LLMRequest(
                systemPrompt = systemPrompt,
                messages = listOf(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = userMessage,
                        sender = ChatMessage.Sender.USER
                    )
                ),
                temperature = 0.3f,
                maxTokens = 2500,
                responseFormat = ResponseFormat.TEXT
            )
        )
        return response.content
    }
}

/**
 * The research pipeline (feature D3): search → fetch → ground → validate →
 * persist, in that order, so citations are true by construction:
 *
 *  1. The configured [SearchProvider] runs the user's query.
 *  2. The top results' page text is fetched (provider extract API, or
 *     on-device Jsoup for snippet-only providers). A source whose fetch
 *     failed keeps its search snippet as its grounding text — it is still
 *     text the provider actually returned in this run.
 *  3. The model answers from numbered source blocks under a prompt that
 *     requires a `[n]` marker on every factual claim.
 *  4. [CitationText.validate] strips any marker that doesn't resolve to
 *     one of this run's sources.
 *  5. The sources are stored as [ResearchSourceEntity] rows (with the
 *     hash of the exact text the model saw) under the caller's
 *     [messageId], plus a [ResearchRunEntity] audit row — BEFORE the
 *     answer is returned, so the UI can only ever render citations that
 *     resolve to a stored row.
 *
 * With no key configured the pipeline returns
 * [ResearchOutcome.NotConfigured] without touching the network — it never
 * falls back to an ungrounded answer dressed up as research.
 */
@Singleton
class ResearchPipeline @Inject constructor(
    private val settingsStore: ResearchSettingsStore,
    private val providerFactory: SearchProviderFactory,
    private val answerer: ResearchAnswerer,
    private val researchDao: ResearchDao
) {

    suspend fun run(
        query: String,
        sessionId: String,
        messageId: String,
        runId: String,
        configOverride: LLMConfig? = null
    ): ResearchOutcome {
        val config = settingsStore.configuration()
        if (config is ResearchConfiguration.NotConfigured) {
            return ResearchOutcome.NotConfigured(config.reason)
        }
        val ready = config as ResearchConfiguration.Ready
        val provider = providerFactory.create(ready)

        val results = try {
            provider.search(query, MAX_SEARCH_RESULTS)
        } catch (e: ResearchException) {
            return ResearchOutcome.Failed(e.message ?: "Web search failed.")
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for provider ${ready.kind}", e)
            return ResearchOutcome.Failed(
                "Web research failed before any results came back (${e.message ?: "network error"}). " +
                    "Nothing was cited — try again when the connection is back."
            )
        }

        // Duplicate URLs (the same page via two result slots) are one source.
        val uniqueResults = results.distinctBy { it.url }.take(MAX_SOURCES)
        if (uniqueResults.isEmpty()) {
            val message = "I searched the web for \"$query\" but the search came back with no " +
                "results, so there is nothing to cite for this one."
            persistRun(runId, sessionId, messageId, query, message)
            return ResearchOutcome.NoSources(message)
        }

        val fetched = try {
            provider.fetchContent(uniqueResults.map { it.url })
        } catch (e: Exception) {
            // fetchContent contracts to omit failures, not throw — but a
            // throwing implementation must shrink the set, not kill the run.
            Log.w(TAG, "fetchContent threw; continuing with snippets only", e)
            emptyMap()
        }

        val fetchedAt = System.currentTimeMillis()
        val grounded = uniqueResults.mapNotNull { result ->
            val text = fetched[result.url]?.takeIf { it.isNotBlank() } ?: result.snippet
            if (text.isBlank()) {
                // No fetched text and no snippet: this result cannot ground
                // a claim, so it never becomes a citable source.
                null
            } else {
                GroundedSource(result = result, modelText = text.take(MAX_SOURCE_TEXT_CHARS))
            }
        }
        if (grounded.isEmpty()) {
            val message = "I found pages for \"$query\" but none of them could be read, so I " +
                "can't cite anything for this one. Try rephrasing the question."
            persistRun(runId, sessionId, messageId, query, message)
            return ResearchOutcome.NoSources(message)
        }

        val blocks = grounded.mapIndexed { index, source ->
            ResearchPrompts.sourceBlock(
                position = index + 1,
                title = source.result.title,
                url = source.result.url,
                text = source.modelText
            )
        }
        val rawAnswer = try {
            answerer.answer(
                systemPrompt = ResearchPrompts.GROUNDED_ANSWER_SYSTEM,
                userMessage = ResearchPrompts.groundedUserMessage(query, blocks),
                configOverride = configOverride
            )
        } catch (e: Exception) {
            Log.e(TAG, "Grounded answer call failed", e)
            return ResearchOutcome.Failed(
                "The web search worked, but the model call failed (${e.message ?: "unknown error"}), " +
                    "so no answer was written. Nothing was cited."
            )
        }

        val validation = CitationText.validate(rawAnswer, grounded.size)
        if (validation.strippedMarkers > 0) {
            Log.w(
                TAG,
                "Stripped ${validation.strippedMarkers} citation marker(s) that did not " +
                    "resolve to a fetched source"
            )
        }

        val entities = grounded.mapIndexed { index, source ->
            ResearchSourceEntity(
                messageId = messageId,
                position = index + 1,
                url = source.result.url,
                title = source.result.title,
                snippet = source.result.snippet,
                fetchedAt = fetchedAt,
                contentHash = CitationText.contentHash(source.modelText)
            )
        }
        researchDao.insertSources(entities)
        persistRun(runId, sessionId, messageId, query, validation.text)
        return ResearchOutcome.Answer(
            text = validation.text,
            sources = entities,
            strippedMarkers = validation.strippedMarkers
        )
    }

    private suspend fun persistRun(
        runId: String,
        sessionId: String,
        messageId: String,
        query: String,
        answerText: String
    ) {
        researchDao.insertRun(
            ResearchRunEntity(
                runId = runId,
                sessionId = sessionId,
                messageId = messageId,
                query = query,
                answerText = answerText,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private data class GroundedSource(val result: SearchResult, val modelText: String)

    companion object {
        private const val TAG = "ResearchPipeline"
        const val MAX_SEARCH_RESULTS = 6
        const val MAX_SOURCES = 4

        /** Per-source grounding-text cap, so four big pages can't blow a small model's context. */
        const val MAX_SOURCE_TEXT_CHARS = 6_000
    }
}
