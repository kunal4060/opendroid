package com.opendroid.ai.ui.viewmodel

import android.app.Activity
import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.google.CalendarEventSource
import com.opendroid.ai.core.google.GmailMessageDetail
import com.opendroid.ai.core.google.GmailMessageSummary
import com.opendroid.ai.core.google.GoogleApiException
import com.opendroid.ai.core.google.GoogleAuthConfig
import com.opendroid.ai.core.google.GoogleAuthManager
import com.opendroid.ai.core.google.GoogleAuthorizeResult
import com.opendroid.ai.core.google.GoogleAccountStore
import com.opendroid.ai.core.google.GoogleCalendarApi
import com.opendroid.ai.core.google.GoogleCalendarEvent
import com.opendroid.ai.core.google.GoogleScopes
import com.opendroid.ai.core.google.GoogleSignInResult
import com.opendroid.ai.core.google.GmailApi
import com.opendroid.ai.core.google.LocalCalendarRepository
import com.opendroid.ai.core.google.NewCalendarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoogleUiState(
    val configured: Boolean = false,
    val accountEmail: String? = null,
    val accountName: String? = null,
    val grantedScopes: List<String> = emptyList(),
    val calendarEvents: List<GoogleCalendarEvent> = emptyList(),
    /** Which path served [calendarEvents] — shown in the UI, never blurred. */
    val calendarSource: CalendarEventSource? = null,
    val localCalendarPermissionGranted: Boolean = false,
    val messages: List<GmailMessageSummary> = emptyList(),
    val mailLoaded: Boolean = false,
    val openedMessage: GmailMessageDetail? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Token/grant died (revoked or expired): show the reconnect prompt. */
    val needsReconnect: Boolean = false
) {
    val isConnected: Boolean get() = accountEmail != null
    val hasCalendarScope: Boolean get() = GoogleScopes.CALENDAR_EVENTS in grantedScopes
    val hasGmailScope: Boolean get() = GoogleScopes.GMAIL_READONLY in grantedScopes
}

/** One-shot UI events the screen must act on with an Activity result launcher. */
sealed interface GoogleUiEvent {
    /** Google needs user interaction to authorize; launch this intent. */
    data class LaunchResolution(val pendingIntent: PendingIntent) : GoogleUiEvent
}

private enum class AfterAuth { LOAD_CALENDAR, LOAD_MAIL, NONE }

/**
 * Settings → Connected accounts → Google (feature D6).
 *
 * Owns the connect flow (Credential Manager sign-in, then AuthorizationClient
 * for scopes — calendar first, Gmail only on explicit enable), the Calendar
 * API reads/writes, the Gmail read paths, and the honest states around them:
 * unconfigured build → setup guidance; expired/revoked grant → reconnect
 * prompt; API unreachable → on-device calendar shown *labelled as such*.
 */
