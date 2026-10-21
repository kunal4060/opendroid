package com.opendroid.ai.core.research

import com.opendroid.ai.data.db.entities.ResearchSourceEntity
import com.opendroid.ai.data.models.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature D3: which stored sources a chat message renders. The resolver
 * is the last link in the citation chain — a wrong link here would show
 * sources under an answer that never cited them, so both link mechanisms
 * (direct message id, run-answer containment) and their guards are
 * pinned.
 */
class ResearchSourceResolverTest {

    private fun source(messageId: String, position: Int) = ResearchSourceEntity(
        id = (messageId.hashCode() * 10L + position),
        messageId = messageId,
        position = position,
        url = "https://example.org/$position",
        title = "Source $position",
        snippet = "snippet",
        fetchedAt = 1_000L,
        contentHash = "hash"
    )

    private fun agentMessage(id: String, text: String) = ChatMessage(
        id = id,
        text = text,
        sender = ChatMessage.Sender.AGENT
    )

    @Test
    fun `direct linkage resolves sources stored under the message id`() {
        val messages = listOf(agentMessage("reply-1", "Answer with citations [1][2]."))
        val sources = listOf(source("reply-1", 2), source("reply-1", 1), source("other", 1))

        val resolved = ResearchSourceResolver.resolve(messages, sources, emptyMap())

        assertEquals(listOf(1, 2), resolved["reply-1"]!!.map { it.position })
        assertEquals(1, resolved.size)
    }

    @Test
    fun `run containment links the summary message that carries the answer verbatim`() {
        val answerText = "The festival runs from 7 to 10 November this year [1]."
        val summary = agentMessage(
            "summary-1",
            "Here's what I found. $answerText Hope that helps."
        )
        val sources = listOf(source("run-42", 1))

        val resolved = ResearchSourceResolver.resolve(
            messages = listOf(summary),
            sources = sources,
            runsAnswerTexts = mapOf("run-42" to answerText)
        )

        assertEquals(listOf(1), resolved["summary-1"]!!.map { it.position })
    }

    @Test
    fun `short run answers never match by containment`() {
        // A fragment like "Done [1]." appears in many messages; linking on
        // it would attach sources to answers that never cited them.
        val shortAnswer = "Done [1]."
        val messages = listOf(agentMessage("m", "Earlier I said: Done [1]. And more text here."))
        val sources = listOf(source("run-1", 1))

        val resolved = ResearchSourceResolver.resolve(
            messages, sources, mapOf("run-1" to shortAnswer)
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `user messages never carry sources`() {
        val messages = listOf(
            ChatMessage(id = "u-1", text = "question [1]", sender = ChatMessage.Sender.USER)
        )
        val sources = listOf(source("u-1", 1))

        val resolved = ResearchSourceResolver.resolve(messages, sources, emptyMap())

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `direct linkage wins over containment`() {
        val answerText = "A long enough validated answer text to be distinctive [2]."
        val message = agentMessage("m-1", "Prefix. $answerText")
        val sources = listOf(source("m-1", 1), source("run-7", 2))

        val resolved = ResearchSourceResolver.resolve(
            listOf(message), sources, mapOf("run-7" to answerText)
        )

        assertEquals(listOf(1), resolved["m-1"]!!.map { it.position })
    }

    @Test
    fun `no sources means no resolution`() {
        val messages = listOf(agentMessage("m", "text"))
        assertTrue(ResearchSourceResolver.resolve(messages, emptyList(), emptyMap()).isEmpty())
    }
}
