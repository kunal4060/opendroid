package com.opendroid.ai.core.service

import android.content.Context
import android.content.SharedPreferences
import com.opendroid.ai.core.mcp.McpTransportKind
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretEnvelope
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class McpEndpointConfig(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val headers: Map<String, String>,
    /** D5 client transport: AUTO tries Streamable HTTP, then legacy HTTP+SSE. */
    val transport: McpTransportKind = McpTransportKind.AUTO,
    /** D5: user-consented plain-HTTP access for a local-network server. */
    val allowInsecureLocal: Boolean = false,
    /** D5: per-tool enable + rug-pull fingerprint, keyed by the server's tool name. */
    val toolSettings: Map<String, McpToolSetting> = emptyMap()
) {
    /** A tool is usable unless the user explicitly unticked it in Settings. */
    fun isToolEnabled(toolName: String): Boolean = toolSettings[toolName]?.enabled ?: true
}

/** Per-tool client state persisted with the endpoint (D5). */
data class McpToolSetting(
    val enabled: Boolean = true,
    /** Schema/description fingerprint at last sight; a change revokes the tool's grant. */
    val fingerprint: String = ""
)

@Serializable
private data class StoredMcpEndpoint(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val transport: String = "AUTO",
    val allowInsecureLocal: Boolean = false,
    val toolSettings: Map<String, StoredMcpToolSetting> = emptyMap()
)

@Serializable
private data class StoredMcpToolSetting(
    val enabled: Boolean = true,
    val fingerprint: String = ""
)

/**
 * Persists the MCP loopback access token and remote endpoint configs as
 * Keystore AES-GCM envelopes (same boundary as [com.opendroid.ai.core.security.ProviderCredentialStore]).
 */
