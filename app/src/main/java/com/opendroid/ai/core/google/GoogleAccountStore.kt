package com.opendroid.ai.core.google

import android.content.Context
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D6 — persistence for the Google connection: account identity, the set of
 * granted OAuth scopes, and the short-lived access token.
 *
 * Everything lives in the same Keystore-envelope layer as the other secret
 * stores ([KeystoreSecretRecords] over an app-private preference file), so
 * the access token never sits in plaintext preferences. Access tokens are
 * issued by the Google Identity authorization library and are short-lived
 * (about an hour); the cached copy is dropped on disconnect, on a 401, and
 * once [tokenExpiryMillis] passes, after which [GoogleAuthManager] re-runs
 * authorization (silent while the user's consent persists). Android OAuth
 * flows receive no refresh token — there is deliberately nothing else
 * long-lived to store.
 */
@Singleton
class GoogleAccountStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val records = KeystoreSecretRecords(
        storage = SharedPreferencesSecretRecordStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        ),
        cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS)
    )

    private val lock = Any()

    fun accountEmail(): String? = read(KEY_EMAIL)

    fun accountName(): String? = read(KEY_DISPLAY_NAME)

    fun isConnected(): Boolean = accountEmail() != null

    /** The OAuth scope URIs the user has actually granted (may be empty). */
    fun grantedScopes(): Set<String> =
        read(KEY_GRANTED_SCOPES)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    fun cachedAccessToken(): String? = read(KEY_ACCESS_TOKEN)

    /** Epoch millis after which [cachedAccessToken] must not be used. */
    fun tokenExpiryMillis(): Long = read(KEY_TOKEN_EXPIRY)?.toLongOrNull() ?: 0L

    fun saveConnection(email: String, displayName: String?, grantedScopes: Set<String>) {
        write(KEY_EMAIL, email)
        if (displayName != null) write(KEY_DISPLAY_NAME, displayName) else remove(KEY_DISPLAY_NAME)
        write(KEY_GRANTED_SCOPES, grantedScopes.joinToString("\n"))
    }

    /** Unions newly granted scopes into the stored record. */
    fun addGrantedScopes(scopes: Set<String>) {
        if (scopes.isEmpty()) return
        write(KEY_GRANTED_SCOPES, (grantedScopes() + scopes).joinToString("\n"))
    }

    fun saveAccessToken(token: String, expiryMillis: Long) {
        write(KEY_ACCESS_TOKEN, token)
        write(KEY_TOKEN_EXPIRY, expiryMillis.toString())
    }

    fun clearAccessToken() {
        remove(KEY_ACCESS_TOKEN)
        remove(KEY_TOKEN_EXPIRY)
    }

    /** Removes every Google-connection record (used by Disconnect). */
    fun clearAll(): Boolean = synchronized(lock) {
        var allSuccess = true
        for (key in listOf(KEY_EMAIL, KEY_DISPLAY_NAME, KEY_GRANTED_SCOPES, KEY_ACCESS_TOKEN, KEY_TOKEN_EXPIRY)) {
            if (!remove(key)) allSuccess = false
        }
        allSuccess
    }

    private fun read(key: String): String? = synchronized(lock) {
        when (val result = records.read(key, aad(key))) {
            is SecretRecordResult.Success ->
                result.value?.let { String(it, StandardCharsets.UTF_8) }
            // An unreadable record reads as "absent": the envelope being
            // unrecoverable means the key material is gone and the recovery
            // path is simply connecting again.
            else -> null
        }
    }

    private fun write(key: String, value: String): Boolean = synchronized(lock) {
        when (records.write(key, aad(key), value.toByteArray(StandardCharsets.UTF_8))) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    private fun remove(key: String): Boolean = synchronized(lock) {
        when (records.removeRecord(key)) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    private fun aad(key: String): String = "$AAD_PREFIX:$key"

    companion object {
        const val PREFERENCES_NAME = "opendroid_google_account"
        const val KEY_ALIAS = "opendroid.google_account.aes_gcm.v1"

        private const val AAD_PREFIX = "google-account"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_GRANTED_SCOPES = "granted_scopes"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
    }
}
