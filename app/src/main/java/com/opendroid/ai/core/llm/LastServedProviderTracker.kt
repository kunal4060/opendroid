package com.opendroid.ai.core.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records which provider actually produced the most recent answer.
 *
 * Every provider response passes through [WrappedLLMProvider], which reports
 * the serving provider's name here (wired up in [LLMProviderFactory]). The
 * routing badge (A8) reads this so that after a mid-chat failover it can show
 * the provider that *really* answered, not just the one that was configured.
 */
@Singleton
class LastServedProviderTracker @Inject constructor() {

    private val _lastAnsweredBy = MutableStateFlow<String?>(null)

    /** Canonical name of the provider that served the latest response, or null before the first one. */
    val lastAnsweredBy: StateFlow<String?> = _lastAnsweredBy

    fun record(providerName: String) {
        val canonical = ProviderCatalog.canonicalName(providerName)
        if (canonical.isNotBlank()) {
            _lastAnsweredBy.value = canonical
        }
    }
}
