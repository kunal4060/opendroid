package com.opendroid.ai.core.external

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The most recent accepted external run, kept so QUERY_STATUS can answer
 * "what happened with the last thing I asked OpenDroid to do?" without
 * guessing. States use [ExternalIntentContract]'s STATE_* values.
 */
@Serializable
data class LastExternalRun(
    val requestId: String?,
    /** "COMMAND" or "MACRO". */
    val kind: String,
    /** The instruction text or macro name, truncated for display/storage. */
    val label: String,
    val state: String,
    val summary: String?,
    val startedAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Persistence for external automation (feature C7): the master enable toggle
 * (default OFF), the API-token hash record, and the last external run.
 *
 * Everything lives in one app-private preference file. The token itself is
 * never written anywhere — only the salted-hash record produced by
 * [ExternalApiToken.createRecord] plus a 4-character display hint — so this
 * file holds no replayable credential even though it is plaintext storage.
 */
@Singleton
class ExternalAutomationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun isEnabled(): Boolean = synchronized(lock) {
        preferences.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            @Suppress("UseKtx") // commit(): the caller needs the durability result.
            preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()
        }
    }

    /** The [ExternalApiToken] hash record, or null when no token has been generated. */
    fun tokenRecord(): String? = synchronized(lock) {
        preferences.getString(KEY_TOKEN_RECORD, null)
    }

    /** Last 4 characters of the current token, for the Settings display only. */
    fun tokenHint(): String? = synchronized(lock) {
        preferences.getString(KEY_TOKEN_HINT, null)
    }

    fun setTokenRecord(record: String, hint: String) {
        synchronized(lock) {
            @Suppress("UseKtx")
            preferences.edit()
                .putString(KEY_TOKEN_RECORD, record)
                .putString(KEY_TOKEN_HINT, hint)
                .commit()
        }
    }

    /** Revokes the token entirely: every external intent is rejected until a new one is generated. */
    fun clearToken() {
        synchronized(lock) {
            @Suppress("UseKtx")
            preferences.edit()
                .remove(KEY_TOKEN_RECORD)
                .remove(KEY_TOKEN_HINT)
                .commit()
        }
    }

    fun lastRun(): LastExternalRun? = synchronized(lock) {
        val raw = preferences.getString(KEY_LAST_RUN, null) ?: return@synchronized null
        try {
            json.decodeFromString<LastExternalRun>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun setLastRun(run: LastExternalRun) {
        synchronized(lock) {
            @Suppress("UseKtx")
            preferences.edit()
                .putString(KEY_LAST_RUN, json.encodeToString(run))
                .commit()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "opendroid_external_automation"

        private const val KEY_ENABLED = "external_api_enabled"
        private const val KEY_TOKEN_RECORD = "external_api_token_record"
        private const val KEY_TOKEN_HINT = "external_api_token_hint"
        private const val KEY_LAST_RUN = "external_api_last_run"
    }
}
