package com.opendroid.ai.core.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimatorTest {

    @Test
    fun `cost is prompt and completion tokens times their per-million rates`() {
        val rate = ModelRate(inputPer1MUsd = 2.0, outputPer1MUsd = 8.0)
        val cost = CostEstimator.costUsd(promptTokens = 500_000, completionTokens = 250_000, rate = rate)
        assertEquals(1.0 + 2.0, cost!!, 1e-9)
    }

    @Test
    fun `no rate means no cost figure`() {
        assertNull(CostEstimator.costUsd(1_000, 1_000, null))
    }

    @Test
    fun `built in table prices the catalog seed models`() {
        val gpt4o = ModelPricing.builtInRate("OpenAI", "gpt-4o")
        assertEquals(ModelRate(2.50, 10.00), gpt4o)
        val flash = ModelPricing.builtInRate("Google Gemini", "gemini-2.5-flash")
        assertEquals(ModelRate(0.30, 2.50), flash)
        val sonnet = ModelPricing.builtInRate("Anthropic Claude", "claude-sonnet-5")
        assertEquals(ModelRate(3.00, 15.00), sonnet)
    }

    @Test
    fun `longest family prefix wins so mini is not priced as full gpt-4o`() {
        val mini = ModelPricing.builtInRate("OpenAI", "gpt-4o-mini")
        assertEquals(ModelRate(0.15, 0.60), mini)
        val miniDated = ModelPricing.builtInRate("OpenAI", "gpt-4o-mini-2024-07-18")
        assertEquals(ModelRate(0.15, 0.60), miniDated)
    }

    @Test
    fun `unknown model gets no invented price`() {
        assertNull(ModelPricing.builtInRate("OpenAI", "some-future-model-x"))
        assertNull(ModelPricing.builtInRate("OpenRouter", "openrouter/auto"))
        assertNull(ModelPricing.builtInRate("Custom OpenAI Compatible", "custom-model"))
    }

    @Test
    fun `on-device and ollama are genuinely free`() {
        assertEquals(ModelRate(0.0, 0.0), ModelPricing.builtInRate("On-Device AI", "gemma-4-on-device"))
        assertEquals(ModelRate(0.0, 0.0), ModelPricing.builtInRate("Ollama", "llama3"))
        assertTrue(ModelPricing.isFreeLocal("LiteRT-LM (On-device)"))
        assertEquals(0.0, CostEstimator.costUsd(10_000, 10_000, ModelRate(0.0, 0.0))!!, 0.0)
    }

    @Test
    fun `user override wins over the built in table`() {
        val overrides = mapOf(
            CostEstimator.overrideKey("OpenAI", "gpt-4o") to ModelRate(9.0, 9.0)
        )
        val resolved = CostEstimator.resolveRate("OpenAI", "gpt-4o", overrides)
        assertEquals(ModelRate(9.0, 9.0), resolved)
    }

    @Test
    fun `override applies to an otherwise unknown model`() {
        val overrides = mapOf(
            CostEstimator.overrideKey("Custom OpenAI Compatible", "my-local-proxy-model") to ModelRate(1.0, 2.0)
        )
        val resolved = CostEstimator.resolveRate("Custom OpenAI Compatible", "my-local-proxy-model", overrides)
        assertEquals(ModelRate(1.0, 2.0), resolved)
        assertEquals(3.0, CostEstimator.costUsd(1_000_000, 1_000_000, resolved)!!, 1e-9)
    }

    @Test
    fun `provider aliases resolve through the canonical name`() {
        // "Gemma 4 (On-device)" is the legacy alias of "On-Device AI".
        assertEquals(ModelRate(0.0, 0.0), ModelPricing.builtInRate("Gemma 4 (On-device)", "gemma-4-on-device"))
    }
}
