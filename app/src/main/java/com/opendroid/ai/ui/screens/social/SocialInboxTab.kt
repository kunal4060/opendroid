package com.opendroid.ai.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.opendroid.ai.ui.components.PriorityBadge
import com.opendroid.ai.ui.components.StatusBadge
import com.opendroid.ai.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialInboxTab(
    interactions: List<SocialInteraction>,
    onResolve: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onReply: (String, String) -> Unit,
    onRefresh: () -> Unit
) {
    val theme = AppTheme.colors
    var selectedCategory by remember { mutableStateOf<InteractionCategory?>(null) }
    var replyingInteraction by remember { mutableStateOf<SocialInteraction?>(null) }

    val filteredInteractions = remember(interactions, selectedCategory) {
        if (selectedCategory == null) {
            interactions
        } else {
            interactions.filter { it.category == selectedCategory }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── CATEGORY FILTER ROW ──────────────────────────────────────
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All (${interactions.size})", style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = theme.accentContainer,
                            selectedLabelColor = theme.onAccentContainer,
                            containerColor = theme.cardBackground,
                            labelColor = theme.textSecondary
                        )
                    )
                }

                items(InteractionCategory.values()) { cat ->
                    val count = interactions.count { it.category == cat }
                    if (count > 0) {
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.name.replace('_', ' ')} ($count)", style = MaterialTheme.typography.labelMedium) },
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
        }

        // ── INTERACTIONS LIST ────────────────────────────────────────
        if (filteredInteractions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inbox,
                            contentDescription = null,
                            tint = theme.textSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Inbox is Clean",
                            color = theme.textPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "No pending notifications, mentions, or messages.",
                            color = theme.textSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            items(filteredInteractions, key = { it.id }) { item ->
                InteractionCard(
                    interaction = item,
                    onReplyClick = { replyingInteraction = item },
                    onResolveClick = { onResolve(item.id) },
                    onDismissClick = { onDismiss(item.id) }
                )
            }
        }
    }

    // ── REPLY MODAL ──────────────────────────────────────────────────
    replyingInteraction?.let { interaction ->
        var replyText by remember { mutableStateOf(interaction.suggestedAction ?: "") }

        AlertDialog(
            onDismissRequest = { replyingInteraction = null },
            containerColor = theme.surfaceRaised,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    "Reply on ${interaction.platform.displayName}",
                    color = theme.textPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "${interaction.authorName}: \"${interaction.content}\"",
                        color = theme.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3
                    )

                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text("Your Reply", style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
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
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReply(interaction.id, replyText)
                        replyingInteraction = null
                    },
                    enabled = replyText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentGreenButton, contentColor = theme.onAccent)
                ) {
                    Text("Send Reply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { replyingInteraction = null }) {
                    Text("Cancel", color = theme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun InteractionCard(
    interaction: SocialInteraction,
    onReplyClick: () -> Unit,
    onResolveClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    val theme = AppTheme.colors
    val timeFormatted = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(interaction.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Platform, Author, Timestamp, Priority
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(theme.surface)
                        .border(0.5.dp, theme.borderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        interaction.platform.displayName.take(1),
                        color = theme.accentNeonGreen,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            interaction.authorName.ifBlank { "User" },
                            color = theme.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val (priorityLabel, priorityColor) = when (interaction.priority) {
                            InteractionPriority.URGENT -> "Urgent" to theme.accentRed
                            InteractionPriority.HIGH -> "High" to theme.accentOrange
                            InteractionPriority.NORMAL -> "Normal" to theme.accentInfo
                            InteractionPriority.LOW -> "Low" to theme.textSecondary
                        }
                        PriorityBadge(label = priorityLabel, color = priorityColor)
                    }
                    Text(
                        "${interaction.category.name} • $timeFormatted",
                        color = theme.textTertiary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                val (statusLabel, statusColor) = when (interaction.status.uppercase()) {
                    "UNREAD" -> "Unread" to theme.accentInfo
                    "RESOLVED" -> "Resolved" to theme.textSecondary
                    "REPLIED" -> "Replied" to theme.success
                    else -> interaction.status to theme.textSecondary
                }
                StatusBadge(label = statusLabel, color = statusColor)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body content
            Text(
                interaction.content,
                color = theme.textPrimary,
                style = MaterialTheme.typography.bodyMedium
            )

            // AI Suggestion pill if present
            if (!interaction.suggestedAction.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.surface)
                        .border(0.5.dp, theme.borderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = theme.accentNeonGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Suggested: \"${interaction.suggestedAction}\"",
                            color = theme.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismissClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Dismiss", color = theme.textSecondary, style = MaterialTheme.typography.labelMedium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onResolveClick,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, theme.borderColor)
                    ) {
                        Text("Resolve", color = theme.textPrimary, style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = onReplyClick,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentGreenButton, contentColor = theme.onAccent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Reply", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

