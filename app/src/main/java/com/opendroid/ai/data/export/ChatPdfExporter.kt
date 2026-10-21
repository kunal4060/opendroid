package com.opendroid.ai.data.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.ChatSession
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a chat session to a PDF using only the platform
 * [PdfDocument] — no third-party PDF dependency. A4 pages, wrapped text via
 * [StaticLayout]; pagination draws each block line-slice by line-slice
 * (clip + translate over one layout) so a block taller than a page splits
 * across pages without re-wrapping. Attachments render as a placeholder
 * line, mirroring the Markdown export.
 */
object ChatPdfExporter {

    private const val PAGE_WIDTH = 595   // A4 at 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f

    fun render(session: ChatSession, messages: List<ChatMessage>): ByteArray {
        val document = PdfDocument()
        try {
            val renderer = Renderer(document)
            renderer.render(session, messages)
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private class Renderer(private val document: PdfDocument) {
        private val contentWidth = (PAGE_WIDTH - 2 * MARGIN).toInt()

        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.rgb(17, 17, 17)
        }
        private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = Color.rgb(100, 100, 100)
        }
        private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.rgb(17, 17, 17)
        }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = Color.rgb(33, 33, 33)
        }

        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 0f

        fun render(session: ChatSession, messages: List<ChatMessage>) {
            val headerTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val messageTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            startPage()
            drawWrapped(session.title.ifBlank { "Chat" }, titlePaint, spacingAfter = 4f)
            drawWrapped(
                "Exported from OpenDroid · ${headerTime.format(Date())}",
                metaPaint,
                spacingAfter = 16f
            )
            for (message in messages) {
                val header = buildString {
                    when (message.sender) {
                        ChatMessage.Sender.USER -> append("You")
                        ChatMessage.Sender.AGENT -> {
                            append("OpenDroid")
                            message.modelBadge?.takeIf { it.isNotBlank() }?.let {
                                append(" (").append(it).append(")")
                            }
                        }
                    }
                    append(" · ").append(messageTime.format(Date(message.timestamp)))
                }
                drawWrapped(header, headerPaint, spacingAfter = 2f)
                val body = if (message.imageBase64 != null) {
                    message.text + "\n[Image attachment]"
                } else {
                    message.text
                }
                drawWrapped(body, bodyPaint, spacingAfter = 14f)
            }
            finishPage()
        }

        private fun startPage() {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            y = MARGIN
        }

        private fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
        }

        private fun remaining(): Float = PAGE_HEIGHT - MARGIN - y

        /**
         * Draws [text] wrapped to the content width, splitting across pages
         * when needed: each page draws the slice of the single layout that
         * fits, so line breaks are computed once and never shift mid-block.
         */
        private fun drawWrapped(text: String, paint: TextPaint, spacingAfter: Float) {
            if (text.isEmpty()) {
                y += spacingAfter
                return
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            var consumed = 0f
            val total = layout.height.toFloat()
            while (consumed < total) {
                if (remaining() <= 0f) {
                    finishPage()
                    startPage()
                }
                val slice = minOf(remaining(), total - consumed)
                val canvas = page!!.canvas
                canvas.save()
                canvas.clipRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + slice)
                canvas.translate(MARGIN, y - consumed)
                layout.draw(canvas)
                canvas.restore()
                y += slice
                consumed += slice
            }
            y += spacingAfter
        }
    }
}
