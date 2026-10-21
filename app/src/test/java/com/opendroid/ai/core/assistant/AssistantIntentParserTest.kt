package com.opendroid.ai.core.assistant

import android.app.SearchManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantIntentParserTest {

    @Test
    fun `plain launcher launch is not an assistant invocation`() {
        val parsed = AssistantIntentParser.parse(Intent.ACTION_MAIN, emptyMap())
        assertFalse(parsed.isAssistantInvocation)
        assertNull(parsed.query)
    }

    @Test
    fun `ACTION_ASSIST without a query is an invocation awaiting speech`() {
        val parsed = AssistantIntentParser.parse(Intent.ACTION_ASSIST, emptyMap())
        assertTrue(parsed.isAssistantInvocation)
        assertNull(parsed.query)
    }

    @Test
    fun `forwarded marker from AssistActivity counts as an invocation`() {
        val parsed = AssistantIntentParser.parse(
            Intent.ACTION_MAIN,
            mapOf(AssistantIntentParser.EXTRA_FROM_ASSISTANT to true)
        )
        assertTrue(parsed.isAssistantInvocation)
    }

    @Test
    fun `assist query extra is honoured and trimmed`() {
        val parsed = AssistantIntentParser.parse(
            Intent.ACTION_ASSIST,
            mapOf(AssistantIntentParser.EXTRA_ASSIST_QUERY to "  turn off the lights  ")
        )
        assertEquals("turn off the lights", parsed.query)
    }

    @Test
    fun `query priority is assist extra then search query then plain text`() {
        val all = AssistantIntentParser.parse(
            Intent.ACTION_ASSIST,
            mapOf(
                AssistantIntentParser.EXTRA_ASSIST_QUERY to "from assist",
                SearchManager.QUERY to "from search",
                Intent.EXTRA_TEXT to "from text"
            )
        )
        assertEquals("from assist", all.query)

        val noAssist = AssistantIntentParser.parse(
            Intent.ACTION_ASSIST,
            mapOf(
                SearchManager.QUERY to "from search",
                Intent.EXTRA_TEXT to "from text"
            )
        )
        assertEquals("from search", noAssist.query)

        val textOnly = AssistantIntentParser.parse(
            Intent.ACTION_ASSIST,
            mapOf(Intent.EXTRA_TEXT to "from text")
        )
        assertEquals("from text", textOnly.query)
    }

    @Test
    fun `blank extras do not produce a query`() {
        val parsed = AssistantIntentParser.parse(
            Intent.ACTION_ASSIST,
            mapOf(
                AssistantIntentParser.EXTRA_ASSIST_QUERY to "   ",
                Intent.EXTRA_TEXT to ""
            )
        )
        assertNull(parsed.query)
    }

    @Test
    fun `a query on a non-assistant launch is ignored`() {
        val parsed = AssistantIntentParser.parse(
            Intent.ACTION_MAIN,
            mapOf(AssistantIntentParser.EXTRA_ASSIST_QUERY to "sneaky")
        )
        assertFalse(parsed.isAssistantInvocation)
        assertNull(parsed.query)
    }
}