@HiltViewModel
class GoogleAccountViewModel @Inject constructor(
    private val store: GoogleAccountStore,
    private val authManager: GoogleAuthManager,
    private val calendarApi: GoogleCalendarApi,
    private val gmailApi: GmailApi,
    private val localCalendar: LocalCalendarRepository
) : ViewModel() {

    private val _state = MutableStateFlow(GoogleUiState(configured = GoogleAuthConfig.isConfigured))
    val state: StateFlow<GoogleUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GoogleUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<GoogleUiEvent> = _events.asSharedFlow()

    private var pendingScopes: List<String> = emptyList()
    private var pendingAfterAuth: AfterAuth = AfterAuth.NONE

    init {
        refreshConnectionState()
    }

    /** Called when the screen becomes visible; loads whichever calendar path applies. */
    fun onShown(activity: Activity) {
        refreshConnectionState()
        val current = _state.value
        if (current.isConnected && current.hasCalendarScope && current.configured) {
            loadApiCalendar(activity)
        } else {
            loadLocalCalendar()
        }
    }

    /** Connect button: sign in, then authorize the calendar scope. */
    fun connect(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, needsReconnect = false) }
            when (val result = authManager.signIn(activity)) {
                is GoogleSignInResult.SignedIn -> {
                    refreshConnectionState()
                    beginAuthorize(activity, GoogleScopes.CONNECT_SCOPES, AfterAuth.LOAD_CALENDAR)
                }
                is GoogleSignInResult.Cancelled -> {
                    _state.update { it.copy(loading = false) }
                }
                is GoogleSignInResult.Failed -> {
                    _state.update { it.copy(loading = false, error = result.message) }
                }
            }
        }
    }

    /** Reconnect button shown when a grant died: re-runs authorization. */
    fun reconnect(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(needsReconnect = false, error = null) }
            beginAuthorize(activity, GoogleScopes.CONNECT_SCOPES, AfterAuth.LOAD_CALENDAR)
        }
    }

    /** "Enable Gmail access": incremental authorization for the Gmail scope. */
    fun enableMail(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            beginAuthorize(activity, GoogleScopes.MAIL_SCOPES, AfterAuth.LOAD_MAIL)
        }
    }

    /** The resolution activity returned; re-run authorization to collect the result. */
    fun onResolutionFinished(activity: Activity, success: Boolean) {
        viewModelScope.launch {
            if (!success) {
                _state.update {
                    it.copy(loading = false, error = "Google authorization was not completed.")
                }
                return@launch
            }
            val scopes = pendingScopes
            val after = pendingAfterAuth
            pendingScopes = emptyList()
            pendingAfterAuth = AfterAuth.NONE
            runAuthorize(activity, scopes, after)
        }
    }

    /** Creates an event on the primary Google calendar, then reloads the list. */
    fun createEvent(activity: Activity, draft: NewCalendarEvent) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val created = withTokenRetry(activity, listOf(GoogleScopes.CALENDAR_EVENTS)) { token ->
                    calendarApi.createEvent(token, draft)
                }
                _state.update { it.copy(loading = false) }
                if (created != null) loadApiCalendar(activity)
            } catch (e: GoogleApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't create the event.") }
            }
        }
    }

    /** Runs a Gmail search (empty query = most recent), metadata-first. */
    fun searchMail(activity: Activity, query: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, openedMessage = null) }
            try {
                val results = withTokenRetry(activity, listOf(GoogleScopes.GMAIL_READONLY)) { token ->
                    gmailApi.search(token, query)
                }
                _state.update {
                    it.copy(loading = false, messages = results ?: it.messages, mailLoaded = results != null)
                }
            } catch (e: GoogleApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load mail.") }
            }
        }
    }

    /** Opens one message (the only `format=full` fetch in the feature). */
    fun openMessage(activity: Activity, messageId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val detail = withTokenRetry(activity, listOf(GoogleScopes.GMAIL_READONLY)) { token ->
                    gmailApi.getMessage(token, messageId)
                }
                _state.update { it.copy(loading = false, openedMessage = detail ?: it.openedMessage) }
            } catch (e: GoogleApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open the message.") }
            }
        }
    }

    fun closeMessage() {
        _state.update { it.copy(openedMessage = null) }
    }

    fun onLocalCalendarPermissionResult() {
        loadLocalCalendar()
    }

    /** Disconnect: revoke (best effort) + wipe the local grant record. */
    fun disconnect() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            authManager.disconnect()
            refreshConnectionState()
            _state.update {
                it.copy(
                    loading = false,
                    messages = emptyList(),
                    mailLoaded = false,
                    openedMessage = null,
                    needsReconnect = false
                )
            }
            loadLocalCalendar()
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun refreshConnectionState() {
        _state.update {
            it.copy(
                configured = GoogleAuthConfig.isConfigured,
                accountEmail = store.accountEmail(),
                accountName = store.accountName(),
                grantedScopes = store.grantedScopes().toList(),
                localCalendarPermissionGranted = localCalendar.hasPermission()
            )
        }
    }

    private suspend fun beginAuthorize(activity: Activity, scopes: List<String>, after: AfterAuth) {
        runAuthorize(activity, scopes, after)
    }

    private suspend fun runAuthorize(activity: Activity, scopes: List<String>, after: AfterAuth) {
        when (val result = authManager.authorize(activity, scopes)) {
            is GoogleAuthorizeResult.Authorized -> {
                _state.update { it.copy(loading = false, needsReconnect = false) }
                refreshConnectionState()
                when (after) {
                    AfterAuth.LOAD_CALENDAR -> loadApiCalendar(activity)
                    AfterAuth.LOAD_MAIL -> searchMail(activity, "")
                    AfterAuth.NONE -> Unit
                }
            }
            is GoogleAuthorizeResult.ResolutionRequired -> {
                pendingScopes = scopes
                pendingAfterAuth = after
                _state.update { it.copy(loading = false) }
                _events.tryEmit(GoogleUiEvent.LaunchResolution(result.pendingIntent))
            }
            is GoogleAuthorizeResult.Failed -> {
                _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    private fun loadApiCalendar(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val events = withTokenRetry(activity, listOf(GoogleScopes.CALENDAR_EVENTS)) { token ->
                    calendarApi.listUpcomingEvents(token)
                }
                if (events == null) {
                    _state.update { it.copy(loading = false) }
                    loadLocalCalendar()
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        calendarEvents = events,
                        calendarSource = CalendarEventSource.GOOGLE_API
                    )
                }
            } catch (e: GoogleApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
                loadLocalCalendar()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load Google Calendar.") }
                loadLocalCalendar()
            }
        }
    }

    private fun loadLocalCalendar() {
        viewModelScope.launch {
            val events = localCalendar.upcomingEvents()
            _state.update {
                it.copy(
                    calendarEvents = events,
                    calendarSource = CalendarEventSource.DEVICE,
                    localCalendarPermissionGranted = localCalendar.hasPermission()
                )
            }
        }
    }

    /**
     * Runs [block] with a valid token; on a 401 the cached token is dropped
     * and the call retried once with a freshly authorized token. Returns null
     * when no token can be obtained (grant dead → [GoogleUiState.needsReconnect]
     * is set by [accessTokenOrNull], or an error was recorded).
     */
    private suspend fun <T> withTokenRetry(
        activity: Activity,
        scopes: List<String>,
        block: suspend (String) -> T
    ): T? {
        val token = accessTokenOrNull(activity, scopes) ?: return null
        return try {
            block(token)
        } catch (e: GoogleApiException) {
            if (!e.isUnauthorized) throw e
            authManager.invalidateCachedToken()
            val fresh = accessTokenOrNull(activity, scopes) ?: return null
            block(fresh)
        }
    }

    /**
     * Gets a token for [scopes]; on a dead grant this flips [GoogleUiState.needsReconnect]
     * (the reconnect prompt) instead of failing silently, and returns null.
     */
    private suspend fun accessTokenOrNull(activity: Activity, scopes: List<String>): String? {
        return when (val result = authManager.validAccessToken(activity, scopes)) {
            is GoogleAuthorizeResult.Authorized -> result.accessToken
            is GoogleAuthorizeResult.ResolutionRequired -> {
                // Silent re-authorization needs interaction: that is exactly
                // the "grant expired or was revoked" case — prompt to reconnect.
                _state.update { it.copy(needsReconnect = true) }
                null
            }
            is GoogleAuthorizeResult.Failed -> {
                _state.update { it.copy(error = result.message) }
                null
            }
        }
    }
}