@Singleton
class McpConfigStore private constructor(
    private val records: KeystoreSecretRecords,
    private val preferences: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        records = KeystoreSecretRecords(
            storage = SharedPreferencesSecretRecordStorage(
                context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            ),
            cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS)
        ),
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    )

    @Synchronized
    fun accessToken(): String {
        readSecret(TOKEN_KEY, TOKEN_AAD)?.takeIf { it.isNotEmpty() }?.let { return it }
        val token = UUID.randomUUID().toString().replace("-", "")
        writeSecret(TOKEN_KEY, TOKEN_AAD, token)
        return token
    }

    @Synchronized
    fun list(): List<McpEndpointConfig> {
        val raw = readSecret(ENDPOINTS_KEY, ENDPOINTS_AAD) ?: "[]"
        val elements = storedElements(raw) ?: return emptyList()
        return elements.mapNotNull { element -> decodeElement(element)?.let(::toConfig) }
    }

    @Synchronized
    fun upsert(config: McpEndpointConfig) {
        require(config.name.isNotBlank()) { "name is required" }
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) {
            "url must use http or https"
        }
        val elements = storedElements(readSecret(ENDPOINTS_KEY, ENDPOINTS_AAD) ?: "[]")
            ?: throw IllegalStateException(
                "Stored MCP endpoints could not be read; refusing to overwrite them"
            )
        val kept = elements.filterNot { elementName(it) == config.name }
        writeElements(kept + json.encodeToJsonElement(toStored(config)))
    }

    @Synchronized
    fun remove(name: String) {
        val elements = storedElements(readSecret(ENDPOINTS_KEY, ENDPOINTS_AAD) ?: "[]")
            ?: throw IllegalStateException(
                "Stored MCP endpoints could not be read; refusing to overwrite them"
            )
        writeElements(elements.filterNot { elementName(it) == name })
    }

    /** Replaces one endpoint's per-tool settings (D5), leaving everything else untouched. */
    @Synchronized
    fun updateToolSettings(name: String, toolSettings: Map<String, McpToolSetting>) {
        val elements = storedElements(readSecret(ENDPOINTS_KEY, ENDPOINTS_AAD) ?: "[]")
            ?: throw IllegalStateException(
                "Stored MCP endpoints could not be read; refusing to overwrite them"
            )
        var changed = false
        val updated = elements.map { element ->
            val stored = if (elementName(element) == name) decodeElement(element) else null
            if (stored != null) {
                changed = true
                json.encodeToJsonElement(
                    stored.copy(
                        toolSettings = toolSettings.mapValues { (_, setting) ->
                            StoredMcpToolSetting(
                                enabled = setting.enabled,
                                fingerprint = setting.fingerprint
                            )
                        }
                    )
                )
            } else {
                element
            }
        }
        if (changed) writeElements(updated)
    }

    /**
     * The stored endpoints blob as raw JSON elements — null when it is not
     * a JSON array at all. Writes are computed over these elements (never
     * over the decoded list), so an element this build cannot decode —
     * corrupt, or written by a newer format — is carried through verbatim
     * instead of being silently destroyed by the next upsert/remove, which
     * is what used to happen when one bad element emptied the whole list.
     * Only a blob that is not even an array refuses the write (by throwing
     * from the mutation), because then there is nothing safe to preserve
     * into.
     */
    private fun storedElements(raw: String): List<JsonElement>? = try {
        (json.parseToJsonElement(raw) as? JsonArray)?.toList()
    } catch (_: Exception) {
        null
    }

    private fun decodeElement(element: JsonElement): StoredMcpEndpoint? = try {
        json.decodeFromJsonElement<StoredMcpEndpoint>(element)
    } catch (_: Exception) {
        null
    }

    /** The `name` of a stored element, read leniently even when the element as a whole no longer decodes. */
    private fun elementName(element: JsonElement): String? =
        ((element as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

    private fun writeElements(elements: List<JsonElement>) {
        writeSecret(ENDPOINTS_KEY, ENDPOINTS_AAD, JsonArray(elements).toString())
    }

    private fun toConfig(stored: StoredMcpEndpoint): McpEndpointConfig = McpEndpointConfig(
        name = stored.name,
        url = stored.url,
        enabled = stored.enabled,
        headers = stored.headers,
        transport = runCatching { McpTransportKind.valueOf(stored.transport) }
            .getOrDefault(McpTransportKind.AUTO),
        allowInsecureLocal = stored.allowInsecureLocal,
        toolSettings = stored.toolSettings.mapValues { (_, setting) ->
            McpToolSetting(enabled = setting.enabled, fingerprint = setting.fingerprint)
        }
    )

    private fun toStored(config: McpEndpointConfig): StoredMcpEndpoint = StoredMcpEndpoint(
        name = config.name,
        url = config.url,
        enabled = config.enabled,
        headers = config.headers,
        transport = config.transport.name,
        allowInsecureLocal = config.allowInsecureLocal,
        toolSettings = config.toolSettings.mapValues { (_, setting) ->
            StoredMcpToolSetting(enabled = setting.enabled, fingerprint = setting.fingerprint)
        }
    )

    private fun readSecret(storageKey: String, aad: String): String? {
        when (val result = records.read(storageKey, aad)) {
            is SecretRecordResult.Success -> {
                val value = result.value ?: return migratePlaintext(storageKey, aad)
                return String(value, StandardCharsets.UTF_8)
            }
            SecretRecordResult.Unrecoverable -> return migratePlaintext(storageKey, aad)
            SecretRecordResult.StorageUnavailable ->
                error("MCP secret storage is unavailable for $storageKey")
        }
    }

    private fun writeSecret(storageKey: String, aad: String, plaintext: String) {
        when (
            records.write(storageKey, aad, plaintext.toByteArray(StandardCharsets.UTF_8))
        ) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable ->
                error("MCP secret key material is unrecoverable for $storageKey")
            SecretRecordResult.StorageUnavailable ->
                error("MCP secret storage is unavailable for $storageKey")
        }
    }

    /**
     * Upgrade values written by the initial unencrypted build into versioned
     * Keystore envelopes. Only migrates strings that are not already envelopes.
     */
    private fun migratePlaintext(storageKey: String, aad: String): String? {
        val raw = try {
            preferences.getString(storageKey, null)
        } catch (_: ClassCastException) {
            null
        } ?: return null
        if (SecretEnvelope.decode(raw) != null) return null
        writeSecret(storageKey, aad, raw)
        return raw
    }

    companion object {
        const val PREFERENCES = "opendroid_mcp"
        const val KEY_ALIAS = "opendroid.mcp_secrets.aes_gcm.v1"
        private const val TOKEN_KEY = "access_token"
        private const val ENDPOINTS_KEY = "endpoints"
        private const val TOKEN_AAD = "mcp-access-token"
        private const val ENDPOINTS_AAD = "mcp-endpoints"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** JVM test seam that keeps the production constructor Keystore-only. */
        internal fun createForTest(
            records: KeystoreSecretRecords,
            preferences: SharedPreferences
        ): McpConfigStore = McpConfigStore(records, preferences)
    }
}
