package com.opendroid.ai.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {

    @Test
    fun `cosine of identical vectors is one`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0, VectorMath.cosine(v, v), 1e-9)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertEquals(
            0.0,
            VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-9
        )
    }

    @Test
    fun `cosine of opposite vectors is minus one`() {
        assertEquals(
            -1.0,
            VectorMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)),
            1e-9
        )
    }

    @Test
    fun `zero vector has zero cosine with anything`() {
        assertEquals(
            0.0,
            VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)),
            1e-9
        )
    }

    @Test
    fun `encode decode round trip is exact`() {
        val original = floatArrayOf(0.5f, -1.25f, 3.14159f, 0f, 123456.75f)
        val restored = VectorMath.decode(VectorMath.encode(original))
        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(original[i], restored[i], 0.0f)
        }
    }
}

class SemanticRetrieverTest {

    private val modelId = "OpenAI|text-embedding-3-small"

    private fun chunk(id: String, vector: FloatArray?, model: String? = modelId) =
        RagChunk(
            id = id,
            documentId = "doc",
            documentName = "Facts.txt",
            chunkIndex = id.removePrefix("c").toIntOrNull() ?: 0,
            pageRef = null,
            text = "chunk $id",
            embedding = vector?.let { VectorMath.encode(it) },
            embeddingModelId = if (vector == null) null else model
        )

    @Test
    fun `closest vector ranks first`() {
        val fact = chunk("c1", floatArrayOf(1f, 0.05f, 0f))
        val decoy = chunk("c2", floatArrayOf(0f, 1f, 0f))
        val mid = chunk("c3", floatArrayOf(0.7f, 0.7f, 0f))
        val hits = SemanticRetriever.rank(
            listOf(decoy, mid, fact),
            queryVector = floatArrayOf(1f, 0f, 0f),
            queryModelId = modelId
        )
        assertEquals("c1", hits.first().chunk.id)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun `floor drops unrelated chunks`() {
        val unrelated = chunk("c1", floatArrayOf(0f, 0f, 1f))
        val hits = SemanticRetriever.rank(
            listOf(unrelated),
            queryVector = floatArrayOf(1f, 0f, 0f),
            queryModelId = modelId
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `vectors from another model are never compared`() {
        // Identical direction, wrong space: must not match.
        val foreign = chunk("c1", floatArrayOf(1f, 0f), model = "Other|model")
        val hits = SemanticRetriever.rank(
            listOf(foreign),
            queryVector = floatArrayOf(1f, 0f),
            queryModelId = modelId
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `chunks without vectors are skipped`() {
        val bare = chunk("c1", null)
        val hits = SemanticRetriever.rank(
            listOf(bare),
            queryVector = floatArrayOf(1f),
            queryModelId = modelId
        )
        assertTrue(hits.isEmpty())
    }
}
