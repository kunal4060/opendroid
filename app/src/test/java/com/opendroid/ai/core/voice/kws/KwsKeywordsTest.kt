package com.opendroid.ai.core.voice.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwsKeywordsTest {

    /** Mini lexicon in the exact `en.phone` format of the bundled model. */
    private val lexicon = PhoneLexicon.parse(
        sequenceOf(
            "OPEN OW1 P AH0 N",
            "DROID D R OY1 D",
            "HEY HH EY1",
            "LIGHT L AY1 T",
            "'BOUT B AW1 T",
            "OPEN(2) OW1 P AH0 N", // alternate-pronunciation marker: same word
            "",
            "BROKENLINE"
        )
    )

    @Test
    fun `lexicon parses words and first pronunciation wins`() {
        assertEquals(5, lexicon.size)
        assertEquals(listOf("OW1", "P", "AH0", "N"), lexicon.pronunciationOf("OPEN"))
        assertTrue(lexicon.contains("DROID"))
        assertFalse(lexicon.contains("XYLOPHONE"))
    }

    @Test
    fun `wordsOf normalises case hyphens and punctuation`() {
        assertEquals(listOf("HEY", "OPEN", "DROID"), WakeKeywordFactory.wordsOf("hey, open-droid!"))
        assertEquals(listOf("OPEN", "DROID"), WakeKeywordFactory.wordsOf("  Open   Droid "))
        assertEquals(emptyList<String>(), WakeKeywordFactory.wordsOf("   "))
    }

    @Test
    fun `default phrase builds phrase line plus hey variant`() {
        val result = WakeKeywordFactory.buildLines("Open Droid", lexicon)
        assertTrue(result is KeywordBuildResult.Ready)
        val ready = result as KeywordBuildResult.Ready
        assertEquals("OPEN_DROID", ready.displayName)
        assertEquals(
            listOf(
                "OW1 P AH0 N D R OY1 D @OPEN_DROID",
                "HH EY1 OW1 P AH0 N D R OY1 D @HEY_OPEN_DROID"
            ),
            ready.lines
        )
    }

    @Test
    fun `phrase already starting with hey gets no duplicate variant`() {
        val result = WakeKeywordFactory.buildLines("Hey Open Droid", lexicon) as KeywordBuildResult.Ready
        assertEquals(listOf("HH EY1 OW1 P AH0 N D R OY1 D @HEY_OPEN_DROID"), result.lines)
    }

    @Test
    fun `blank phrase falls back to the default phrase`() {
        val result = WakeKeywordFactory.buildLines("   ", lexicon) as KeywordBuildResult.Ready
        assertEquals("OPEN_DROID", result.displayName)
    }

    @Test
    fun `unknown words are reported not guessed`() {
        val result = WakeKeywordFactory.buildLines("Open Xylophone", lexicon)
        assertTrue(result is KeywordBuildResult.UnknownWords)
        assertEquals(listOf("XYLOPHONE"), (result as KeywordBuildResult.UnknownWords).words)
    }

    @Test
    fun `display name is a safe keyword identifier`() {
        assertEquals("HEY_OPEN_DROID", WakeKeywordFactory.displayNameFor("Hey Open Droid"))
        assertEquals("BOUT", WakeKeywordFactory.displayNameFor("'bout"))
    }

    @Test
    fun `keywords file content is newline terminated lines`() {
        val content = WakeKeywordFactory.keywordsFileContent(listOf("A B @X", "C D @Y"))
        assertEquals("A B @X\nC D @Y\n", content)
    }

    @Test
    fun `sensitivity maps monotonically onto the detection threshold`() {
        assertEquals(0.35f, WakeKeywordFactory.thresholdForSensitivity(0f), 0.0001f)
        assertEquals(0.25f, WakeKeywordFactory.thresholdForSensitivity(0.5f), 0.0001f)
        assertEquals(0.15f, WakeKeywordFactory.thresholdForSensitivity(1f), 0.0001f)
        var previous = Float.MAX_VALUE
        for (step in 0..20) {
            val threshold = WakeKeywordFactory.thresholdForSensitivity(step / 20f)
            assertTrue("threshold must not increase with sensitivity", threshold <= previous)
            previous = threshold
        }
    }

    @Test
    fun `sensitivity is clamped outside the slider range`() {
        assertEquals(0.35f, WakeKeywordFactory.thresholdForSensitivity(-1f), 0.0001f)
        assertEquals(0.15f, WakeKeywordFactory.thresholdForSensitivity(2f), 0.0001f)
    }
}
