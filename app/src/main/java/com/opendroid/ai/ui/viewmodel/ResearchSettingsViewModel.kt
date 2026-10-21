package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.opendroid.ai.core.research.ResearchProviderKind
import com.opendroid.ai.core.research.ResearchSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Web research (feature D3).
 *
 * State is read straight from [ResearchSettingsStore] (the Keystore-backed
 * store the research pipeline reads), so what this screen shows is exactly
 * what a run will use. Saved keys are only ever surfaced as a 4-character
 * hint — the plaintext a user types exists in the text field and the store,
 * never in this ViewModel's state.
 */
@HiltViewModel
class ResearchSettingsViewModel @Inject constructor(
    private val store: ResearchSettingsStore
) : ViewModel() {

    private val _providerKind = MutableStateFlow(store.providerKind())
    val providerKind: StateFlow<ResearchProviderKind> = _providerKind.asStateFlow()

    private val _tavilyKeyHint = MutableStateFlow(hint(store.tavilyApiKey()))
    val tavilyKeyHint: StateFlow<String?> = _tavilyKeyHint.asStateFlow()

    private val _braveKeyHint = MutableStateFlow(hint(store.braveApiKey()))
    val braveKeyHint: StateFlow<String?> = _braveKeyHint.asStateFlow()

    private val _searxngUrl = MutableStateFlow(store.searxngInstanceUrl() ?: "")
    val searxngUrl: StateFlow<String> = _searxngUrl.asStateFlow()

    /** True when the currently selected provider has everything a run needs. */
    val isConfigured: Boolean
        get() = store.configuration() is com.opendroid.ai.core.research.ResearchConfiguration.Ready

    fun setProviderKind(kind: ResearchProviderKind) {
        store.setProviderKind(kind)
        _providerKind.value = kind
    }

    fun saveTavilyApiKey(key: String) {
        store.setTavilyApiKey(key)
        _tavilyKeyHint.value = hint(store.tavilyApiKey())
    }

    fun clearTavilyApiKey() {
        store.setTavilyApiKey(null)
        _tavilyKeyHint.value = null
    }

    fun saveBraveApiKey(key: String) {
        store.setBraveApiKey(key)
        _braveKeyHint.value = hint(store.braveApiKey())
    }

    fun clearBraveApiKey() {
        store.setBraveApiKey(null)
        _braveKeyHint.value = null
    }

    fun saveSearxngUrl(url: String) {
        store.setSearxngInstanceUrl(url)
        _searxngUrl.value = store.searxngInstanceUrl() ?: ""
    }

    fun refresh() {
        _providerKind.value = store.providerKind()
        _tavilyKeyHint.value = hint(store.tavilyApiKey())
        _braveKeyHint.value = hint(store.braveApiKey())
        _searxngUrl.value = store.searxngInstanceUrl() ?: ""
    }

    private fun hint(key: String?): String? =
        key?.takeIf { it.isNotBlank() }?.let { "••••" + it.takeLast(4) }
}
