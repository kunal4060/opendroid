package com.opendroid.ai.core.usage

import com.opendroid.ai.core.llm.ProviderCatalog

/** USD price of one model: per 1M prompt (input) / completion (output) tokens. */
data class ModelRate(
    val inputPer1MUsd: Double,
    val outputPer1MUsd: Double
)

/**
 * The built-in per-model price table (B3). Prices are the providers' public
 * list prices as of [TABLE_AS_OF]; they exist so the dashboard can estimate
 * spend, and every figure derived from them is labelled an estimate in the
 * UI. The user can override any model's rate in the dashboard, and an
 * override always wins over this table. Models with no entry here and no
 * override get **no** cost figure (shown as "—") — a price is never guessed
 * for an unknown model.
 */
object ModelPricing {

    const val TABLE_AS_OF = "2026-10-21"
    const val TABLE_SOURCE = "Built-in table from providers' public pricing pages, Oct 2026"

    private data class FamilyRate(val provider: String, val modelPrefix: String, val rate: ModelRate)

    /**
     * Prefix rules, matched longest-prefix-first so e.g. "gpt-4o-mini" wins
     * over "gpt-4o". Keyed by canonical provider name.
     */
    private val familyRates = listOf(
        // OpenAI
        FamilyRate("OpenAI", "gpt-4o-mini", ModelRate(0.15, 0.60)),
        FamilyRate("OpenAI", "gpt-4o", ModelRate(2.50, 10.00)),
        FamilyRate("OpenAI", "o3-mini", ModelRate(1.10, 4.40)),
        FamilyRate("OpenAI", "o1-mini", ModelRate(1.10, 4.40)),
        // Google Gemini
        FamilyRate("Google Gemini", "gemini-2.5-flash", ModelRate(0.30, 2.50)),
        FamilyRate("Google Gemini", "gemini-2.5-pro", ModelRate(1.25, 10.00)),
        FamilyRate("Google Gemini", "gemini-2.0-flash", ModelRate(0.10, 0.40)),
        FamilyRate("Google Gemini", "gemini-1.5-flash", ModelRate(0.075, 0.30)),
        FamilyRate("Google Gemini", "gemini-1.5-pro", ModelRate(1.25, 5.00)),
        // Anthropic Claude
        FamilyRate("Anthropic Claude", "claude-opus", ModelRate(15.00, 75.00)),
        FamilyRate("Anthropic Claude", "claude-sonnet", ModelRate(3.00, 15.00)),
        FamilyRate("Anthropic Claude", "claude-haiku", ModelRate(0.80, 4.00)),
        // Mistral AI
        FamilyRate("Mistral AI", "mistral-large", ModelRate(2.00, 6.00)),
        FamilyRate("Mistral AI", "mistral-medium", ModelRate(0.40, 2.00)),
        FamilyRate("Mistral AI", "mistral-small", ModelRate(0.20, 0.60)),
        // Groq (hosted open models)
        FamilyRate("Groq", "llama-3.3-70b", ModelRate(0.59, 0.79)),
        FamilyRate("Groq", "llama-3.1-70b", ModelRate(0.59, 0.79)),
        FamilyRate("Groq", "llama-3.1-8b", ModelRate(0.05, 0.08)),
        // DeepSeek
        FamilyRate("DeepSeek", "deepseek-chat", ModelRate(0.27, 1.10)),
        FamilyRate("DeepSeek", "deepseek-reasoner", ModelRate(0.55, 2.19)),
        // Cohere
        FamilyRate("Cohere", "command-r-plus", ModelRate(2.50, 10.00)),
        FamilyRate("Cohere", "command-r", ModelRate(0.15, 0.60)),
        FamilyRate("Cohere", "command-a", ModelRate(2.50, 10.00)),
        // Together AI (catalog seed model)
        FamilyRate("Together AI", "meta-llama/Llama-3-70b-chat-hf", ModelRate(0.88, 0.88)),
        FamilyRate("Together AI", "meta-llama/Meta-Llama-3-70B", ModelRate(0.88, 0.88))
    )

    /** Providers whose models run on the user's own hardware: cost is a real 0, not an estimate. */
    private val selfHostedProviders = setOf("Ollama")

    fun isFreeLocal(provider: String): Boolean {
        val canonical = ProviderCatalog.canonicalName(provider)
        return ProviderCatalog.isOnDevice(canonical) || canonical in selfHostedProviders
    }

    /**
     * The built-in rate for a provider+model, or null when the table has no
     * entry. Self-hosted/on-device models resolve to a genuine zero rate.
     */
    fun builtInRate(provider: String, model: String): ModelRate? {
        val canonical = ProviderCatalog.canonicalName(provider)
        if (isFreeLocal(canonical)) return ModelRate(0.0, 0.0)
        val trimmedModel = model.trim()
        return familyRates
            .filter { it.provider == canonical && trimmedModel.startsWith(it.modelPrefix) }
            .maxByOrNull { it.modelPrefix.length }
            ?.rate
    }
}

/** Cost resolution and arithmetic, kept pure so it is unit-testable. */
object CostEstimator {

    /**
     * The rate that applies to a model: the user's editable override when
     * one exists, otherwise the built-in table. Overrides are keyed by
     * canonical provider + exact model id.
     */
    fun resolveRate(provider: String, model: String, overrides: Map<String, ModelRate>): ModelRate? {
        val canonical = ProviderCatalog.canonicalName(provider)
        return overrides[overrideKey(canonical, model)] ?: ModelPricing.builtInRate(canonical, model)
    }

    fun overrideKey(provider: String, model: String): String =
        "${ProviderCatalog.canonicalName(provider)}|${model.trim()}"

    /**
     * Estimated USD cost of the prompt/completion split at [rate]. Unsplit
     * tokens are deliberately **not** priced: their prompt/completion mix is
     * unknown, so any figure for them would be invented. The UI discloses
     * the exclusion whenever a row carries unsplit tokens. Returns null when
     * there is no rate at all.
     */
    fun costUsd(promptTokens: Long, completionTokens: Long, rate: ModelRate?): Double? {
        rate ?: return null
        return promptTokens / 1_000_000.0 * rate.inputPer1MUsd +
            completionTokens / 1_000_000.0 * rate.outputPer1MUsd
    }
}
