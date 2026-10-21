package com.opendroid.ai.data.models

import com.opendroid.ai.core.llm.ClaudeModelCatalog
import com.opendroid.ai.core.llm.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-chat model override resolution (feature A2). Pure logic: the
 * resolver must apply a usable override to a config COPY, and refuse
 * (null = use the global config) any override that names an unknown or
 * no-longer-configured provider, so a stale per-chat pick can never
 * hijack a chat or corrupt the global default.
 */
class SessionModelResolutionTest {

    private val geminiKeyConfig = LLMConfig(
        apiKeys = mapOf("Google Gemini" to "gemini-key")
    )

    @Test
    fun `no override resolves to null`() {
        assertNull(geminiKeyConfig.effectiveWithSessionOverride(null, null))
        assertNull(geminiKeyConfig.effectiveWithSessionOverride("", "gpt-4o"))
        assertNull(geminiKeyConfig.effectiveWithSessionOverride("   ", null))
    }

    @Test
    fun `unknown provider override resolves to null`() {
        assertNull(geminiKeyConfig.effectiveWithSessionOverride("Not A Provider", "x"))
        assertFalse(isProviderConfiguredForUse(geminiKeyConfig, "Not A Provider"))
    }

    @Test
    fun `override for a provider whose key was removed resolves to null`() {
        // OpenAI override exists but no OpenAI key is configured anymore.
        assertNull(geminiKeyConfig.effectiveWithSessionOverride("OpenAI", "gpt-4o"))
        assertFalse(isProviderConfiguredForUse(geminiKeyConfig, "OpenAI"))
    }

    @Test
    fun `usable override switches provider and model on a copy only`() {
        val config = geminiKeyConfig.copy(apiKeys = geminiKeyConfig.apiKeys + ("OpenAI" to "sk-test"))
        val effective = config.effectiveWithSessionOverride("OpenAI", "gpt-4o-mini")

        assertNotNull(effective)
        assertEquals("OpenAI", effective!!.activeProvider)
        assertEquals("gpt-4o-mini", effective.selectedModelFor("OpenAI"))
        // The global config the snapshot came from is untouched.
        assertEquals("Google Gemini", config.activeProvider)
        assertEquals(ProviderCatalog.defaultModel("Google Gemini"), config.activeModel)
    }

    @Test
    fun `provider-only override uses that provider's remembered model`() {
        val config = geminiKeyConfig.copy(
            apiKeys = geminiKeyConfig.apiKeys + ("OpenAI" to "sk-test"),
            selectedModels = mapOf("OpenAI" to "gpt-4-turbo")
        )
        val effective = config.effectiveWithSessionOverride("OpenAI", null)

        assertNotNull(effective)
        assertEquals("OpenAI", effective!!.activeProvider)
        assertEquals("gpt-4-turbo", effective.selectedModelFor("OpenAI"))
    }

    @Test
    fun `legacy alias override canonicalises to the unified on-device provider`() {
        val effective = geminiKeyConfig.effectiveWithSessionOverride(
            ProviderCatalog.LEGACY_ON_DEVICE, "gemma-4-on-device"
        )
        assertNotNull(effective)
        assertEquals(ProviderCatalog.ON_DEVICE, effective!!.activeProvider)
    }

    @Test
    fun `on-device override needs no key`() {
        val effective = LLMConfig().effectiveWithSessionOverride(
            ProviderCatalog.ON_DEVICE, "gemma-4-e2b-it-litert"
        )
        assertNotNull(effective)
        assertEquals("gemma-4-e2b-it-litert", effective!!.selectedModelFor(ProviderCatalog.ON_DEVICE))
        assertTrue(isProviderConfiguredForUse(LLMConfig(), ProviderCatalog.ON_DEVICE))
    }

    @Test
    fun `untrusted claude model id is coerced to the catalog default`() {
        val config = LLMConfig(apiKeys = mapOf("Anthropic Claude" to "claude-key"))
        val effective = config.effectiveWithSessionOverride("Anthropic Claude", "claude-not-a-real-id")

        assertNotNull(effective)
        assertEquals(
            ClaudeModelCatalog.defaultModelId,
            effective!!.selectedModelFor("Anthropic Claude")
        )
    }

    @Test
    fun `gemini override without a key is never usable`() {
        // "gemini-nano" used to be exempted as a keyless on-device mock;
        // that mock fabricated replies and plans, so the exemption was
        // removed (Loop-2 audit Area 1, Finding 1) and Gemini now always
        // requires a key, whatever model id the override names.
        assertNull(LLMConfig().effectiveWithSessionOverride("Google Gemini", "gemini-nano"))
        assertNull(LLMConfig().effectiveWithSessionOverride("Google Gemini", "gemini-2.5-flash"))
        assertNotNull(
            LLMConfig(apiKeys = mapOf("Google Gemini" to "gemini-key"))
                .effectiveWithSessionOverride("Google Gemini", "gemini-nano")
        )
    }

    @Test
    fun `ollama override needs a configured endpoint`() {
        assertNull(LLMConfig().effectiveWithSessionOverride("Ollama", "llama3"))
        val withUrl = LLMConfig(ollamaUrl = "http://localhost:11434")
        assertNotNull(withUrl.effectiveWithSessionOverride("Ollama", "llama3"))
        assertTrue(isProviderConfiguredForUse(withUrl, "Ollama"))
    }
}
