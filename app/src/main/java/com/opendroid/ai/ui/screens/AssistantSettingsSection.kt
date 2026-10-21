package com.opendroid.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opendroid.ai.core.assistant.AssistantRoleAction
import com.opendroid.ai.core.assistant.AssistantRoleManager
import com.opendroid.ai.core.assistant.AssistantRolePolicy
import com.opendroid.ai.core.assistant.AssistantRoleStatus
import com.opendroid.ai.ui.theme.AccentGreenButton
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

/**
 * Settings → Default assistant section (C3).
 *
 * Shows the LIVE assistant-role status (re-read on every resume, because
 * the role can change inside the system dialog/settings this section
 * launches) and the one action appropriate to it, per
 * [AssistantRolePolicy]: the RoleManager request dialog when the role can
 * be requested, or a deep link into system settings when it cannot —
 * never a dead button and never a silent failure.
 *
 * Note on expectations, mirrored in the copy below: holding the role gives
 * gesture / power-long-press invocation; privileged paths reserved for the
 * preinstalled system assistant (hotword from any screen, squeeze) are
 * not available to third-party assistants.
 */
@Composable
fun AssistantSettingsSection() {
    val context = LocalContext.current
    val roleManager = remember { AssistantRoleManager(context) }

    var status by remember { mutableStateOf(roleManager.currentStatus()) }

    // The role dialog and the settings screens both return via onResume;
    // re-read there so the label never lies about the current holder.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = roleManager.currentStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val roleRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        status = roleManager.currentStatus()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Assistant,
                    contentDescription = "Default assistant",
                    tint = AccentNeonGreen
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = "Default assistant",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = AssistantRolePolicy.statusLabel(status),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "When OpenDroid is the assistant, long-pressing Home or the " +
                    "power button (device-dependent) opens its voice session — " +
                    "the same agent as chat, with the same approvals.",
                fontSize = 12.sp,
                color = TextSecondary
            )

            when (AssistantRolePolicy.actionFor(status)) {
                AssistantRoleAction.REQUEST_ROLE -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val requestIntent = roleManager.createRequestRoleIntent()
                            if (requestIntent != null) {
                                roleRequestLauncher.launch(requestIntent)
                            } else {
                                // The dialog could not be built after all
                                // (OEM quirk) — settings deep link instead
                                // of a silent no-op.
                                context.startActivity(roleManager.createFallbackSettingsIntent())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreenButton)
                    ) {
                        Text("Make OpenDroid my assistant")
                    }
                }
                AssistantRoleAction.OPEN_SETTINGS -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(roleManager.createFallbackSettingsIntent())
                        }
                    ) {
                        Text("Open system assistant settings", color = AccentNeonGreen)
                    }
                }
                AssistantRoleAction.NONE -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(roleManager.createFallbackSettingsIntent())
                        }
                    ) {
                        Text("Change in system settings", color = AccentNeonGreen)
                    }
                }
            }

            if (status == AssistantRoleStatus.HELD) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Try it: long-press Home or the power button and speak a command.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
