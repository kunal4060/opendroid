package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.mcp.McpClientManager
import com.opendroid.ai.core.mcp.McpNames
import com.opendroid.ai.core.mcp.McpServerState
import com.opendroid.ai.core.mcp.McpTool
import com.opendroid.ai.core.mcp.McpToolBridge
import com.opendroid.ai.core.mcp.McpTransportKind
import com.opendroid.ai.core.service.McpConfigStore
import com.opendroid.ai.core.service.McpEndpointConfig
import com.opendroid.ai.core.service.McpToolSetting
import com.opendroid.ai.data.models.effectiveGrantedActions
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One tool row in the server detail list. */
data class McpToolUi(
    val tool: McpTool,
    val actionName: String,
    val enabled: Boolean,
    val alwaysAllow: Boolean
)

/** One configured server with its live connection state and tools. */
data class McpServerUi(
    val config: McpEndpointConfig,
    val state: McpServerState,
    val tools: List<McpToolUi>
)

/** Editable form model for the add/edit server dialog. */
data class McpServerDraft(
    val name: String = "",
    val url: String = "",
    val transport: McpTransportKind = McpTransportKind.AUTO,
    val headersText: String = "",
    val allowInsecureLocal: Boolean = false,
    val enabled: Boolean = true
) {
    /**
     * [existingNames] are the names of all configured servers and
     * [originalName] the name of the server being edited (null when
     * adding). Servers are stored keyed by name, so saving under another
     * server's name would upsert over — and silently destroy — that
     * server's config; a collision with any name other than the edited
     * server's own is rejected.
     */
    fun validationError(
        existingNames: Set<String> = emptySet(),
        originalName: String? = null
    ): String? = when {
        name.isBlank() -> "Give the server a name"
        !url.startsWith("http://") && !url.startsWith("https://") ->
            "URL must start with http:// or https://"
        name.trim() != originalName && name.trim() in existingNames ->
            "A server named '${name.trim()}' already exists — choose a different name"
        else -> null
    }

    companion object {
        fun from(config: McpEndpointConfig): McpServerDraft = McpServerDraft(
            name = config.name,
            url = config.url,
            transport = config.transport,
            headersText = config.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            allowInsecureLocal = config.allowInsecureLocal,
            enabled = config.enabled
        )

        /** Parses the "Key: Value" per-line header editor text. */
        fun parseHeaders(text: String): Map<String, String> =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains(':') }
                .associate { line ->
                    line.substringBefore(':').trim() to line.substringAfter(':').trim()
                }
                .filterKeys { it.isNotEmpty() }
    }
}

/**
 * Settings → MCP Servers (D5). All persistence goes through the existing
 * Keystore-backed [McpConfigStore]; connection work through
 * [McpClientManager]/[McpToolBridge]; always-allow grants through the same
 * LLMConfig grant map the built-in approval flow uses.
 */
