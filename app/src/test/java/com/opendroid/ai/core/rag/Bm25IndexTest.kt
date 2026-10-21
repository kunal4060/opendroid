package com.opendroid.ai.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25IndexTest {

    private fun chunk(id: String, index: Int, text: String, doc: String = "Facts.txt") =
        RagChunk(
            id = id,
            documentId = "doc-$doc",
            documentName = doc,
            chunkIndex = index,
            pageRef = null,
            text = text
        )

    // A corpus whose second chunk holds a unique invented fact: no model
    // or general knowledge could produce "Zorblaxian … vault K-9" — only
    // real retrieval over these chunks can surface it.
    private val spoons = chunk(
        "c1", 0,
        "The Zorblaxian embassy keeps its ceremonial spoons in vault K-9 " +
            "beneath the east wing, next to the winter wine cellar."
    )
    private val garden = chunk(
        "c2", 1,
        "The embassy garden grows tomatoes, basil and courgettes for the " +
            "summer kitchen, watered every morning at dawn."
    )
    private val vaultDoors = chunk(
        "c3", 2,
        "Vault doors in the west annex are painted green and serviced " +
            "every spring by the maintenance guild."
    )
    private val corpus = listOf(spoons, garden, vaultDoors)

    @Test
    fun `unique invented fact is the top hit for its question`() {
        val hits = Bm25Index(corpus).search("Where does the Zorblaxian embassy keep its ceremonial spoons?")
        assertTrue(hits.isNotEmpty())
        assertEquals("c1", hits.first().chunk.id)
    }

    @Test
    fun `rare term beats a chunk sharing only a common term`() {
        // "vault" appears in c1 and c3; "zorblaxian" only in c1 and must
        // dominate via inverse document frequency.
        val hits = Bm25Index(corpus).search("zorblaxian vault")
        assertEquals("c1", hits.first().chunk.id)
        assertTrue(hits.first().score > (hits.getOrNull(1)?.score ?: 0.0))
    }

    @Test
    fun `unrelated question retrieves nothing`() {
        val hits = Bm25Index(corpus).search("quantum entanglement lecture schedule")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `garden question retrieves the garden chunk`() {
        val hits = Bm25Index(corpus).search("what grows in the embassy garden")
        assertEquals("c2", hits.first().chunk.id)
    }

    @Test
    fun `limit is honoured and empty index retrieves nothing`() {
        assertTrue(Bm25Index(emptyList()).search("spoons").isEmpty())
        val many = (1..12).map { chunk("m$it", it, "shared topic token entry $it") }
        assertEquals(3, Bm25Index(many).search("shared topic", limit = 3).size)
    }

    @Test
    fun `code-like tokens stay findable`() {
        val hits = Bm25Index(corpus).search("K-9")
        assertEquals("c1", hits.first().chunk.id)
    }

    @Test
    fun `citation label uses chunk position for text documents`() {
        assertEquals("Facts.txt, chunk 1", spoons.citationLabel())
        val paged = spoons.copy(pageRef = 7)
        assertEquals("Facts.txt, page 7", paged.citationLabel())
    }
}
