package com.opendroid.ai.core.research

import com.opendroid.ai.data.db.entities.ResearchSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature D3: citation markers are only meaningful against stored sources.
 * These tests pin the construction guarantee — after [CitationText
 * .validate], every marker left in an answer resolves to a stored source
 * position, and [CitationText.linkify] only ever links resolvable markers.
 */
class CitationTextTest {

    private fun source(position: Int, url: String) = ResearchSourceEntity(
        id = position.toLong(),
        messageId = "msg-1",
        position = position,
        url = url,
        title = "Source $position",
        snippet = "snippet",
        fetchedAt = 1_000L,
        contentHash = "hash"
    )

    @Test
    fun `validate keeps markers that resolve to stored sources`() {
        val result = CitationText.validate("Alpha happened [1]. Beta followed [2].", sourceCount = 2)
        assertEquals("Alpha happened [1]. Beta followed [2].", result.text)
        assertEquals(0, result.strippedMarkers)
    }

    @Test
    fun `validate strips fabricated markers and counts them`() {
        val result = CitationText.validate(
            "Real claim [1]. Invented claim [7]. Also invented [12].",
            sourceCount = 2
        )
        assertEquals(2, result.strippedMarkers)
        assertFalse(result.text.contains("[7]"))
        assertFalse(result.text.contains("[12]"))
        assertTrue(result.text.contains("[1]"))
    }

    @Test
    fun `every marker surviving validation resolves to a stored position`() {
        val storedPositions = (1..3).toSet()
        val answer = "One [1], two [2], three [3], four [4], zero [0], big [99]."
        val validated = CitationText.validate(answer, sourceCount = storedPositions.size)
        val cited = CitationText.citedPositions(validated.text)
        assertTrue("cited $cited must all be stored positions", cited.all { it in storedPositions })
        assertEquals(setOf(1, 2, 3), cited)
    }

    @Test
    fun `validate with no sources strips every marker`() {
        val result = CitationText.validate("Nothing can be cited [1] or [2].", sourceCount = 0)
        assertEquals(2, result.strippedMarkers)
        assertTrue(CitationText.citedPositions(result.text).isEmpty())
    }

    @Test
    fun `validate leaves non-citation brackets alone`() {
        val result = CitationText.validate("An array index a[0] and a range [1-2] stay.", sourceCount = 1)
        // a[0]: marker regex matches [0], which does not resolve (positions
        // are 1-based) — it is stripped like any unresolvable marker.
        assertFalse(result.text.contains("[0]"))
        // [1-2] is not a marker shape and must survive untouched.
        assertTrue(result.text.contains("[1-2]"))
    }

    @Test
    fun `linkify links only markers with a stored source`() {
        val sources = listOf(
            source(1, "https://example.org/a"),
            source(2, "https://example.org/b")
        )
        val linked = CitationText.linkify("First [1], second [2], phantom [5].", sources)
        assertTrue(linked.contains("[[1]](https://example.org/a)"))
        assertTrue(linked.contains("[[2]](https://example.org/b)"))
        // No stored source at position 5: the marker stays plain text and
        // gains no link — a citation can never point at an unfetched page.
        assertTrue(linked.contains("[5]"))
        assertFalse(linked.contains("(https://example.org/") && linked.contains("[5]]("))
    }

    @Test
    fun `linkify with no sources returns the text unchanged`() {
        val text = "Plain answer [1] with no stored sources."
        assertEquals(text, CitationText.linkify(text, emptyList()))
    }

    @Test
    fun `citedPositions reads all markers`() {
        assertEquals(setOf(1, 2, 10), CitationText.citedPositions("a [1] b [2] c [10] d [1]"))
        assertTrue(CitationText.citedPositions("no markers here").isEmpty())
    }

    @Test
    fun `contentHash is a stable sha-256 hex`() {
        val hash = CitationText.contentHash("the exact text the model saw")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hash, CitationText.contentHash("the exact text the model saw"))
        assertFalse(hash == CitationText.contentHash("different text"))
    }
}
