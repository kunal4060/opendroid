package com.opendroid.ai.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.voice.kws.WakeEngineKind
import com.opendroid.ai.core.voice.kws.WakeKeywordFactory
import com.opendroid.ai.core.voice.kws.WakeWordEngineState
import com.opendroid.ai.core.voice.kws.WakeWordStatus
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

/**
 * Settings → Wake word section (D1).
 *
 * Controls the true offline keyword spotter (sherpa-onnx): engine choice
 * (offline vs the legacy system recogniser), the wake phrase, and the
 * sensitivity that maps to the spotter's detection threshold. The status
 * line is the live [WakeWordStatus] published by the running detector —
 * including the honest error state when the offline engine cannot start
 * (it never silently substitutes the legacy recogniser).
 */
@Composable
fun WakeWordSettingsSection(viewModel: SettingsViewModel) {
    val config by viewModel.llmConfig.collectAsState()
    val status by WakeWordStatus.info.collectAsState()
    val engineKind = WakeEngineKind.fromSettingsId(config.wakeWordEngine)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Wake Word",
                    tint = AccentNeonGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WAKE WORD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentNeonGreen
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Say the wake phrase to start a voice command, hands-free. " +
                            "Listening only runs while the floating button is off.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Engine status — live from the running detector.
            val (statusText, statusColor) = when (val state = status.state) {
                WakeWordEngineState.Idle -> "Not started" to TextSecondary
                WakeWordEngineState.Starting -> "Starting…" to TextSecondary
                WakeWordEngineState.Listening ->
                    "Listening for \"${config.wakeWordKeyword.trim().ifEmpty { WakeKeywordFactory.DEFAULT_PHRASE }}\"" to AccentNeonGreen
                WakeWordEngineState.Stopped -> "Stopped" to TextSecondary
                is WakeWordEngineState.Error -> state.message to AccentRed
            }
            Text(
                text = "STATUS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = statusText, fontSize = 13.sp, color = statusColor)
            status.detail?.let { detail ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = detail, fontSize = 12.sp, color = TextSecondary)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ENGINE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            WakeEngineOption(
                title = "Offline (on-device)",
                description = "Keyword spotting runs entirely on this phone — works in " +
                    "airplane mode and no audio ever leaves the device.",
                selected = engineKind == WakeEngineKind.OFFLINE_SHERPA,
                onSelect = { viewModel.updateWakeWordEngine(WakeEngineKind.OFFLINE_SHERPA.settingsId) }
            )
            WakeEngineOption(
                title = "Legacy (system speech)",
                description = "The pre-D1 behaviour: the system speech recogniser listens " +
                    "for the phrase. On most devices it needs the network.",
                selected = engineKind == WakeEngineKind.LEGACY_SPEECH,
                onSelect = { viewModel.updateWakeWordEngine(WakeEngineKind.LEGACY_SPEECH.settingsId) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = config.wakeWordKeyword,
                onValueChange = { viewModel.updateWakeWordKeyword(it) },
                label = { Text("Wake phrase", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "An English phrase, e.g. \"Open Droid\" or \"Hey Open Droid\". If it uses " +
                    "words the offline model doesn't know, the status above says so and the " +
                    "default phrase stays active.",
                fontSize = 12.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sensitivity",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(config.wakeWordSensitivity * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }
            Slider(
                value = config.wakeWordSensitivity,
                onValueChange = { viewModel.updateWakeWordSensitivity(it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = TextPrimary,
                    activeTrackColor = TextPrimary.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Higher wakes more easily but may false-trigger from TV or nearby " +
                    "conversation. Always-on listening runs in the foreground service and " +
                    "uses the CPU continuously, so expect some battery use — the persistent " +
                    "notification is its indicator.",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun WakeEngineOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(text = description, fontSize = 12.sp, color = TextSecondary)
        }
    }
}
