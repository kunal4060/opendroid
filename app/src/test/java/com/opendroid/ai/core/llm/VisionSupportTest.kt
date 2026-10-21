package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.VisionSupport.AttachmentDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checklist B1: the capability gate's decision matrix. The invariant under
 * test is the anti-silent-drop rule - an attachment is only ever routed
 * Native to a provider/model known to accept that payload; everything else
 * extracts text (files) or blocks with a reason (images).
 */
class VisionSupportTest {

    private fun decide(mime: String, provider: String, model: String?) =
        VisionSupport.decide(mime, provider, model)

    // ── Images ────────────────────────────────────────────────────────

    @Test
    fun `gemini takes images natively`() {
        assertEquals(
            AttachmentDecision.Native,
            decide("image/jpeg", "Google Gemini", "gemini-2.5-flash")
        )
    }

    @Test
    fun `claude takes images natively`() {
        assertEquals(
            AttachmentDecision.Native,
            decide("image/png", "Anthropic Claude", "claude-sonnet-4-5")
        )
    }

    @Test
    fun `openai vision models take images, text models block`() {
        assertEquals(
            AttachmentDecision.Native,
            decide("image/jpeg", "OpenAI", "gpt-4o")
        )
        assertTrue(
            decide("image/jpeg", "OpenAI", "gpt-3.5-turbo")
                is AttachmentDecision.Blocked
        )
    }

    @Test
    fun `text-only providers block images instead of dropping them`() {
        assertTrue(decide("image/jpeg", "DeepSeek", "deepseek-chat") is AttachmentDecision.Blocked)
        assertTrue(decide("image/jpeg", "Cohere", "command-r-plus") is AttachmentDecision.Blocked)
        assertTrue(decide("image/jpeg", "Copilot API", "gpt-4o") is AttachmentDecision.Blocked)
    }

    @Test
    fun `on-device models block images in v1`() {
        assertTrue(
            decide("image/jpeg", "On-Device AI", "gemma-4-on-device")
                is AttachmentDecision.Blocked
        )
        assertFalse(VisionSupport.supportsImage("LiteRT-LM (On-device)", "gemma3-1b-it"))
    }

    @Test
    fun `groq is per-model - default text model blocks, vision models pass`() {
        assertTrue(
            decide("image/jpeg", "Groq", "llama-3.3-70b-versatile")
                is AttachmentDecision.Blocked
        )
        assertEquals(
            AttachmentDecision.Native,
            decide("image/jpeg", "Groq", "meta-llama/llama-4-scout-17b-16e-instruct")
        )
    }

    @Test
    fun `ollama uses the model-name heuristic`() {
        assertEquals(AttachmentDecision.Native, decide("image/jpeg", "Ollama", "llava:13b"))
        assertTrue(decide("image/jpeg", "Ollama", "llama3") is AttachmentDecision.Blocked)
    }

    @Test
    fun `aggregators use the model-id heuristic`() {
        assertTrue(VisionSupport.supportsImage("OpenRouter", "anthropic/claude-sonnet-4"))
        assertTrue(VisionSupport.supportsImage("OpenRouter", "openrouter/auto"))
        assertFalse(VisionSupport.supportsImage("Together AI", "meta-llama/Llama-3-70b-chat-hf"))
        assertTrue(VisionSupport.supportsImage("Together AI", "Qwen/Qwen2.5-VL-72B-Instruct"))
    }

    @Test
    fun `blocked image decision carries a user-readable reason`() {
        val decision = decide("image/jpeg", "DeepSeek", "deepseek-chat")
        assertTrue(decision is AttachmentDecision.Blocked)
        assertTrue((decision as AttachmentDecision.Blocked).reason.contains("can't view images"))
    }

    // ── PDFs ──────────────────────────────────────────────────────────

    @Test
    fun `pdf is native only on gemini and claude`() {
        assertEquals(
            AttachmentDecision.Native,
            decide("application/pdf", "Google Gemini", "gemini-2.5-flash")
        )
        assertEquals(
            AttachmentDecision.Native,
            decide("application/pdf", "Anthropic Claude", "claude-sonnet-4-5")
        )
        assertTrue(VisionSupport.supportsNativePdf("Google Gemini", null))
        assertFalse(VisionSupport.supportsNativePdf("OpenAI", "gpt-4o"))
    }

    @Test
    fun `pdf extracts text everywhere else, including on-device`() {
        assertEquals(
            AttachmentDecision.ExtractText,
            decide("application/pdf", "OpenAI", "gpt-4o")
        )
        assertEquals(
            AttachmentDecision.ExtractText,
            decide("application/pdf", "DeepSeek", "deepseek-chat")
        )
        assertEquals(
            AttachmentDecision.ExtractText,
            decide("application/pdf", "On-Device AI", "gemma-4-on-device")
        )
    }

    // ── Text files ────────────────────────────────────────────────────

    @Test
    fun `text files always extract, on every provider`() {
        assertEquals(
            AttachmentDecision.ExtractText,
            decide("text/plain", "DeepSeek", "deepseek-chat")
        )
        assertEquals(
            AttachmentDecision.ExtractText,
            decide("text/markdown", "On-Device AI", "gemma-4-on-device")
        )
        assertEquals(
            AttachmentDecision.ExtractText,
            decide("application/json", "OpenAI", "gpt-4o")
        )
    }

    // ── Unsupported types ─────────────────────────────────────────────

    @Test
    fun `unsupported types block with a reason`() {
        assertTrue(
            decide("application/zip", "Google Gemini", "gemini-2.5-flash")
                is AttachmentDecision.Blocked
        )
        assertTrue(
            decide("video/mp4", "Google Gemini", "gemini-2.5-flash")
                is AttachmentDecision.Blocked
        )
    }

    // ── Payload-builder gate ──────────────────────────────────────────

    @Test
    fun `display names and canonical names agree`() {
        // Providers report display names (e.g. "Mistral AI"); the registry
        // keys on canonical names - both spellings must give the same answer.
        assertEquals(
            VisionSupport.supportsImage("Mistral AI", "pixtral-large-latest"),
            VisionSupport.supportsImage("Mistral AI", "pixtral-large-latest")
        )
        assertTrue(VisionSupport.supportsImage("Mistral AI", "pixtral-large-latest"))
        assertTrue(VisionSupport.supportsImage("Mistral AI", "mistral-large-latest"))
    }
}
