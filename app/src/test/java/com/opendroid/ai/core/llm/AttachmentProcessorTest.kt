package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checklist B1: the pure routing/preparation logic of the attachment
 * pipeline - the pick-time allowlist, the image downscale maths, and the
 * token-budget trimming applied to extracted file text. (The Android-bound
 * parts - decoding, PdfBox, private-storage copies - are exercised by the
 * migration/device passes, not here.)
 */
class AttachmentProcessorTest {

    // ── Pick-time allowlist ───────────────────────────────────────────

    @Test
    fun `allowlist accepts images pdfs and text`() {
        assertTrue(isSupportedAttachmentMime("image/jpeg"))
        assertTrue(isSupportedAttachmentMime("image/png"))
        assertTrue(isSupportedAttachmentMime("application/pdf"))
        assertTrue(isSupportedAttachmentMime("text/plain"))
        assertTrue(isSupportedAttachmentMime("text/markdown"))
        assertTrue(isSupportedAttachmentMime("application/json"))
    }

    @Test
    fun `allowlist rejects everything else`() {
        assertFalse(isSupportedAttachmentMime("application/zip"))
        assertFalse(isSupportedAttachmentMime("video/mp4"))
        assertFalse(isSupportedAttachmentMime("audio/mpeg"))
        assertFalse(isSupportedAttachmentMime("application/octet-stream"))
        assertFalse(isSupportedAttachmentMime(null))
    }

    // ── Downscale maths ───────────────────────────────────────────────

    @Test
    fun `downscale caps the longest edge and keeps aspect`() {
        assertEquals(1024 to 768, scaledDimensions(4000, 3000, 1024))
        assertEquals(1024 to 512, scaledDimensions(2048, 1024, 1024))
        assertEquals(512 to 1024, scaledDimensions(1024, 2048, 1024))
    }

    @Test
    fun `small images are not upscaled`() {
        assertEquals(800 to 600, scaledDimensions(800, 600, 1024))
        assertEquals(1024 to 1024, scaledDimensions(1024, 1024, 1024))
    }

    // ── Token-budget trimming ─────────────────────────────────────────

    @Test
    fun `short text passes through untouched`() {
        val text = "hello attachment"
        assertEquals(text, trimToTokenBudget(text))
    }

    @Test
    fun `long text is trimmed to the budget with an explicit note`() {
        val text = "lorem ipsum dolor sit amet ".repeat(8000)
        val trimmed = trimToTokenBudget(text)
        assertTrue(
            PromptBudget.estimateTokens(trimmed) <= AttachmentProcessor.MAX_FILE_TEXT_TOKENS
        )
        assertTrue(trimmed.endsWith("[Attachment truncated to fit the message size limit.]"))
        assertTrue(trimmed.length < text.length)
    }

    // ── Inline block shape ────────────────────────────────────────────

    @Test
    fun `fenced block names the file and fences the content`() {
        val block = fencedAttachmentBlock("notes.txt", "file body")
        assertTrue(block.startsWith("\n\n[Attached file: notes.txt]"))
        assertTrue(block.contains("```\nfile body\n```"))
    }
}
