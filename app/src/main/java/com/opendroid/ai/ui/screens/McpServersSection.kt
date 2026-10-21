package com.opendroid.ai.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.mcp.McpServerState
import com.opendroid.ai.core.mcp.McpTransportKind
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.McpServerDraft
import com.opendroid.ai.ui.viewmodel.McpServerUi
import com.opendroid.ai.ui.viewmodel.McpServersViewModel

/**
 * Settings → MCP Servers (feature D5).
 *
 * The client side of MCP: servers added here are connected for real
 * (initialize handshake + tools/list), and their tools join the agent's
 * action set as approval-gated actions. Each row shows the live
 * connection state from the client manager — not just "URL saved".
 * Credentials travel as headers and are stored only in the Keystore-backed
 * config store. stdio servers can't run on a phone; the add dialog says so
 * and points at HTTP exposure instead of pretending otherwise.
 */
@Composable
fun McpServersSection(viewModel: McpServersViewModel = hiltViewModel()) {
    val servers by viewModel.servers.collectAsState()
    val message by viewModel.message.collectAsState()

    var editorDraft by remember { mutableStateOf<McpServerDraft?>(null) }
    var editorOriginalName by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var expandedServer by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = "MCP servers",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MCP Servers",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Connect external Model Context Protocol servers; their tools become agent actions.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh MCP servers",
                        tint = TextSecondary
                    )
                }
            }

            message?.let { text ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.dismissMessage() }) { Text("OK") }
                }
            }

            if (servers.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No MCP servers yet. Add one to give the agent new tools.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            servers.forEach { server ->
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = BorderColor)
                Spacer(modifier = Modifier.height(12.dp))
                McpServerRow(
                    server = server,
                    expanded = expandedServer == server.config.name,
                    onToggleExpanded = {
                        expandedServer =
                            if (expandedServer == server.config.name) null else server.config.name
                    },
                    onEnabledChange = { enabled ->
                        viewModel.setServerEnabled(server.config.name, enabled)
                    },
                    onTest = { viewModel.testConnection(server.config.name) },
                    onEdit = {
                        editorOriginalName = server.config.name
                        editorDraft = McpServerDraft.from(server.config)
                    },
                    onDelete = { pendingDelete = server.config.name },
                    onToolEnabledChange = { toolName, enabled ->
                        viewModel.setToolEnabled(server.config.name, toolName, enabled)
                    },
                    onToolAlwaysAllowChange = { toolName, allow ->
                        viewModel.setToolAlwaysAllow(server.config.name, toolName, allow)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    editorOriginalName = null
                    editorDraft = McpServerDraft()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add server")
            }
        }
    }

    editorDraft?.let { draft ->
        McpServerEditorDialog(
            draft = draft,
            isEdit = editorOriginalName != null,
            onDismiss = { editorDraft = null },
            onSave = { updated ->
                viewModel.saveServer(editorOriginalName, updated)
                editorDraft = null
            }
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove MCP server") },
            text = {
                Text(
                    "Remove '$name'? Its tools leave the agent's set immediately. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteServer(name)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServerUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToolEnabledChange: (String, Boolean) -> Unit,
    onToolAlwaysAllowChange: (String, Boolean) -> Unit
) {
    val config = server.config
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = config.url,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (stateText, stateColor) = when (val state = server.state) {
                McpServerState.Disabled -> "Disabled" to TextSecondary
                McpServerState.Disconnected -> "Not connected" to TextSecondary
                McpServerState.Connecting -> "Connecting…" to TextPrimary
                is McpServerState.Connected ->
                    "Connected · ${state.toolCount} tool(s)" +
                        (state.serverInfo?.let { " · $it" } ?: "") to AppTheme.colors.success
                is McpServerState.Error -> "Error: ${state.message}" to AccentRed
            }
            if (server.state == McpServerState.Connecting) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(text = stateText, fontSize = 12.sp, color = stateColor, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide tools" else "Show tools",
                    tint = TextSecondary
                )
            }
        }

        Row {
            TextButton(onClick = onTest, enabled = config.enabled) { Text("Test connection") }
            IconButton(onClick = onEdit) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit server", tint = TextSecondary)
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove server", tint = AccentRed)
            }
        }

        if (expanded) {
            if (server.tools.isEmpty()) {
                Text(
                    text = if (server.state is McpServerState.Connected) {
                        "This server reported no tools."
                    } else {
                        "Connect to see this server's tools."
                    },
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            } else {
                server.tools.forEach { toolUi ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = toolUi.tool.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = toolUi.actionName,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                        if (toolUi.tool.description.isNotBlank()) {
                            Text(
                                text = toolUi.tool.description,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 3
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Available to agent",
                                fontSize = 12.sp,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = toolUi.enabled,
                                onCheckedChange = { onToolEnabledChange(toolUi.tool.name, it) }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Always allow (skip approval)",
                                fontSize = 12.sp,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = toolUi.alwaysAllow,
                                onCheckedChange = { onToolAlwaysAllowChange(toolUi.tool.name, it) },
                                enabled = toolUi.enabled
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerEditorDialog(
    draft: McpServerDraft,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (McpServerDraft) -> Unit
) {
    var current by remember(draft) { mutableStateOf(draft) }
    val error = current.validationError()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit MCP server" else "Add MCP server") },
        text = {
            Column {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { current = current.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = current.url,
                    onValueChange = { current = current.copy(url = it) },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://mcp.example.com/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Transport", fontSize = 12.sp, color = TextSecondary)
                McpTransportKind.values().forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = current.transport == kind,
                            onClick = { current = current.copy(transport = kind) }
                        )
                        Text(
                            text = when (kind) {
                                McpTransportKind.AUTO -> "Auto (Streamable HTTP, legacy fallback)"
                                McpTransportKind.STREAMABLE -> "Streamable HTTP"
                                McpTransportKind.LEGACY_SSE -> "Legacy HTTP + SSE"
                            },
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = current.headersText,
                    onValueChange = { current = current.copy(headersText = it) },
                    label = { Text("Headers (one per line, Key: Value)") },
                    placeholder = { Text("Authorization: Bearer …") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Allow insecure local server (plain HTTP)",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = current.allowInsecureLocal,
                        onCheckedChange = { current = current.copy(allowInsecureLocal = it) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Enabled",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = current.enabled,
                        onCheckedChange = { current = current.copy(enabled = it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Remote servers only: stdio servers (npx / uvx commands) cannot run on a " +
                        "phone. Expose them over HTTP on your computer (e.g. with mcp-proxy) and " +
                        "add that URL here. Headers are stored encrypted on this device. " +
                        "Plain HTTP works only for localhost-class addresses unless your server " +
                        "uses HTTPS.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, fontSize = 12.sp, color = AccentRed)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(current) }, enabled = error == null) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
