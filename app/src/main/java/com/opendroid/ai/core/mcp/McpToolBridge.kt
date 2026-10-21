package com.opendroid.ai.core.mcp

import android.util.Log
import com.opendroid.ai.core.agent.ActionCategory
import com.opendroid.ai.core.agent.ActionDefinition
import com.opendroid.ai.core.agent.DynamicActionRegistry
import com.opendroid.ai.core.service.McpConfigStore
import com.opendroid.ai.core.service.McpEndpointConfig
import com.opendroid.ai.core.service.McpToolSetting
import com.opendroid.ai.data.models.effectiveGrantedActions
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires configured MCP servers into the agent: connects each enabled
 * server, turns its tools into [ActionDefinition]s + [McpToolAction]s in
 * the [DynamicActionRegistry], and keeps that registration truthful —
 * tools appear when a server connects and vanish the moment it errors,
 * is disabled, or is deleted.
 *
 * Approval model (research §1, seam 3): a tool's definition is created
 * with `neverAutoApprove = true` unless the user granted it "always allow"
 * in the server detail UI. Grants live in the same LLMConfig grant map the
 * built-in approval flow uses, so AUTO mode's policy math
 * (`isNeverAutoApprove || not granted → ask`) works unchanged; the chat
 * modal never *offers* a grant for MCP actions (AutoApprovalPolicy
 * .isGrantable stays built-in-only) — granting is a deliberate Settings act.
 *
 * Rug-pull defence: each tool's name+description+schema fingerprint is
 * stored at first sight. If a reconnected server presents a *changed*
 * tool under the same name, its always-allow grant is revoked and the
 * tool returns to approval-gated.
 */
