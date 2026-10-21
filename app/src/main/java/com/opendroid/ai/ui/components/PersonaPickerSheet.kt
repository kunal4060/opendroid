package com.opendroid.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.Persona
import com.opendroid.ai.data.models.PersonaKind
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.JetBrainsMonoFamily
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkSurface
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

/**
 * Header chip for the chat's persona (feature B6), sitting next to the A2
 * model chip. Shows the bound persona's name, or "Persona" while the chat
 * follows the default prompt; tapping opens [PersonaPickerSheet].
 */
@Composable
fun PersonaChip(
    activePersona: Persona?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CardBackground,
        border = BorderStroke(1.dp, BorderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = null,
                tint = if (activePersona != null) AccentNeonGreen else TextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = activePersona?.name ?: "Persona",
                fontSize = 11.sp,
                fontFamily = JetBrainsMonoFamily,
                color = TextPrimary,
                maxLines = 1
            )
            if (activePersona != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CHAT",
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = AccentNeonGreen
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Choose persona for this chat",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Bottom sheet behind [PersonaChip] (feature B6). Two sections:
 * - Personas: picking one binds it to THIS chat only (persisted per session);
 *   "Default" clears the binding.
 * - Quick commands: picking one inserts its text into the composer — it is
 *   never auto-sent and never bound.
 *
 * [personas] is the whole library (both kinds), already in library order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaPickerSheet(
    personas: List<Persona>,
    activePersonaId: String?,
    onSelectPersona: (Persona) -> Unit,
    onClearPersona: () -> Unit,
    onPickQuickCommand: (Persona) -> Unit,
    onManagePersonas: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val personaEntries = personas.filter { it.kind == PersonaKind.PERSONA }
    val quickCommands = personas.filter { it.kind == PersonaKind.QUICK_COMMAND }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "Persona for this chat",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "A persona shapes this chat's AI replies and planning. " +
                    "Other chats are not affected, and built-in shortcuts " +
                    "(alarms, device toggles) never use a persona.",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                item(key = "persona-default") {
                    PersonaPickerRow(
                        title = "Default",
                        subtitle = "No persona — the standard OpenDroid prompt",
                        isSelected = activePersonaId == null,
                        onClick = onClearPersona
                    )
                    HorizontalDivider(color = BorderColor)
                }
                items(personaEntries, key = { "persona-${it.id}" }) { persona ->
                    PersonaPickerRow(
                        title = persona.name,
                        subtitle = persona.prompt,
                        isSelected = persona.id == activePersonaId,
                        onClick = { onSelectPersona(persona) }
                    )
                    HorizontalDivider(color = BorderColor)
                }

                if (quickCommands.isNotEmpty()) {
                    item(key = "quick-commands-header") {
                        Text(
                            text = "QUICK COMMANDS",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                        Text(
                            text = "Tap one to insert it into the message box — nothing is sent until you send it.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(quickCommands, key = { "command-${it.id}" }) { command ->
                        PersonaPickerRow(
                            title = command.name,
                            subtitle = command.prompt,
                            isSelected = false,
                            onClick = { onPickQuickCommand(command) }
                        )
                        HorizontalDivider(color = BorderColor)
                    }
                }
            }

            OutlinedButton(
                onClick = onManagePersonas,
                border = BorderStroke(1.dp, BorderColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Manage personas & commands", fontSize = 12.sp)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun PersonaPickerRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 2
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected for this chat",
                tint = AccentNeonGreen,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
