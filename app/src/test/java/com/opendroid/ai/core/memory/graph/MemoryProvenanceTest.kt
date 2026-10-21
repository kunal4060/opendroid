package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProvenanceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val formatDate: (Long) -> String = { millis -> "DATE($millis)" }

    private fun node(
        id: String = "pattern_app_spotify",
        origin: MemoryOrigin? = null,
        accessCount: Int = 1,
        tier: MemoryTier = MemoryTier.LEARNED_PATTERN
    ) = KnowledgeNode(
        id = id,
        label = "Spotify",
        category = KnowledgeCategory.APP_PREFERENCE,
        tier = tier,
        summary = "Preferred app",
        confidence = 0.85f,
        accessCount = accessCount,
        lastUpdated = 1_760_100_000_000L,
        origin = origin
    )

    // ── Serialisation compatibility ─────────────────────────────────────

    @Test
    fun `legacy node json without origin field decodes with null origin`() {
        // Exact shape written by the pre-provenance KnowledgeNode.
        val legacyJson = """
            {"id":"explicit_coffee_1760000000000","label":"Coffee","category":"NOTE_FACT",
             "tier":"LONG_TERM","summary":"Likes filter coffee","properties":{},
             "confidence":1.0,"accessCount":1,"lastUpdated":1760000000000,"ttlHours":-1}
        """.trimIndent()

        val decoded = json.decodeFromString<KnowledgeNode>(legacyJson)

        assertEquals("explicit_coffee_1760000000000", decoded.id)
        assertNull(decoded.origin)
    }

    @Test
    fun `node with origin survives serialisation round trip`() {
        val original = node(
            origin = MemoryOrigin(
                kind = OriginKind.CHAT_EXTRACTION,
                detail = "Extracted automatically from your chat messages",
                sessionId = "session-42",
                createdAt = 1_760_000_000_000L
            )
        )

        val decoded = json.decodeFromString<KnowledgeNode>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(OriginKind.CHAT_EXTRACTION, decoded.origin?.kind)
        assertEquals("session-42", decoded.origin?.sessionId)
    }

    // ── Derived creation time (the one allowed, labelled inference) ─────

    @Test
    fun `derivedCreatedAtMillis reads timestamp embedded in explicit node ids`() {
        assertEquals(
            1_760_000_000_000L,
            MemoryProvenance.derivedCreatedAtMillis("explicit_coffee_1760000000000")
        )
    }

    @Test
    fun `derivedCreatedAtMillis rejects non-explicit and implausible ids`() {
        assertNull(MemoryProvenance.derivedCreatedAtMillis("pattern_app_spotify"))
        assertNull(MemoryProvenance.derivedCreatedAtMillis("explicit_broken_notanumber"))
        assertNull(MemoryProvenance.derivedCreatedAtMillis("explicit_tiny_123"))
        assertNull(MemoryProvenance.derivedCreatedAtMillis("chatfact_user_name"))
    }

    // ── Headlines ───────────────────────────────────────────────────────

    @Test
    fun `headline is honestly unrecorded when origin is missing`() {
        val headline = MemoryProvenance.headline(node(origin = null), formatDate)
        assertTrue(headline.startsWith("Origin not recorded"))
    }

    @Test
    fun `headline for legacy explicit node adds labelled derived date`() {
        val legacy = node(id = "explicit_coffee_1760000000000", origin = null, tier = MemoryTier.LONG_TERM)
        val headline = MemoryProvenance.headline(legacy, formatDate)
        assertTrue(headline.startsWith("Origin not recorded"))
        assertTrue(headline.contains("saved around DATE(1760000000000)"))
        assertTrue(headline.contains("derived from its record id"))
    }

    @Test
    fun `headline for user explicit origin names the ask and date`() {
        val headline = MemoryProvenance.headline(
            node(
                origin = MemoryOrigin(OriginKind.USER_EXPLICIT, createdAt = 1_760_000_000_000L),
                tier = MemoryTier.LONG_TERM
            ),
            formatDate
        )
        assertEquals("You asked me to remember this on DATE(1760000000000).", headline)
    }

    @Test
    fun `headline for chat extraction names chat and date`() {
        val headline = MemoryProvenance.headline(
            node(
                origin = MemoryOrigin(
                    OriginKind.CHAT_EXTRACTION,
                    sessionId = "s1",
                    createdAt = 1_760_000_000_000L
                ),
                tier = MemoryTier.LONG_TERM
            ),
            formatDate
        )
        assertEquals("I learned this from your chat on DATE(1760000000000).", headline)
    }

    @Test
    fun `headline for app usage includes detail and observation count`() {
        val headline = MemoryProvenance.headline(
            node(
                origin = MemoryOrigin(
                    OriginKind.APP_USAGE,
                    detail = "Music Playback",
                    createdAt = 1_760_000_000_000L
                ),
                accessCount = 14
            ),
            formatDate
        )
        assertTrue(headline.contains("app usage"))
        assertTrue(headline.contains("Music Playback"))
        assertTrue(headline.contains("observed 14 times"))
    }

    @Test
    fun `headline for user entered origin credits the memory screen`() {
        val headline = MemoryProvenance.headline(
            node(
                origin = MemoryOrigin(OriginKind.USER_ENTERED, createdAt = 1_760_000_000_000L),
                tier = MemoryTier.LONG_TERM
            ),
            formatDate
        )
        assertTrue(headline.contains("You added this yourself in the Memory screen"))
    }
}
