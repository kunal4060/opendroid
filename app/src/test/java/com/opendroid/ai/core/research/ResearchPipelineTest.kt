package com.opendroid.ai.core.research

import com.opendroid.ai.data.db.dao.ResearchDao
import com.opendroid.ai.data.db.entities.ResearchRunEntity
import com.opendroid.ai.data.db.entities.ResearchSourceEntity
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature D3: the pipeline's honesty guarantees.
 *
 *  - No key configured → NotConfigured, and NOTHING is persisted or
 *    fetched (no fabricated sources, ever).
 *  - A search with no citable results → NoSources whose text cites
 *    nothing, with the run row recording exactly that.
 *  - Happy path → the persisted sources are exactly the pages fetched,
 *    the answer's surviving markers all resolve to them, and a marker
 *    the model invented is stripped before the answer is stored.
 */
class ResearchPipelineTest {

    // ── Fakes ─────────────────────────────────────────────────────────

    private class FakeSettingsStore(
        var config: ResearchConfiguration
    ) : ResearchSettingsStore {
        override fun providerKind(): ResearchProviderKind = ResearchProviderKind.TAVILY
        override fun setProviderKind(kind: ResearchProviderKind) = true
        override fun tavilyApiKey(): String? = null
        override fun setTavilyApiKey(key: String?) = true
        override fun braveApiKey(): String? = null
        override fun setBraveApiKey(key: String?) = true
        override fun searxngInstanceUrl(): String? = null
        override fun setSearxngInstanceUrl(url: String?) = true
        override fun configuration(): ResearchConfiguration = config
        override fun clearAll() = true
    }

    private class FakeSearchProvider(
        var results: List<SearchResult> = emptyList(),
        var fetched: Map<String, String> = emptyMap(),
        var searchError: Exception? = null,
        var fetchError: Exception? = null
    ) : SearchProvider {
        var searchCalls = 0

        override val kind: ResearchProviderKind = ResearchProviderKind.TAVILY

        override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
            searchCalls++
            searchError?.let { throw it }
            return results.take(maxResults)
        }