@Singleton
class McpToolBridge @Inject constructor(
    private val configStore: McpConfigStore,
    private val manager: McpClientManager,
    private val settingsRepository: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val knownServers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var grantedActions: Set<String> = emptySet()

    /** Starts the grant observer, the error-withdrawal observer, and the initial connect pass. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settingsRepository.llmConfig.collectLatest { config ->
                val grants = config.effectiveGrantedActions().keys
                val changed = grants != grantedActions
                grantedActions = grants
                if (changed) reregisterConnectedServers()
            }
        }
        scope.launch {
            manager.states.collectLatest { states ->
                states.forEach { (serverName, state) ->
                    if (state is McpServerState.Error) {
                        DynamicActionRegistry.removeForOwner(ownerKey(serverName))
                    }
                }
            }
        }
        scope.launch { refreshAll() }
    }

    /** Connects/refreshes every configured server (startup + Settings "refresh"). */
    suspend fun refreshAll() {
        val configs = configStore.list()
        val configuredNames = configs.map { it.name }.toSet()
        // Withdraw tools of servers that no longer exist in the store —
        // and their always-allow grants, exactly as for an explicit delete.
        knownServers.toList().filter { it !in configuredNames }.forEach { removed ->
            DynamicActionRegistry.removeForOwner(ownerKey(removed))
            manager.disconnect(removed)
            knownServers.remove(removed)
            revokeServerGrants(removed)
        }
        configs.forEach { config ->
            knownServers += config.name
            refreshServer(config.name)
        }
    }

    /**
     * (Re)registers one server from its live config: disabled or missing →
     * tools withdrawn; enabled → connect (cached when healthy) and register
     * the currently-enabled tools.
     */
    suspend fun refreshServer(serverName: String) {
        val config = configStore.list().firstOrNull { it.name == serverName }
        if (config == null) {
            DynamicActionRegistry.removeForOwner(ownerKey(serverName))
            manager.disconnect(serverName)
            knownServers.remove(serverName)
            return
        }
        knownServers += serverName
        if (!config.enabled) {
            DynamicActionRegistry.removeForOwner(ownerKey(serverName))
            manager.onServerDisabled(serverName)
            return
        }
        try {
            val tools = manager.connect(config)
            registerTools(config, tools)
        } catch (error: Exception) {
            Log.w(TAG, "MCP server '$serverName' unavailable: ${error.message}")
            DynamicActionRegistry.removeForOwner(ownerKey(serverName))
        }
    }

    /** Explicit user-driven test: fresh handshake, then register what came back. */
    suspend fun testConnection(serverName: String): List<McpTool> {
        val config = configStore.list().firstOrNull { it.name == serverName }
            ?: throw McpException.Transport("MCP server '$serverName' is no longer configured")
        val tools = manager.reconnect(config)
        registerTools(config, tools)
        return tools
    }

    /**
     * Server deleted in Settings (or renamed — Settings calls this with
     * the old name): withdraw tools, drop the session, and revoke the
     * server's always-allow grants. Without the revocation the grant keys
     * (`MCP_<server>_<tool>`) outlive the server, and a *different* server
     * later added under the same (or same-sanitising) name silently
     * inherits them — its tools would run with no approval prompt for a
     * server the user never granted.
     */
    suspend fun onServerRemoved(serverName: String) {
        DynamicActionRegistry.removeForOwner(ownerKey(serverName))
        manager.disconnect(serverName)
        knownServers.remove(serverName)
        revokeServerGrants(serverName)
    }

    /** Removes every always-allow grant keyed to [serverName]'s tools. */
    private suspend fun revokeServerGrants(serverName: String) {
        val prefix = McpNames.grantKeyPrefix(serverName)
        try {
            settingsRepository.updateConfig { config ->
                val resolved = config.effectiveGrantedActions()
                val kept = resolved.filterKeys { key -> !key.startsWith(prefix) }
                if (kept.size == resolved.size) config
                else config.copy(grantedActions = kept)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not revoke grants for removed MCP server '$serverName': ${error.message}")
        }
    }

    private suspend fun reregisterConnectedServers() {
        configStore.list().filter { it.enabled }.forEach { config ->
            val tools = manager.toolsFor(config.name)
            if (tools.isNotEmpty()) registerTools(config, tools)
        }
    }

    private suspend fun registerTools(config: McpEndpointConfig, tools: List<McpTool>) {
        val actionNames = McpNames.assignActionNames(config.name, tools.map { it.name })
        val entries = LinkedHashMap<String, Pair<ActionDefinition, com.opendroid.ai.actions.base.Action>>()
        var toolSettings = config.toolSettings
        var settingsDirty = false

        for (tool in tools) {
            val actionName = actionNames.getValue(tool.name)
            val fingerprint = McpSchemaMapper.fingerprint(tool)
            val stored = toolSettings[tool.name]
            if (stored == null) {
                // First sight: baseline the fingerprint so later changes are visible.
                toolSettings = toolSettings + (tool.name to McpToolSetting(enabled = true, fingerprint = fingerprint))
                settingsDirty = true
            } else if (stored.fingerprint.isNotBlank() && stored.fingerprint != fingerprint) {
                // The tool changed under the same name: revoke its grant.
                Log.w(TAG, "MCP tool '${tool.name}' on '${config.name}' changed — revoking always-allow grant")
                revokeGrant(actionName)
                toolSettings = toolSettings + (tool.name to stored.copy(fingerprint = fingerprint))
                settingsDirty = true
            }
            val enabled = toolSettings[tool.name]?.enabled ?: true
            if (!enabled) continue

            val definition = ActionDefinition(
                name = actionName,
                description = "[MCP · ${config.name}] ${tool.description}".trim(),
                params = McpSchemaMapper.toParamDefinitions(tool.inputSchema),
                examples = emptyList(),
                category = ActionCategory.ADVANCED,
                isSimple = false,
                neverAutoApprove = actionName !in grantedActions
            )
            entries[actionName] = definition to McpToolAction(actionName, config.name, tool, manager)
        }

        if (settingsDirty) {
            try {
                configStore.updateToolSettings(config.name, toolSettings)
            } catch (error: Exception) {
                Log.w(TAG, "Could not persist MCP tool settings for '${config.name}': ${error.message}")
            }
        }
        DynamicActionRegistry.replaceForOwner(ownerKey(config.name), entries)
    }

    private suspend fun revokeGrant(actionName: String) {
        try {
            settingsRepository.updateConfig { config ->
                config.copy(grantedActions = config.effectiveGrantedActions() - actionName)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not revoke grant for $actionName: ${error.message}")
        }
    }

    private fun ownerKey(serverName: String): String = "mcp:$serverName"

    private companion object {
        const val TAG = "McpToolBridge"
    }
}
