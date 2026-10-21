package com.opendroid.ai.core.llm

/**
 * Capability registry for composer attachments (feature B1): which
 * provider/model combinations can actually receive an image or a native PDF,
 * and what happens to an attachment they cannot take.
 *
 * This exists so an attachment is never silently dropped: the composer gate
 * consults [decide] before a send, and the OpenAI-family payload builder
 * consults [supportsImage] for the provider it is actually building for (a
 * fallback provider may be text-only even when the primary was not).
 *
 * Deliberately conservative: a provider/model is only claimed capable when
 * its backend is known to accept the payload. Claiming support a backend
 * rejects or ignores would recreate the silent drop this registry prevents.
 * Provider names are the canonical names from [ProviderCatalog]; callers may
 * pass display names - they are canonicalised here.
 */
object VisionSupport {

    /** How one attachment may be delivered to one provider/model. */
    sealed interface AttachmentDecision {
        /** Send the binary payload in the provider's native attachment form. */
        data object Native : AttachmentDecision

        /** Extract the file's text locally and inline it into the message. */
        data object ExtractText : AttachmentDecision

        /** The current model cannot take this attachment in any form. */
        data class Blocked(val reason: String) : AttachmentDecision
    }

    fun isImageMime(mimeType: String?): Boolean =
        mimeType?.startsWith("image/") == true

    fun isPdfMime(mimeType: String?): Boolean =
        mimeType?.equals("application/pdf", ignoreCase = true) == true

    fun isTextMime(mimeType: String?): Boolean =
        mimeType?.startsWith("text/") == true ||
            mimeType in TEXTUAL_APPLICATION_MIMES

    private val TEXTUAL_APPLICATION_MIMES = setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml"
    )

    /**
     * Model-id substrings that mark a model as vision-capable on the
     * aggregator / self-hosted families, where one provider name covers both
     * text-only and multimodal models.
     */
    private val VISION_MODEL_TOKENS = listOf(
        "vision", "-vl", "gpt-4o", "gpt-4.1", "gpt-5", "gemini", "claude",
        "pixtral", "llava", "bakllava", "moondream", "minicpm-v",
        "llama-4", "llama4", "llama3.2-vision", "qwen2-vl", "qwen2.5-vl",
        "gemma3"
    )

    private fun modelMatchesVisionHeuristic(model: String?): Boolean {
        val id = model?.lowercase().orEmpty()
        if (id.isBlank()) return false
        return VISION_MODEL_TOKENS.any { id.contains(it) }
    }

    /** True when [providerName]/[model] accept image payloads. */
    fun supportsImage(providerName: String, model: String?): Boolean {
        return when (ProviderCatalog.canonicalName(providerName)) {
            // Gemini's current lineup is multimodal end to end.
            "Google Gemini" -> true
            // Every model the Claude catalog offers (3.5+/4.x) takes images.
            "Anthropic Claude" -> true
            "OpenAI" -> {
                val id = model?.lowercase().orEmpty()
                id.contains("gpt-4o") || id.contains("gpt-4.1") ||
                    id.contains("gpt-5") || id.contains("chatgpt-4o") ||
                    id.startsWith("o3") || id.startsWith("o4")
            }
            // Groq serves both text-only Llama and vision models; the
            // default (llama-3.3-70b) is text-only, so this is per-model.
            "Groq" -> modelMatchesVisionHeuristic(model) ||
                model?.lowercase()?.contains("llama-4") == true
            "Mistral AI" -> {
                val id = model?.lowercase().orEmpty()
                id.contains("pixtral") || id.startsWith("mistral-large") ||
                    id.startsWith("mistral-medium")
            }
            // Aggregators and self-hosted endpoints: the provider name says
            // nothing, the model id says everything.
            "Together AI", "OpenRouter", "Custom OpenAI Compatible", "Ollama" ->
                modelMatchesVisionHeuristic(model) ||
                    // OpenRouter's auto-router resolves to a frontier
                    // (multimodal) model; treat it as vision-capable.
                    model?.equals("openrouter/auto", ignoreCase = true) == true
            // DeepSeek, Cohere, Copilot API and Opencode-Go backends reject or
            // ignore image payloads today; on-device engines have no image
            // code path in v1. Claiming otherwise would silently drop.
            else -> false
        }
    }

    /** True when [providerName] accepts a PDF as a native binary payload. */
    fun supportsNativePdf(providerName: String, model: String?): Boolean {
        return when (ProviderCatalog.canonicalName(providerName)) {
            "Google Gemini" -> true
            "Anthropic Claude" -> true
            else -> false
        }
    }

    /**
     * The delivery decision for one attachment mime type against the model
     * that will actually answer. Text files always extract (they work on
     * every provider, including on-device); PDFs extract wherever they are
     * not native; images have no text fallback and block instead.
     */
    fun decide(mimeType: String?, providerName: String, model: String?): AttachmentDecision {
        val provider = ProviderCatalog.canonicalName(providerName)
        val modelLabel = model?.takeIf { it.isNotBlank() } ?: "the current model"
        return when {
            isTextMime(mimeType) -> AttachmentDecision.ExtractText
            isPdfMime(mimeType) -> when {
                supportsNativePdf(provider, model) -> AttachmentDecision.Native
                else -> AttachmentDecision.ExtractText
            }
            isImageMime(mimeType) -> when {
                supportsImage(provider, model) -> AttachmentDecision.Native
                else -> AttachmentDecision.Blocked(
                    "$modelLabel ($provider) can't view images. " +
                        "Switch to a vision-capable model to send this photo."
                )
            }
            else -> AttachmentDecision.Blocked(
                "This file type isn't supported as an attachment yet."
            )
        }
    }
}
