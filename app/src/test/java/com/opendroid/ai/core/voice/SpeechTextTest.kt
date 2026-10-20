package com.opendroid.ai.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-aloud text preparation: markdown stripping and speech chunking. */
class SpeechTextTest {

    @Test
    fun `stripMarkdown removes bold and italic markers`() {
        assertEquals(
            "This is bold and italic text.",
            SpeechText.stripMarkdown("This is **bold** and *italic* text.")
        )
    }

    @Test
    fun `stripMarkdown removes headings quotes and list markers`() {
        val md = "# Title\n> quoted line\n- first\n- second\n1. numbered"
        assertEquals(
            "Title\nquoted line\nfirst\nsecond\nnumbered",
            SpeechText.stripMarkdown(md)
        )
    }

    @Test
    fun `stripMarkdown keeps link text and drops url and images`() {
        val md = "See [the docs](https://example.com/docs) ![diagram](img.png) now."
        assertEquals("See the docs now.", SpeechText.stripMarkdown(md))
    }

    @Test
    fun `stripMarkdown drops fence lines but keeps code`() {
        val md = "Run this:\n```kotlin\nval x = 1\n```\nDone."
        assertEquals("Run this:\nval x = 1\nDone.", SpeechText.stripMarkdown(md))
    }

    @Test
    fun `stripMarkdown flattens tables`() {
        val md = "| Name | Age |\n| --- | --- |\n| Ada | 36 |"
        val spoken = SpeechText.stripMarkdown(md)
        assertTrue(spoken.contains("Name"))
        assertTrue(spoken.contains("Ada"))
        assertTrue(!spoken.contains("|"))
        assertTrue(!spoken.contains("---"))
    }

    @Test
    fun `chunkForSpeech keeps short text as one chunk`() {
        assertEquals(listOf("Hello world."), SpeechText.chunkForSpeech("Hello world."))
    }

    @Test
    fun `chunkForSpeech returns empty for blank text`() {
        assertEquals(emptyList<String>(), SpeechText.chunkForSpeech("   "))
    }

    @Test
    fun `chunkForSpeech respects max length and sentence boundaries`() {
        val text = "One two three. Four five six. Seven eight nine."
        val chunks = SpeechText.chunkForSpeech(text, maxChars = 20)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 20 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `chunkForSpeech splits an overlong sentence on word boundaries`() {
        val text = "alpha beta gamma delta epsilon zeta eta theta iota"
        val chunks = SpeechText.chunkForSpeech(text, maxChars = 20)
        assertTrue(chunks.all { it.length <= 20 })
        assertEquals(text, chunks.joinToString(" "))
    }
}
