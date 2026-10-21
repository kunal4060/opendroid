package com.opendroid.ai.data.export

import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export formats offered by the chat export action (A11). */
enum class ChatExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    PDF("pdf", "application/pdf")
}

/**
 * A built export, ready to be written wherever the user chose. Exactly one
 * of [textContent] / [binaryContent] is non-null, matching [mimeType].
 */
class ChatExportPayload(
    val fileName: String,
    val mimeType: String,
    val textContent: String?,
    val binaryContent: ByteArray?
) {
    fun bytes(): ByteArray =
        binaryContent ?: (textContent ?: "").toByteArray(Charsets.UTF_8)
}

/**
 * Serialises a chat session to Markdown from its real stored rows — roles,
 * order and timestamps preserved, message text verbatim (code fences stay
 * exactly as written). A pure function of its inputs so it is unit-testable
 * on the JVM; the caller decides where the result is written.
 *
 * Timestamps use [Locale.US] so exported files keep ASCII digits in every
 * device locale.
 */
object ChatExporter {

    fun toMarkdown(
        session: ChatSession,
        messages: List<ChatMessage>,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val headerTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val messageTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val out = StringBuilder()
        out.append("# ").append(session.title.ifBlank { "Chat" }).append("\n\n")
        out.append("Exported from OpenDroid · ")
            .append(headerTime.format(Date(exportedAt))).append("\n")
        for (message in messages) {
            out.append("\n## ")
            when (message.sender) {
                ChatMessage.Sender.USER -> out.append("You")
                ChatMessage.Sender.AGENT -> {
                    out.append("OpenDroid")
                    message.modelBadge?.takeIf { it.isNotBlank() }?.let { badge ->
                        out.append(" (").append(badge).append(")")
                    }
                }
            }
            out.append(" · ").append(messageTime.format(Date(message.timestamp)))
            out.append("\n\n")
            out.append(message.text)
            if (message.imageBase64 != null) {
                // Attachments are referenced, never inlined as base64.
                out.append("\n\n![image](attached-image)")
            }
            out.append("\n")
        }
        return out.toString()
    }

    /**
     * `opendroid-<title>-<yyyyMMdd-HHmm>.<ext>` with the title sanitised for
     * file systems (path separators and control characters stripped, length
     * capped) and a "chat" fallback for blank titles.
     */
    fun suggestedFileName(
        session: ChatSession,
        extension: String,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(exportedAt))
        val cleaned = session.title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .trim()
            .replace(Regex("\\s+"), "-")
            .take(48)
            .trim('-')
        val base = cleaned.ifBlank { "chat" }
        return "opendroid-$base-$stamp.$extension"
    }
}