        override suspend fun fetchContent(urls: List<String>): Map<String, String> {
            fetchError?.let { throw it }
            return fetched.filterKeys { it in urls }
        }
    }

    private class FakeAnswerer(var answerText: String) : ResearchAnswerer {
        var calls = 0
        var lastUserMessage: String? = null

        override suspend fun answer(
            systemPrompt: String,
            userMessage: String,
            configOverride: LLMConfig?
        ): String {
            calls++
            lastUserMessage = userMessage
            return answerText
        }
    }

    private class FakeResearchDao : ResearchDao {
        val insertedSources = mutableListOf<ResearchSourceEntity>()
        val insertedRuns = mutableListOf<ResearchRunEntity>()

        override suspend fun insertSources(sources: List<ResearchSourceEntity>) {
            insertedSources += sources
        }

        override suspend fun insertRun(run: ResearchRunEntity) {
            insertedRuns += run
        }

        override fun getSourcesForMessage(messageId: String): Flow<List<ResearchSourceEntity>> =
            flowOf(insertedSources.filter { it.messageId == messageId })

        override fun getAllSources(): Flow<List<ResearchSourceEntity>> = flowOf(insertedSources)

        override fun getRunsForSession(sessionId: String): Flow<List<ResearchRunEntity>> =
            flowOf(insertedRuns.filter { it.sessionId == sessionId })

        override fun getAllRuns(): Flow<List<ResearchRunEntity>> = flowOf(insertedRuns)

        override suspend fun deleteSourcesForMessage(messageId: String) {
            insertedSources.removeAll { it.messageId == messageId }
        }
    }

    private fun readyConfig() = ResearchConfiguration.Ready(
        kind = ResearchProviderKind.TAVILY, apiKey = "test-key", instanceUrl = null
    )

    private fun pipeline(
        store: FakeSettingsStore,
        provider: FakeSearchProvider,
        answerer: FakeAnswerer,
        dao: FakeResearchDao
    ) = ResearchPipeline(
        settingsStore = store,
        providerFactory = SearchProviderFactory { provider },
        answerer = answerer,
        researchDao = dao
    )

    // ── No-key behaviour ──────────────────────────────────────────────

    @Test
    fun `no key configured fails honestly and persists nothing`() = runBlocking {
        val store = FakeSettingsStore(
            ResearchConfiguration.NotConfigured(
                "Web research isn't set up yet. Add a Tavily API key in " +
                    "Settings → Web research (a free key works), then try again."
            )
        )
        val provider = FakeSearchProvider()
        val answerer = FakeAnswerer("must never be produced")
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, answerer, dao).run(
            query = "latest android news",
            sessionId = "session-1",
            messageId = "msg-1",
            runId = "run-1"
        )

        assertTrue(outcome is ResearchOutcome.NotConfigured)
        assertTrue(outcome.displayText.contains("Settings"))
        assertEquals("the search provider must not be touched", 0, provider.searchCalls)
        assertEquals("the model must not be asked to improvise", 0, answerer.calls)
        assertTrue("no sources may be fabricated", dao.insertedSources.isEmpty())
        assertTrue(dao.insertedRuns.isEmpty())
    }

    // ── Empty / unusable search ───────────────────────────────────────

    @Test
    fun `search with no results answers with no citations and records the run`() = runBlocking {
        val store = FakeSettingsStore(readyConfig())
        val provider = FakeSearchProvider(results = emptyList())
        val answerer = FakeAnswerer("must never be produced")
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, answerer, dao).run(
            query = "obscure question", sessionId = "s", messageId = "m", runId = "r"
        )

        assertTrue(outcome is ResearchOutcome.NoSources)
        assertTrue(CitationText.citedPositions(outcome.displayText).isEmpty())
        assertEquals(0, answerer.calls)
        assertTrue(dao.insertedSources.isEmpty())
        assertEquals(1, dao.insertedRuns.size)
        assertEquals(outcome.displayText, dao.insertedRuns.single().answerText)
    }

    @Test
    fun `results with no readable text and no snippet are not citable`() = runBlocking {
        val store = FakeSettingsStore(readyConfig())
        val provider = FakeSearchProvider(
            results = listOf(SearchResult(title = "Empty page", url = "https://x.example/", snippet = "")),
            fetched = emptyMap()
        )
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, FakeAnswerer("unused"), dao).run(
            query = "q", sessionId = "s", messageId = "m", runId = "r"
        )

        assertTrue(outcome is ResearchOutcome.NoSources)
        assertTrue(dao.insertedSources.isEmpty())
    }

    @Test
    fun `search failure is an honest failure with nothing cited`() = runBlocking {
        val store = FakeSettingsStore(readyConfig())
        val provider = FakeSearchProvider(
            searchError = ResearchException("Tavily refused the API key. Check the key in Settings → Web research.")
        )
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, FakeAnswerer("unused"), dao).run(
            query = "q", sessionId = "s", messageId = "m", runId = "r"
        )

        assertTrue(outcome is ResearchOutcome.Failed)
        assertTrue(dao.insertedSources.isEmpty())
    }

    // ── Happy path: citations true by construction ────────────────────

    @Test
    fun `answer markers are validated against exactly the stored sources`() = runBlocking {
        val store = FakeSettingsStore(readyConfig())
        val provider = FakeSearchProvider(
            results = listOf(
                SearchResult("Alpha report", "https://a.example/report", "alpha snippet"),
                SearchResult("Beta blog", "https://b.example/post", "beta snippet")
            ),
            fetched = mapOf(
                "https://a.example/report" to "Full text about alpha.",
                "https://b.example/post" to "Full text about beta."
            )
        )
        // The model cites both real sources — and invents a third.
        val answerer = FakeAnswerer("Alpha shipped [1]. Beta followed [2]. Gamma is rumoured [9].")
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, answerer, dao).run(
            query = "alpha and beta", sessionId = "session-9", messageId = "msg-9", runId = "run-9"
        )

        assertTrue(outcome is ResearchOutcome.Answer)
        val answer = outcome as ResearchOutcome.Answer

        // The invented [9] was stripped before the answer was stored/shown.
        assertEquals(1, answer.strippedMarkers)
        assertFalse(answer.text.contains("[9]"))
        assertTrue(answer.text.contains("[1]"))
        assertTrue(answer.text.contains("[2]"))

        // The stored sources are exactly the fetched pages, in order,
        // under this run's message id.
        assertEquals(2, dao.insertedSources.size)
        assertEquals(listOf(1, 2), dao.insertedSources.map { it.position })
        assertEquals(
            listOf("https://a.example/report", "https://b.example/post"),
            dao.insertedSources.map { it.url }
        )
        assertTrue(dao.insertedSources.all { it.messageId == "msg-9" })
        assertTrue(dao.insertedSources.all { it.contentHash.length == 64 })
        assertEquals(dao.insertedSources, answer.sources)

        // Every marker in the final answer resolves to a stored source.
        val cited = CitationText.citedPositions(answer.text)
        assertTrue(cited.isNotEmpty())
        assertTrue(cited.all { pos -> dao.insertedSources.any { it.position == pos } })

        // The run row records the validated text verbatim (the UI's
        // containment link to the summary message depends on it).
        assertEquals(1, dao.insertedRuns.size)
        assertEquals(answer.text, dao.insertedRuns.single().answerText)
        assertEquals("run-9", dao.insertedRuns.single().runId)

        // The model was grounded on numbered blocks for both pages.
        assertTrue(answerer.lastUserMessage!!.contains("[1] Alpha report"))
        assertTrue(answerer.lastUserMessage!!.contains("Full text about alpha."))
    }

    @Test
    fun `snippet grounds a source when page fetch fails`() = runBlocking {
        val store = FakeSettingsStore(readyConfig())
        val provider = FakeSearchProvider(
            results = listOf(
                SearchResult("Snippet only", "https://s.example/", "the snippet text itself")
            ),
            fetched = emptyMap() // fetch failed for every URL
        )
        val answerer = FakeAnswerer("From the snippet [1].")
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, answerer, dao).run(
            query = "q", sessionId = "s", messageId = "m", runId = "r"
        )

        assertTrue(outcome is ResearchOutcome.Answer)
        assertEquals(1, dao.insertedSources.size)
        assertEquals(
            CitationText.contentHash("the snippet text itself"),
            dao.insertedSources.single().contentHash
        )
    }

    @Test
    fun `duplicate urls collapse into one source`() = runBlocking {
        val store = FakeSettingsStore(readyConfig())
        val provider = FakeSearchProvider(
            results = listOf(
                SearchResult("Same page", "https://dup.example/", "one"),
                SearchResult("Same page again", "https://dup.example/", "one")
            ),
            fetched = mapOf("https://dup.example/" to "page text")
        )
        val answerer = FakeAnswerer("Answer [1].")
        val dao = FakeResearchDao()

        val outcome = pipeline(store, provider, answerer, dao).run(
            query = "q", sessionId = "s", messageId = "m", runId = "r"
        )

        assertTrue(outcome is ResearchOutcome.Answer)
        assertEquals(1, dao.insertedSources.size)
        assertEquals(1, dao.insertedSources.single().position)
    }
}
