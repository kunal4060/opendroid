package com.opendroid.ai.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opendroid.ai.social.domain.model.*
import com.opendroid.ai.ui.components.StatusBadge
import com.opendroid.ai.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialAccountsTab(
    accounts: List<SocialAccount>,
    onConnectAccount: (SocialPlatform, SocialCredentials, List<SocialPermission>) -> Unit,
    onDisconnectAccount: (String, SocialPlatform) -> Unit,
    onRevokeAccount: (String, SocialPlatform) -> Unit,
    onRefresh: () -> Unit
) {
    val theme = AppTheme.colors
    var connectingPlatform by remember { mutableStateOf<SocialPlatform?>(null) }
    var revokingAccount by remember { mutableStateOf<SocialAccount?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── HEADER & SECURITY NOTICE ─────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(38.dp).clip(CircleShape).background(theme.accentContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = theme.accentNeonGreen, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Hardware-Encrypted Credentials",
                            color = theme.textPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "All API tokens are stored in Android KeyStore (AES-256-GCM). Tokens are never logged or stored in plaintext.",
                            color = theme.textSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // ── PLATFORM CARDS ───────────────────────────────────────────
        item {
            Text(
                "Connected Platforms",
                color = theme.textPrimary,
                style = MaterialTheme.typography.titleSmall
            )
        }

        items(SocialPlatform.values()) { platform ->
            val account = accounts.find { it.platform == platform }
            AccountCard(
                platform = platform,
                account = account,
                onConnect = { connectingPlatform = platform },
                onDisconnect = { account?.let { onDisconnectAccount(it.id, it.platform) } },
                onRevoke = { account?.let { revokingAccount = it } }
            )
        }
    }

    // ── CONNECT DIALOG ───────────────────────────────────────────────
    connectingPlatform?.let { platform ->
        ConnectAccountDialog(
            platform = platform,
            onDismiss = { connectingPlatform = null },
            onConfirm = { credentials, permissions ->
                onConnectAccount(platform, credentials, permissions)
                connectingPlatform = null
            }
        )
    }

    // ── REVOKE CONFIRMATION DIALOG ───────────────────────────────────
    revokingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { revokingAccount = null },
            containerColor = theme.surfaceRaised,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text("Revoke Access to ${account.platform.displayName}?", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "This will delete all hardware-encrypted cryptographic keys and credentials from Android KeyStore. Any active automated tasks for this platform will stop immediately.",
                    color = theme.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeAccount(account.id, account.platform)
                        revokingAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentRed)
                ) {
                    Text("Revoke & Purge", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { revokingAccount = null }) {
                    Text("Cancel", color = theme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun AccountCard(
    platform: SocialPlatform,
    account: SocialAccount?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRevoke: () -> Unit
) {
    val theme = AppTheme.colors
    val isConnected = account?.status == AccountStatus.CONNECTED

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Platform Initial Badge
                Box(
                    modifier = Modifier.size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isConnected) theme.accentContainer else theme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        platform.displayName.take(2).uppercase(),
                        color = if (isConnected) theme.onAccentContainer else theme.textSecondary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            platform.displayName,
                            color = theme.textPrimary,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val status = account?.status ?: AccountStatus.DISCONNECTED
                        val (badgeLabel, badgeColor) = when (status) {
                            AccountStatus.CONNECTED -> "Connected" to theme.success
                            AccountStatus.DISCONNECTED -> "Offline" to theme.textSecondary
                            AccountStatus.EXPIRED -> "Expired" to theme.accentOrange
                            AccountStatus.REAUTH_REQUIRED -> "Reauth Needed" to theme.accentOrange
                            AccountStatus.PERMISSION_DENIED -> "Denied" to theme.accentRed
                            AccountStatus.ERROR -> "Error" to theme.accentRed
                        }
                        StatusBadge(label = badgeLabel, color = badgeColor)
                    }

                    if (isConnected && account != null) {
                        Text(
                            if (account.username.isNotBlank()) "@${account.username}" else account.displayName,
                            color = theme.textSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "Not connected",
                            color = theme.textSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentGreenButton, contentColor = theme.onAccent),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Connect", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isConnected && account != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = theme.borderSubtle, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Permissions list
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Permissions:", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
                    account.permissions.forEach { perm ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.surface)
                                .border(0.5.dp, theme.borderColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(perm.title, color = theme.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stats & Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val lastSync = account.lastSyncAt
                    val syncText = if (lastSync != null && lastSync > 0) {
                        "Synced " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSync))
                    } else {
                        "Not synced yet"
                    }
                    Text(syncText, color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Disconnect", color = theme.textSecondary, style = MaterialTheme.typography.labelMedium)
                        }

                        TextButton(
                            onClick = onRevoke,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Revoke", color = theme.accentRed, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectAccountDialog(
    platform: SocialPlatform,
    onDismiss: () -> Unit,
    onConfirm: (SocialCredentials, List<SocialPermission>) -> Unit
) {
    val theme = AppTheme.colors

    var tokenOrKey by remember { mutableStateOf("") }
    var channelOrChatId by remember { mutableStateOf("") }
    var useSandboxMock by remember { mutableStateOf(false) }

    val selectedPermissions = remember {
        mutableStateListOf(
            SocialPermission.READ_POSTS,
            SocialPermission.PUBLISH_POSTS,
            SocialPermission.READ_COMMENTS,
            SocialPermission.REPLY_TO_COMMENTS,
            SocialPermission.READ_ANALYTICS
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceRaised,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text("Connect ${platform.displayName}", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Credentials will be encrypted with AES-256-GCM hardware keys.",
                    color = theme.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )

                // Sandbox toggle
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { useSandboxMock = !useSandboxMock },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useSandboxMock,
                        onCheckedChange = { useSandboxMock = it },
                        colors = CheckboxDefaults.colors(checkedColor = theme.accentNeonGreen, uncheckedColor = theme.textSecondary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Use Sandbox Mock Mode", color = theme.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Simulates live API without external accounts", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (!useSandboxMock) {
                    val label1 = when (platform) {
                        SocialPlatform.TELEGRAM -> "Bot Token"
                        SocialPlatform.DISCORD -> "Bot Token or Webhook URL"
                        SocialPlatform.X -> "Bearer Token / API Key"
                        SocialPlatform.LINKEDIN -> "OAuth Access Token"
                        SocialPlatform.INSTAGRAM -> "Graph API Access Token"
                        SocialPlatform.FACEBOOK -> "Page Access Token"
                        SocialPlatform.YOUTUBE -> "OAuth Token / API Key"
                    }

                    OutlinedTextField(
                        value = tokenOrKey,
                        onValueChange = { tokenOrKey = it },
                        label = { Text(label1, style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary,
                            focusedBorderColor = theme.accentNeonGreen,
                            unfocusedBorderColor = theme.borderStrong,
                            focusedLabelColor = theme.accentNeonGreen,
                            unfocusedLabelColor = theme.textSecondary
                        )
                    )

                    if (platform == SocialPlatform.TELEGRAM || platform == SocialPlatform.DISCORD) {
                        val label2 = if (platform == SocialPlatform.TELEGRAM) "Chat ID / Channel ID" else "Channel ID (optional)"
                        OutlinedTextField(
                            value = channelOrChatId,
                            onValueChange = { channelOrChatId = it },
                            label = { Text(label2, style = MaterialTheme.typography.bodyMedium) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = theme.textPrimary,
                                unfocusedTextColor = theme.textPrimary,
                                focusedBorderColor = theme.accentNeonGreen,
                                unfocusedBorderColor = theme.borderStrong,
                                focusedLabelColor = theme.accentNeonGreen,
                                unfocusedLabelColor = theme.textSecondary
                            )
                        )
                    }
                }

                Text("Granted Permissions:", color = theme.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(SocialPermission.PUBLISH_POSTS, SocialPermission.REPLY_TO_COMMENTS, SocialPermission.READ_ANALYTICS).forEach { perm ->
                        val isChecked = selectedPermissions.contains(perm)
                        FilterChip(
                            selected = isChecked,
                            onClick = {
                                if (isChecked) selectedPermissions.remove(perm) else selectedPermissions.add(perm)
                            },
                            label = { Text(perm.title, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.accentContainer,
                                selectedLabelColor = theme.onAccentContainer,
                                containerColor = theme.cardBackground,
                                labelColor = theme.textSecondary
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val creds = if (useSandboxMock) {
                        SocialCredentials(
                            accountId = "mock_${platform.id}_acc",
                            platform = platform,
                            accessToken = "mock_${platform.id}_token"
                        )
                    } else {
                        SocialCredentials(
                            accountId = "acc_${platform.id}",
                            platform = platform,
                            accessToken = tokenOrKey,
                            botToken = if (platform == SocialPlatform.TELEGRAM || platform == SocialPlatform.DISCORD) tokenOrKey else null,
                            channelOrTargetId = if (channelOrChatId.isNotBlank()) channelOrChatId else null
                        )
                    }
                    onConfirm(creds, selectedPermissions.toList())
                },
                enabled = useSandboxMock || tokenOrKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = theme.accentGreenButton, contentColor = theme.onAccent)
            ) {
                Text("Authorize & Connect", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textSecondary)
            }
        }
    )
}
