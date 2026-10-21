package com.opendroid.ai.data.export

import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat export serialiser: the exported file must mirror the stored rows. */
class ChatExporterTest {

    private val session = ChatSession(
        id = "s1",
        title = "Trip planning",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        isCurrent = true
    )

    private val userMsg = ChatMessage(
        id = "m1",
        text = "Plan a trip to Goa",
        sender = ChatMessage.Sender.USER,
        timestamp = 1_700_000_000_000L
    )

    private val agentMsg = ChatMessage(
        id = "m2",
        text = "Here is a plan:\n```\nDay 1: beach\n```",
        sender = ChatMessage.Sender.AGENT,
        timestamp = 1_700_000_060_000L,
        modelBadge = "gpt-test"
    )

    @Test
    fun `markdown has title header and both messages in order`() {
        val md = ChatExporter.toMarkdown(session, listOf(userMsg, agentMsg), exportedAt = 1_700_000_200_000L)
        assertTrue(md.startsWith("# Trip planning"))
        assertTrue(md.contains("## You · "))
        assertTrue(md.contains("## OpenDroid (gpt-test) · "))
        assertTrue(md.indexOf("Plan a trip to Goa") < md.indexOf("Here is a plan:"))
    }

    @Test
    fun `markdown preserves code fences verbatim`() {
        val md = ChatExporter.toMarkdown(session, listOf(agentMsg), exportedAt = 0L)
        assertTrue(md.contains("```\nDay 1: beach\n```"))
    }

    @Test
    fun `markdown without badge omits parentheses`() {
        val plain = agentMsg.copy(modelBadge = null)
        val md = ChatExporter.toMarkdown(session, listOf(plain), exportedAt = 0L)
        assertTrue(md.contains("## OpenDroid · "))
        assertFalse(md.contains("OpenDroid ("))
    }

    @Test
    fun `markdown renders attachment placeholder without base64`() {
        val withImage = userMsg.copy(imageBase64 = "QUJD")
        val md = ChatExporter.toMarkdown(session, listOf(withImage), exportedAt = 0L)
        assertTrue(md.contains("![image](attached-image)"))
        assertFalse(md.contains("QUJD"))
    }

    @Test
    fun `file name is sanitised and timestamped`() {
        val weird = session.copy(title = "a/b:c*d?e\"f<g>h|i")
        val name = ChatExporter.suggestedFileName(weird, "md", exportedAt = 1_700_000_200_000L)
        assertTrue(name.startsWith("opendroid-a-b-c-d-e-f-g-h-i-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
    }

    @Test
    fun `file name falls back to chat for blank title`() {
        val blank = session.copy(title = "   ")
        val name = ChatExporter.suggestedFileName(blank, "pdf", exportedAt = 0L)
        assertTrue(name.startsWith("opendroid-chat-"))
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun `empty session exports header only`() {
        val md = ChatExporter.toMarkdown(session, emptyList(), exportedAt = 0L)
        assertTrue(md.startsWith("# Trip planning"))
        assertFalse(md.contains("## You"))
        assertFalse(md.contains("## OpenDroid"))
    }

    @Test
    fun `payload bytes come from text content for markdown`() {
        val payload = ChatExportPayload("x.md", "text/markdown", textContent = "abc", binaryContent = null)
        assertEquals("abc", String(payload.bytes(), Charsets.UTF_8))
    }
}
