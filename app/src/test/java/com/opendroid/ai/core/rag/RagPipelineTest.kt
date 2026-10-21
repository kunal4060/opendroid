package com.opendroid.ai.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checklist's acceptance probe, at the retrieval layer: a document
 * containing a unique invented fact is indexed (extract → chunk → store
 * shape), a question about the fact must surface that chunk with its
 * citation, and after the document is removed the fact must stop being
 * retrievable. Runs over the lexical path; the semantic path is covered
 * by [SemanticRetrieverTest].
 */
class RagPipelineTest {

    private val handbookText = """
        Chapter one covers the onboarding week. New analysts receive a desk,
        a laptop, and a mentor for their first month in the department.

        The Zorblaxian embassy keeps its ceremonial spoons in vault K-9
        beneath the east wing, next to the winter wine cellar. Only the
        Keeper of Cutlery may open the vault, on the first Monday of spring.

        Chapter three explains travel claims. Receipts must be submitted
        within thirty days, and hotel stays are capped per night.
    """.trimIndent()

    private fun indexDocument(docId: String, name: String, text: String): List<RagChunk> {
        val drafts = DocumentChunker.chunk(listOf(TextSegment(text, pageRef = 4)))
        return drafts.mapIndexed { i, draft ->
            RagChunk(
                id = "$docId:$i",
                documentId = docId,
                documentName = name,
                chunkIndex = i,
                pageRef = draft.pageRef,
                text = draft.text
            )
        }
    }

    @Test
    fun `invented fact is retrieved with its citation after indexing`() {
        val chunks = indexDocument("handbook", "Handbook.pdf", handbookText) +
            indexDocument("travel", "Travel.txt", "Hotels are booked through the travel desk.")
        val hits = Bm25Index(chunks).search(
            "Where are the ceremonial spoons of the Zorblaxian embassy kept?"
        )
        assertTrue(hits.isNotEmpty())
        val top = hits.first().chunk
        assertTrue(top.text.contains("vault K-9"))
        assertEquals("Handbook.pdf, page 4", top.citationLabel())
    }

    @Test
    fun `after the document is removed the fact is no longer retrievable`() {
        val chunks = indexDocument("handbook", "Handbook.pdf", handbookText)
        val remaining = chunks.filterNot { it.documentId == "handbook" }
        val hits = Bm25Index(remaining).search("ceremonial spoons Zorblaxian vault")
        assertTrue(hits.isEmpty())
    }
}
