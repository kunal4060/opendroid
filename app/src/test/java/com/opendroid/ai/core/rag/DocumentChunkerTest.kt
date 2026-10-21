package com.opendroid.ai.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun `short text becomes a single chunk with its page ref`() {
        val chunks = DocumentChunker.chunk(listOf(TextSegment("Hello world.", pageRef = 3)))
        assertEquals(1, chunks.size)
        assertEquals("Hello world.", chunks[0].text)
        assertEquals(3, chunks[0].pageRef)
    }

    @Test
    fun `blank segments produce no chunks`() {
        val chunks = DocumentChunker.chunk(
            listOf(TextSegment("   \n\n  "), TextSegment(""))
        )
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `paragraphs pack up to the limit and never exceed it`() {
        val paragraph = "alpha ".repeat(30).trim() // ~180 chars
        val text = (1..10).joinToString("\n\n") { "$paragraph ($it)" }
        val chunks = DocumentChunker.chunk(
            listOf(TextSegment(text)),
            maxChars = 400,
            overlapChars = 60
        )
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue("chunk of ${chunk.text.length} exceeds 400", chunk.text.length <= 400)
        }
        // Every paragraph survives somewhere in the chunk set.
        val joined = chunks.joinToString(" ") { it.text }
        for (i in 1..10) {
            assertTrue("paragraph $i lost", joined.contains("($i)"))
        }
    }

    @Test
    fun `later chunks start with a tail of the previous chunk`() {
        val words = (1..120).map { "w$it" }.joinToString(" ")
        val chunks = DocumentChunker.chunk(
            listOf(TextSegment(words)),
            maxChars = 200,
            overlapChars = 50
        )
        assertTrue(chunks.size >= 2)
        // The second chunk must repeat words from the end of the first.
        val firstWords = chunks[0].text.split(" ").takeLast(3)
        assertTrue(
            "second chunk should overlap the first",
            firstWords.any { chunks[1].text.contains(it) }
        )
    }

    @Test
    fun `long paragraph is split on sentence boundaries`() {
        val sentence = "The quick brown fox jumps over the lazy dog again."
        val text = (1..30).joinToString(" ") { "$sentence [$it]" }
        val chunks = DocumentChunker.chunk(
            listOf(TextSegment(text)),
            maxChars = 300,
            overlapChars = 40
        )
        assertTrue(chunks.size >= 4)
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= 300)
        }
        val joined = chunks.joinToString(" ") { it.text }
        for (i in 1..30) assertTrue("sentence $i lost", joined.contains("[$i]"))
    }

    @Test
    fun `a single over-long sentence is hard split without losing content`() {
        val text = (1..500).map { "x$it" }.joinToString(" ")
        val chunks = DocumentChunker.chunk(
            listOf(TextSegment(text)),
            maxChars = 200,
            overlapChars = 0
        )
        assertTrue(chunks.size >= 5)
        for (chunk in chunks) assertTrue(chunk.text.length <= 200)
        val joined = chunks.joinToString(" ") { it.text }
        assertTrue(joined.contains("x1"))
        assertTrue(joined.contains("x500"))
    }

    @Test
    fun `chunks never span segments so page refs stay exact`() {
        val pageOne = "First page content. ".repeat(30).trim()
        val pageTwo = "Second page content. ".repeat(30).trim()
        val chunks = DocumentChunker.chunk(
            listOf(
                TextSegment(pageOne, pageRef = 1),
                TextSegment(pageTwo, pageRef = 2)
            ),
            maxChars = 200,
            overlapChars = 30
        )
        assertTrue(chunks.any { it.pageRef == 1 })
        assertTrue(chunks.any { it.pageRef == 2 })
        for (chunk in chunks) {
            if (chunk.pageRef == 1) {
                assertTrue(!chunk.text.contains("Second page"))
            } else {
                assertTrue(!chunk.text.contains("First page"))
            }
        }
    }

    @Test
    fun `whitespace runs are normalised inside chunks`() {
        val chunks = DocumentChunker.chunk(
            listOf(TextSegment("Line one.\nLine   two with    gaps."))
        )
        assertEquals(1, chunks.size)
        assertEquals("Line one. Line two with gaps.", chunks[0].text)
    }
}
