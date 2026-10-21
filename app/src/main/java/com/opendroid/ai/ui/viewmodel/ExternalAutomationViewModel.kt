package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.opendroid.ai.core.external.ExternalApiToken
import com.opendroid.ai.core.external.ExternalAutomationStore
import com.opendroid.ai.core.external.LastExternalRun
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → External automation (feature C7).
 *
 * State is read straight from [ExternalAutomationStore] (synchronous
 * app-private preferences) — the same values [com.opendroid.ai.core.external
 * .ExternalCommandGateway] enforces on every incoming intent.
 *
 * The plaintext token exists in memory only in the moment it is generated:
 * [generateToken] returns it so the section can show it ONCE for copying
 * into Tasker/ADB scripts; what persists is the salted-hash record plus a
 * 4-character display hint.
 */
@HiltViewModel
class ExternalAutomationViewModel @Inject constructor(
    private val store: ExternalAutomationStore
) : ViewModel() {

    private val _enabled = MutableStateFlow(store.isEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _tokenHint = MutableStateFlow(store.tokenHint())
    val tokenHint: StateFlow<String?> = _tokenHint.asStateFlow()

    private val _lastRun = MutableStateFlow(store.lastRun())
    val lastRun: StateFlow<LastExternalRun?> = _lastRun.asStateFlow()

    /**
     * The most recently generated plaintext token, held in memory only
     * until the section's one-time dialog is dismissed. It used to live in
     * composable `remember` state — an activity recreation while the
     * dialog was open destroyed the only copy (only the token's hash
     * persists), forcing the user to regenerate and re-paste it into
     * Tasker/ADB. Never written to disk; cleared on dismiss and onCleared.
     */
    private val _newlyGeneratedToken = MutableStateFlow<String?>(null)
    val newlyGeneratedToken: StateFlow<String?> = _newlyGeneratedToken.asStateFlow()

    val hasToken: Boolean get() = store.tokenRecord() != null

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        _enabled.value = enabled
    }

    /**
     * Generates and stores a fresh token (invalidating any previous one) and
     * returns the plaintext for its one-time display. The caller must not
     * persist the returned value anywhere.
     */
    fun generateToken(): String {
        val token = ExternalApiToken.generate()
        store.setTokenRecord(
            record = ExternalApiToken.createRecord(token),
            hint = ExternalApiToken.hintFor(token)
        )
        _tokenHint.value = ExternalApiToken.hintFor(token)
        _newlyGeneratedToken.value = token
        return token
    }

    /** The one-time token dialog was dismissed — drop the plaintext copy. */
    fun dismissNewlyGeneratedToken() {
        _newlyGeneratedToken.value = null
    }

    /** Revokes the current token; all external intents are rejected until a new one is generated. */
    fun revokeToken() {
        store.clearToken()
        _tokenHint.value = null
        _newlyGeneratedToken.value = null
    }

    override fun onCleared() {
        _newlyGeneratedToken.value = null
        super.onCleared()
    }

    /** Re-reads the last-run record (runs finish asynchronously in the gateway). */
    fun refresh() {
        _enabled.value = store.isEnabled()
        _tokenHint.value = store.tokenHint()
        _lastRun.value = store.lastRun()
    }
}