@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val configStore: McpConfigStore,
    private val manager: McpClientManager,
    private val bridge: McpToolBridge,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _servers = MutableStateFlow<List<McpServerUi>>(emptyList())
    val servers: StateFlow<List<McpServerUi>> = _servers.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    @Volatile
    private var grantedActions: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            manager.states.collectLatest { refresh() }
        }
        viewModelScope.launch {
            settingsRepository.llmConfig.collectLatest { config ->
                grantedActions = config.effectiveGrantedActions().keys
                refresh()
            }
        }
        refresh()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            val configs = withContext(Dispatchers.IO) { configStore.list() }
            val grants = grantedActions
            _servers.value = configs.map { config ->
                val tools = manager.toolsFor(config.name)
                val actionNames = McpNames.assignActionNames(config.name, tools.map { it.name })
                McpServerUi(
                    config = config,
                    state = if (!config.enabled) McpServerState.Disabled
                    else manager.stateFor(config.name),
                    tools = tools.map { tool ->
                        McpToolUi(
                            tool = tool,
                            actionName = actionNames.getValue(tool.name),
                            enabled = config.isToolEnabled(tool.name),
                            alwaysAllow = actionNames.getValue(tool.name) in grants
                        )
                    }
                )
            }
        }
    }

    fun saveServer(originalName: String?, draft: McpServerDraft) {
        draft.validationError()?.let { _message.value = it; return }
        viewModelScope.launch {
            val allConfigs = withContext(Dispatchers.IO) { configStore.list() }
            // Authoritative duplicate-name check (the dialog validates too,
            // but the store upserts by name — never let an Add, or a rename
            // onto another server, overwrite that server's config).
            val trimmedName = draft.name.trim()
            if (allConfigs.any { it.name == trimmedName && it.name != originalName }) {
                _message.value =
                    "A server named '$trimmedName' already exists — choose a different name"
                return@launch
            }
            val existing = originalName?.let { name ->
                allConfigs.firstOrNull { it.name == name }
            }
            val config = McpEndpointConfig(
                name = draft.name.trim(),
                url = draft.url.trim(),
                enabled = draft.enabled,
                headers = McpServerDraft.parseHeaders(draft.headersText),
                transport = draft.transport,
                allowInsecureLocal = draft.allowInsecureLocal,
                // Tool prefs follow the server across edits (and renames).
                toolSettings = existing?.toolSettings ?: emptyMap()
            )
            try {
                withContext(Dispatchers.IO) {
                    configStore.upsert(config)
                    if (originalName != null && originalName != config.name) {
                        configStore.remove(originalName)
                    }
                }
            } catch (error: Exception) {
                // The store refuses to write over an endpoints blob it
                // cannot read (corrupt / newer format) rather than wiping
                // every server — surface that instead of crashing or
                // pretending the save happened.
                _message.value =
                    "Server storage could not be read, so nothing was saved: ${error.message ?: "unknown error"}"
                return@launch
            }
            if (originalName != null && originalName != config.name) {
                bridge.onServerRemoved(originalName)
            }
            bridge.refreshServer(config.name)
            refresh()
        }
    }

    fun deleteServer(name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { configStore.remove(name) }
            } catch (error: Exception) {
                _message.value =
                    "Server storage could not be read, so '$name' was not removed: ${error.message ?: "unknown error"}"
                return@launch
            }
            bridge.onServerRemoved(name)
            _message.value = "Removed MCP server '$name' — its tools left the agent's set"
            refresh()
        }
    }

    fun setServerEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) {
                configStore.list().firstOrNull { it.name == name }
            } ?: return@launch
            try {
                withContext(Dispatchers.IO) { configStore.upsert(config.copy(enabled = enabled)) }
            } catch (error: Exception) {
                _message.value =
                    "Server storage could not be read, so the change was not saved: ${error.message ?: "unknown error"}"
                return@launch
            }
            bridge.refreshServer(name)
            refresh()
        }
    }

    fun testConnection(name: String) {
        viewModelScope.launch {
            _message.value = "Connecting to '$name'…"
            try {
                val tools = bridge.testConnection(name)
                _message.value = "'$name' connected — ${tools.size} tool(s) available to the agent"
            } catch (error: Exception) {
                _message.value = "'$name' failed: ${error.message ?: "unreachable"}"
            }
            refresh()
        }
    }

    fun setToolEnabled(serverName: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) {
                configStore.list().firstOrNull { it.name == serverName }
            } ?: return@launch
            val updated = config.toolSettings +
                (toolName to (config.toolSettings[toolName] ?: McpToolSetting()).copy(enabled = enabled))
            try {
                withContext(Dispatchers.IO) { configStore.updateToolSettings(serverName, updated) }
            } catch (error: Exception) {
                _message.value =
                    "Server storage could not be read, so the change was not saved: ${error.message ?: "unknown error"}"
                return@launch
            }
            // Re-register so the planner catalogue drops/picks up the tool at once.
            bridge.refreshServer(serverName)
            refresh()
        }
    }

    fun setToolAlwaysAllow(serverName: String, toolName: String, allow: Boolean) {
        viewModelScope.launch {
            val tools = manager.toolsFor(serverName)
            val actionName = McpNames.assignActionNames(serverName, tools.map { it.name })[toolName]
                ?: McpNames.baseActionName(serverName, toolName)
            settingsRepository.updateConfig { config ->
                config.copy(
                    grantedActions = if (allow) {
                        config.effectiveGrantedActions() + (actionName to System.currentTimeMillis())
                    } else {
                        config.effectiveGrantedActions() - actionName
                    }
                )
            }
            // The bridge observes the grant map and rebuilds the definition's
            // neverAutoApprove flag; refresh() repaints the switch.
            refresh()
        }
    }
}
