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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.theme.systemReduceMotionEnabled
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

/**
 * Settings → Accessibility section (D8).
 *
 * One toggle today: Reduce motion. It is persisted on the LLM config like
 * the other appearance settings and read by OpenDroidTheme, which ORs it
 * with the system animator-duration scale — so when the user has removed
 * animations system-wide, the app's continuous animations are static even
 * with this switch off, and the note below says so instead of letting the
 * switch look broken.
 */
@Composable
fun AccessibilitySettingsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val config by viewModel.llmConfig.collectAsState()
    val systemReduced = remember(context) { systemReduceMotionEnabled(context) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Accessibility,
                    contentDescription = "Accessibility",
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACCESSIBILITY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Options for using OpenDroid with assistive technology.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reduce motion",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "Stops the pulsing voice orb, thinking dots and voice " +
                            "waveform from animating. Static indicators are shown instead.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = config.reduceMotion,
                    onCheckedChange = { viewModel.updateReduceMotion(it) }
                )
            }

            if (systemReduced) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Animations are off in your system settings, so reduce " +
                        "motion is already active throughout the app.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
