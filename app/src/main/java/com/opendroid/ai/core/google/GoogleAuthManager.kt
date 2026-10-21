package com.opendroid.ai.core.google

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of the Credential Manager account sign-in (identity only, no API scopes). */
sealed interface GoogleSignInResult {
    data class SignedIn(val email: String, val displayName: String?) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Failed(val message: String) : GoogleSignInResult
}

/** Outcome of an Identity AuthorizationClient authorization for API scopes. */
sealed interface GoogleAuthorizeResult {
    /** Token + the scopes Google actually granted (may be a subset). */
    data class Authorized(val accessToken: String, val grantedScopes: Set<String>) : GoogleAuthorizeResult

    /** User interaction is required; the UI must launch [pendingIntent]. */
    data class ResolutionRequired(val pendingIntent: PendingIntent) : GoogleAuthorizeResult

    data class Failed(val message: String) : GoogleAuthorizeResult
}

/**
 * D6 — Google auth, built on the current (non-deprecated) Android stack:
 *
 *  - **Sign-in** (who the account is): Credential Manager with
 *    [GetGoogleIdOption], keyed by the web client ID in [GoogleAuthConfig].
 *  - **Authorization** (API access): Identity [AuthorizationClient] —
 *    returns a short-lived access token plus the granted scopes. When Google
 *    needs user interaction it returns a resolution [PendingIntent] instead;
 *    the UI launches it and calls [authorize] again on success.
 *
 * Android flows receive **no refresh token**: on expiry (local TTL) or a 401
 * the manager simply re-runs authorization, which completes silently while
 * the user's consent persists, and surfaces [GoogleAuthorizeResult.ResolutionRequired]
 * when it no longer does — the UI turns that into a reconnect prompt.
 * Tokens and grant state persist only in [GoogleAccountStore] (Keystore-backed).
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: GoogleAccountStore,
    private val httpClient: OkHttpClient
) {

    fun isConnected(): Boolean = store.isConnected()

    /**
     * Credential Manager sign-in. Establishes the account identity and stores
     * it; grants no API scopes (that is [authorize]'s job).
     */
    suspend fun signIn(activity: Activity): GoogleSignInResult {
        if (!GoogleAuthConfig.isConfigured) {
            return GoogleSignInResult.Failed("Google sign-in is not configured in this build.")
        }
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(GoogleAuthConfig.webClientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        return try {
            val response = CredentialManager.create(activity).getCredential(
                context = activity,
                request = request
            )
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val email = googleCredential.id
                store.saveConnection(
                    email = email,
                    displayName = googleCredential.displayName,
                    grantedScopes = store.grantedScopes()
                )
                GoogleSignInResult.SignedIn(email, googleCredential.displayName)
            } else {
                GoogleSignInResult.Failed("Unexpected credential type returned by Credential Manager.")
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Failed(e.message ?: "Credential Manager sign-in failed.")
        }
    }

    /**
     * Runs authorization for [scopeUris]. On success the token is cached in
     * the store (45-minute local TTL — the API result carries no expiry) and
     * the granted scopes are unioned into the stored grant record.
     */
    suspend fun authorize(activity: Activity, scopeUris: List<String>): GoogleAuthorizeResult {
        val scopes = scopeUris.map { Scope(it) }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build()
        val result: AuthorizationResult = try {
            Identity.getAuthorizationClient(activity).authorize(request).awaitResult()
        } catch (e: ApiException) {
            return GoogleAuthorizeResult.Failed(
                "Google authorization failed (status ${e.statusCode}). If this build's " +
                    "signing certificate is not registered in the Google Cloud project, " +
                    "authorization cannot succeed — see the setup steps."
            )
        } catch (e: Exception) {
            return GoogleAuthorizeResult.Failed(e.message ?: "Google authorization failed.")
        }
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: return GoogleAuthorizeResult.Failed("Authorization needs interaction but returned no intent.")
            return GoogleAuthorizeResult.ResolutionRequired(pendingIntent)
        }
        val token = result.accessToken
            ?: return GoogleAuthorizeResult.Failed("Authorization returned no access token.")
        val granted = result.grantedScopes.map { it.scopeUri }.toSet()
        store.addGrantedScopes(granted)
        store.saveAccessToken(token, System.currentTimeMillis() + TOKEN_TTL_MILLIS)
        return GoogleAuthorizeResult.Authorized(token, granted)
    }

    /**
     * Returns a usable access token for [scopeUris]: the cached one when it
     * is fresh and the scopes are already granted, otherwise a fresh
     * [authorize] run (silent while consent persists).
     */
    suspend fun validAccessToken(activity: Activity, scopeUris: List<String>): GoogleAuthorizeResult {
        val cached = store.cachedAccessToken()
        val granted = store.grantedScopes()
        if (cached != null &&
            store.tokenExpiryMillis() > System.currentTimeMillis() + TOKEN_EXPIRY_MARGIN_MILLIS &&
            granted.containsAll(scopeUris)
        ) {
            return GoogleAuthorizeResult.Authorized(cached, granted)
        }
        return authorize(activity, scopeUris)
    }

    /** Drops the cached token so the next call re-authorizes (used after a 401). */
    fun invalidateCachedToken() = store.clearAccessToken()

    /**
     * Disconnects: best-effort revocation of the current access token at
     * Google's OAuth endpoint, Credential Manager state cleared, and every
     * local grant record wiped. (The Google-side grant itself can also be
     * removed at any time from the user's Google Account permissions page.)
     */
    suspend fun disconnect() {
        val token = store.cachedAccessToken()
        if (token != null) {
            try {
                withContext(Dispatchers.IO) {
                    val body = FormBody.Builder().add("token", token).build()
                    val request = Request.Builder()
                        .url(REVOKE_URL)
                        .post(body)
                        .build()
                    httpClient.newCall(request).execute().close()
                }
            } catch (_: Exception) {
                // Best effort: local disconnect proceeds regardless.
            }
        }
        try {
            CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // Best effort, same reasoning as above.
        }
        store.clearAll()
    }

    companion object {
        /** Local TTL for a cached access token (API results expose no expiry). */
        const val TOKEN_TTL_MILLIS = 45 * 60 * 1000L

        /** Re-authorize when less than this remains on the cached token. */
        const val TOKEN_EXPIRY_MARGIN_MILLIS = 60 * 1000L

        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    }
}

/** Awaits a Play services [Task] without the kotlinx-coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
