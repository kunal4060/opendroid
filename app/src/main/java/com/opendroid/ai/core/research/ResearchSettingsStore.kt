package com.opendroid.ai.core.research

import android.content.Context
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import java.nio.charset.StandardCharsets

/** Which search backend a research run uses (feature D3). */
enum class ResearchProviderKind(val displayName: String) {
    TAVILY("Tavily"),
    BRAVE("Brave Search"),
    SEARXNG("SearXNG (self-hosted)")
}

/**
 * The resolved research configuration the pipeline runs with. A run is
 * only possible in the [Ready] state — anything else is surfaced to the
 * user verbatim ("set up research in Settings"), never silently replaced
 * by an ungrounded answer or a fabricated source list.
 */
sealed interface ResearchConfiguration {
    data class Ready(
        val kind: ResearchProviderKind,
        /** Null for SearXNG, which is keyless by design. */
        val apiKey: String?,
        /** SearXNG instance base URL; null for the hosted providers. */
        val instanceUrl: String?
    ) : ResearchConfiguration

    data class NotConfigured(val reason: String) : ResearchConfiguration
}

/**
 * Persistence for web-research settings (feature D3): the chosen search
 * provider, its API key, and the SearXNG instance URL.
 *
 * Everything lives in the same Keystore-envelope layer as the other
 * secret stores ([KeystoreSecretRecords]
 * over an app-private preference file — the pattern A5's AppLockStore and
 * C7's token store use), so the search API keys never sit in plaintext
 * preferences or in the DataStore settings JSON that B2's backup exports.
 * The provider choice and instance URL are not secrets, but keeping them
 * beside the key keeps one store, one wipe story, one read path.
 */
interface ResearchSettingsStore {
    fun providerKind(): ResearchProviderKind
    fun setProviderKind(kind: ResearchProviderKind): Boolean

    fun tavilyApiKey(): String?
    fun setTavilyApiKey(key: String?): Boolean

    fun braveApiKey(): String?
    fun setBraveApiKey(key: String?): Boolean

    /** SearXNG instance base URL (no trailing slash), or null when unset. */
    fun searxngInstanceUrl(): String?
    fun setSearxngInstanceUrl(url: String?): Boolean

    /** Resolves the current settings into a run-ready (or honestly not-ready) configuration. */
    fun configuration(): ResearchConfiguration

    /** Removes every research record. Used by the forget-everything wipe. */
    fun clearAll(): Boolean
}

class AndroidResearchSettingsStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS
) : ResearchSettingsStore {

    private val records = KeystoreSecretRecords(
        storage = SharedPreferencesSecretRecordStorage(
            context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        ),
        cipher = AndroidKeyStoreAeadCipher(keyAlias)
    )

    private val lock = Any()

    override fun providerKind(): ResearchProviderKind =
        when (read(KEY_PROVIDER)) {
            ResearchProviderKind.BRAVE.name -> ResearchProviderKind.BRAVE
            ResearchProviderKind.SEARXNG.name -> ResearchProviderKind.SEARXNG
            else -> ResearchProviderKind.TAVILY
        }

    override fun setProviderKind(kind: ResearchProviderKind): Boolean =
        write(KEY_PROVIDER, kind.name)

    override fun tavilyApiKey(): String? = read(KEY_TAVILY_API_KEY)?.takeIf { it.isNotBlank() }

    override fun setTavilyApiKey(key: String?): Boolean =
        if (key.isNullOrBlank()) remove(KEY_TAVILY_API_KEY) else write(KEY_TAVILY_API_KEY, key.trim())

    override fun braveApiKey(): String? = read(KEY_BRAVE_API_KEY)?.takeIf { it.isNotBlank() }

    override fun setBraveApiKey(key: String?): Boolean =
        if (key.isNullOrBlank()) remove(KEY_BRAVE_API_KEY) else write(KEY_BRAVE_API_KEY, key.trim())

    override fun searxngInstanceUrl(): String? =
        read(KEY_SEARXNG_URL)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    override fun setSearxngInstanceUrl(url: String?): Boolean {
        val cleaned = url?.trim()?.trimEnd('/')
        return if (cleaned.isNullOrBlank()) remove(KEY_SEARXNG_URL) else write(KEY_SEARXNG_URL, cleaned)
    }

    override fun configuration(): ResearchConfiguration = when (providerKind()) {
        ResearchProviderKind.TAVILY -> {
            val key = tavilyApiKey()
            if (key != null) {
                ResearchConfiguration.Ready(ResearchProviderKind.TAVILY, apiKey = key, instanceUrl = null)
            } else {
                ResearchConfiguration.NotConfigured(
                    "Web research isn't set up yet. Add a Tavily API key in " +
                        "Settings → Web research (a free key works), then try again."
                )
            }
        }

        ResearchProviderKind.BRAVE -> {
            val key = braveApiKey()
            if (key != null) {
                ResearchConfiguration.Ready(ResearchProviderKind.BRAVE, apiKey = key, instanceUrl = null)
            } else {
                ResearchConfiguration.NotConfigured(
                    "Web research isn't set up yet. Add a Brave Search API key in " +
                        "Settings → Web research, then try again."
                )
            }
        }

        ResearchProviderKind.SEARXNG -> {
            val url = searxngInstanceUrl()
            if (url != null) {
                ResearchConfiguration.Ready(ResearchProviderKind.SEARXNG, apiKey = null, instanceUrl = url)
            } else {
                ResearchConfiguration.NotConfigured(
                    "Web research isn't set up yet. Enter the URL of a SearXNG instance " +
                        "you run or trust in Settings → Web research, then try again."
                )
            }
        }
    }

    override fun clearAll(): Boolean = synchronized(lock) {
        var allSuccess = true
        for (key in listOf(KEY_PROVIDER, KEY_TAVILY_API_KEY, KEY_BRAVE_API_KEY, KEY_SEARXNG_URL)) {
            if (!removeLocked(key)) allSuccess = false
        }
        allSuccess
    }

    private fun read(key: String): String? = synchronized(lock) {
        when (val result = records.read(key, aad(key))) {
            is SecretRecordResult.Success ->
                result.value?.let { String(it, StandardCharsets.UTF_8) }
            // An unreadable record reads as "absent" — for a search key that
            // fails safe: research reports itself not configured instead of
            // sending a corrupt key to the provider.
            else -> null
        }
    }

    private fun write(key: String, value: String): Boolean = synchronized(lock) {
        when (records.write(key, aad(key), value.toByteArray(StandardCharsets.UTF_8))) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    private fun remove(key: String): Boolean = synchronized(lock) { removeLocked(key) }

    private fun removeLocked(key: String): Boolean =
        when (records.removeRecord(key)) {
            is SecretRecordResult.Success -> true
            else -> false
        }

    private fun aad(key: String): String = "$AAD_PREFIX:$key"

    companion object {
        const val PREFERENCES_NAME = "opendroid_research"
        const val KEY_ALIAS = "opendroid.research.aes_gcm.v1"

        private const val AAD_PREFIX = "research"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_TAVILY_API_KEY = "tavily_api_key"
        private const val KEY_BRAVE_API_KEY = "brave_api_key"
        private const val KEY_SEARXNG_URL = "searxng_url"
    }
}
