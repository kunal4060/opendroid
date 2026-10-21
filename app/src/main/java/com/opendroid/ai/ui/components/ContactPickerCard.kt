package com.opendroid.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.agent.maskPhone
import com.opendroid.ai.ui.theme.*

/**
 * Contact picker card shown when multiple contacts match a query.
 * Displays up to 5 options with numbered badges and masked phone numbers.
 *
 * [enabled] is true only while this card is the LIVE picker (audit-2 #10):
 * historical cards render inert — no clickable rows — so a stale tap can
 * never be sent as a bare index answer to an unrelated later prompt.
 */
@Composable
fun ContactPickerCard(
    query: String,
    matches: List<Map<String, String>>,
    onContactSelected: (Map<String, String>) -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Which '$query' do you mean?",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Contact options
            matches.forEachIndexed { index, contact ->
                ContactOptionRow(
                    number = index + 1,
                    name = contact["name"] ?: "",
                    phone = maskPhone(contact["phone"] ?: ""),
                    type = contact["type"] ?: "Mobile",
                    onClick = { onContactSelected(contact) },
                    enabled = enabled
                )
                if (index < matches.size - 1) {
                    HorizontalDivider(
                        color = AppTheme.colors.borderSubtle,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Hint
            Text(
                text = if (enabled) {
                    "Tap to select, or type the number (1, 2, 3...)"
                } else {
                    "This selection is no longer active."
                },
                color = AppTheme.colors.textTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun ContactOptionRow(
    number: Int,
    name: String,
    phone: String,
    type: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Number badge — the accent selection family: accentContainer
        // fill with onAccentContainer content; the number itself is data,
        // so it takes the mono instrument voice.
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    AppTheme.colors.accentContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$number",
                color = AppTheme.colors.onAccentContainer,
                style = MonoMedium
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Text(
                text = "$type • $phone",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = AppTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}
