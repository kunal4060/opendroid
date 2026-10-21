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
import androidx.compose.ui.unit.sp
import com.opendroid.ai.social.domain.model.*
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.JetBrainsMonoFamily
import com.opendroid.ai.ui.theme.MonoMedium
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialSettingsTab(
    currentLevel: AutomationLevel,
    rules: List<SocialAutomationRule>,
    auditLogs: List<SocialAuditEntry>,
    onSelectLevel: (AutomationLevel) -> Unit,
    onSaveRule: (SocialAutomationRule) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    val theme = AppTheme.colors
    var showAddRuleDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. AUTOMATION LEVEL SELECTOR ─────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Automation Guardrails", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text("Configure autonomy boundaries and approval workflows.", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AutomationLevelCard(
                    level = AutomationLevel.SAFE,
                    title = "Safe Mode (Draft Only)",
                    subtitle = "AI creates drafts only. Every post, schedule, and reply requires explicit user confirmation.",
                    isSelected = currentLevel == AutomationLevel.SAFE,
                    onSelect = { onSelectLevel(AutomationLevel.SAFE) }
                )

                AutomationLevelCard(
                    level = AutomationLevel.APPROVAL,
                    title = "Approval Required (Balanced)",
                    subtitle = "Low-risk comments and routine summaries run autonomously. All new posts and sensitive replies require your approval.",
                    isSelected = currentLevel == AutomationLevel.APPROVAL,
                    onSelect = { onSelectLevel(AutomationLevel.APPROVAL) }
                )

                AutomationLevelCard(
                    level = AutomationLevel.AUTONOMOUS,
                    title = "Autonomous Mode (Full Agent)",
                    subtitle = "Approved campaigns, scheduled queue, and high-confidence comment replies publish automatically based on rules.",
                    isSelected = currentLevel == AutomationLevel.AUTONOMOUS,
                    onSelect = { onSelectLevel(AutomationLevel.AUTONOMOUS) }
                )
            }
        }

        // ── 2. AUTOMATION RULES ENGINE ───────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Custom Automation Rules", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("Custom trigger criteria and confidence gates", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = { showAddRuleDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentGreenButton, contentColor = theme.onAccent),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = theme.onAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule", color = theme.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("No custom rules defined yet. Default guardrails are active.", color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                    onDelete = { onDeleteRule(rule.id) }
                )
            }
        }

        // ── 3. KEYSTORE ENCRYPTION & AUDIT LOG ───────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Security & Audit Trail", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text("Hardware-isolated cryptographic logs and agent activity", color = theme.textTertiary, style = MaterialTheme.typography.labelMedium)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(theme.accentContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = theme.onAccentContainer, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Android KeyStore Protected", color = theme.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("AES-256-GCM AEAD encrypted. Strict zero-plaintext policy.", color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (auditLogs.isEmpty()) {
            item {
                Text("No recent audit log entries.", color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(auditLogs.take(10), key = { it.id }) { log ->
                AuditLogItem(log = log)
            }
        }
    }

    // ── ADD RULE DIALOG ──────────────────────────────────────────────
    if (showAddRuleDialog) {
        AddRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onConfirm = { rule ->
                onSaveRule(rule)
                showAddRuleDialog = false
            }
        )
    }
}

@Composable
private fun AutomationLevelCard(
    level: AutomationLevel,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val theme = AppTheme.colors

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(
                1.dp,
                if (isSelected) theme.accentNeonGreen else theme.borderColor,
                RoundedCornerShape(18.dp)
            )
            .clickable { onSelect() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) theme.accentContainer else theme.cardBackground
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = theme.accentNeonGreen, unselectedColor = theme.textTertiary)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = if (isSelected) theme.onAccentContainer else theme.textPrimary, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(3.dp))
                Text(subtitle, color = theme.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: SocialAutomationRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val theme = AppTheme.colors

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(rule.name, color = theme.textPrimary, style = MaterialTheme.typography.titleSmall)

                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = theme.onAccent,
                        checkedTrackColor = theme.accentGreenButton,
                        uncheckedThumbColor = theme.textSecondary,
                        uncheckedTrackColor = theme.surfaceSunken
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Trigger: ${rule.triggerType} • Action: ${rule.actionType}",
                color = theme.textTertiary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFamily
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Confidence Threshold: ${(rule.confidenceThreshold * 100).toInt()}%",
                color = theme.accentNeonGreen,
                style = MonoMedium
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Delete Rule",
                    color = theme.accentRed,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onDelete() }
                )
            }
        }
    }
}

@Composable
private fun AuditLogItem(log: SocialAuditEntry) {
    val theme = AppTheme.colors
    val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(6.dp).clip(CircleShape)
                    .background(if (log.status == "SUCCESS") theme.success else theme.accentRed)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(log.action, color = theme.textPrimary, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    log.platform?.let { p ->
                        Text(" • ${p.displayName}", color = theme.accentInfo, fontFamily = JetBrainsMonoFamily, fontSize = 10.sp)
                    }
                }
                Text(log.details, color = theme.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }

            Text(timeFormat.format(Date(log.timestamp)), color = theme.textTertiary, style = MonoMedium)
        }
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (SocialAutomationRule) -> Unit
) {
    val theme = AppTheme.colors
    var ruleName by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf("SENTIMENT_POSITIVE") }
    var selectedAction by remember { mutableStateOf("AUTO_REPLY") }
    var confidence by remember { mutableStateOf(0.85f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceRaised,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add Automation Rule", color = theme.textPrimary, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("Rule Name", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Auto reply to positive comments", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.textPrimary,
                        unfocusedTextColor = theme.textPrimary,
                        focusedBorderColor = theme.accentNeonGreen,
                        unfocusedBorderColor = theme.borderColor,
                        focusedContainerColor = theme.surface,
                        unfocusedContainerColor = theme.surface
                    )
                )

                Text("Confidence Threshold: ${(confidence * 100).toInt()}%", color = theme.textPrimary, style = MonoMedium)
                Slider(
                    value = confidence,
                    onValueChange = { confidence = it },
                    valueRange = 0.5f..0.99f,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.accentNeonGreen,
                        activeTrackColor = theme.accentGreenButton,
                        inactiveTrackColor = theme.surfaceSunken
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ruleName.isNotBlank()) {
                        val rule = SocialAutomationRule(
                            id = UUID.randomUUID().toString(),
                            name = ruleName,
                            isEnabled = true,
                            platform = null,
                            triggerType = selectedTrigger,
                            keywords = emptyList(),
                            actionType = selectedAction,
                            replyTemplate = null,
                            confidenceThreshold = confidence,
                            requireHumanApproval = false
                        )
                        onConfirm(rule)
                    }
                },
                enabled = ruleName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = theme.accentGreenButton, contentColor = theme.onAccent)
            ) {
                Text("Save Rule", color = theme.onAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textSecondary)
            }
        }
    )
}
