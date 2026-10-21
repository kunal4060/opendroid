package com.opendroid.ai.data.models

import com.opendroid.ai.core.llm.ProviderCatalog

/**
 * Per-chat model resolution for the in-chat model picker (feature A2).
 *
 * A chat session may carry a provider/model override (persisted on the
 * session row). The override is applied to a COPY of the global [LLMConfig]
 * at send time and threaded into the task's provider as a snapshot - the
 * persisted global config is never mutated for a per-chat pick, so other
 * chats and the Settings default are unaffected, and background tasks
 * pinned to other sessions cannot be cross-contaminated.
 *
 * Resolution is defensive, mirroring [selectedModelFor]'s style: an
 * override that names an unknown provider, or a provider that is no
 * longer configured (its key/endpoint was removed in Settings after the
 * override was picked), resolves to NO override - the caller falls back
 * to the global config and any UI shows the effective (global) value,
 * never the stale override.
 */

/**
 * Whether [providerName] could actually serve a request under [config]:
 * a real credential/endpoint is present, or the provider needs none.
 * Mirrors the configured-check the provider factory performs before
 * treating a provider as an available fallback.
 */
fun isProviderConfiguredForUse(config: LLMConfig, providerName: String): Boolean {
    val provider = ProviderCatalog.canonicalName(providerName)
    if (!ProviderCatalog.isKnown(provider)) return false
    if (ProviderCatalog.isOnDevice(provider)) return true
    return when (provider) {
        "Ollama" -> config.ollamaUrl.isNotBlank()
        "Copilot API" -> config.copilotUrl.isNotBlank()
        "Custom OpenAI Compatible" ->
            config.customEndpoints[provider].orEmpty().isNotBlank() &&
                config.apiKeys[provider].orEmpty().isNotBlank()
        else -> {
            val model = config.selectedModelFor(provider)
            !ProviderCatalog.requiresApiKey(provider) ||
                (provider == "Google Gemini" && model == "gemini-nano") ||
                config.apiKeys[provider].orEmpty().isNotBlank()
        }
    }
}

/**
 * The effective config for a session with the given override columns, or
 * null when there is no usable override (none set, unknown provider, or
 * the provider is no longer configured) - null means "use the global
 * config unchanged".
 *
 * [modelOverride] is applied through [withSelectedModel], so an
 * untrusted Claude ID is coerced to the catalog default exactly as a
 * Settings pick would be; raw IDs are never trusted.
 */
fun LLMConfig.effectiveWithSessionOverride(
    providerOverride: String?,
    modelOverride: String?
): LLMConfig? {
    val rawProvider = providerOverride?.trim().orEmpty()
    if (rawProvider.isEmpty()) return null
    if (!ProviderCatalog.isKnown(rawProvider)) return null
    val provider = ProviderCatalog.canonicalName(rawProvider)

    var effective = withActiveProvider(provider)
    val model = modelOverride?.trim().orEmpty()
    if (model.isNotEmpty()) {
        effective = effective.withSelectedModel(provider, model)
    }
    // Validate configured-ness on the EFFECTIVE config: its selected model
    // for the provider is the one the request would actually carry (this is
    // what lets a keyless Gemini override through only for gemini-nano).
    if (!isProviderConfiguredForUse(effective, provider)) return null
    return effective
}
