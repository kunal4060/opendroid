package com.opendroid.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.ExternalAutomationViewModel

/**
 * Settings → External automation section (C7).
 *
 * The master toggle (default OFF), the API-token lifecycle, the last
 * external run, and a short in-app copy of the intent contract. The gateway
 * enforces exactly what this screen configures; the full contract lives in
 * `docs/external-intents.md`.
 */
@Composable
fun ExternalAutomationSettingsSection(
    viewModel: ExternalAutomationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsState()
    val tokenHint by viewModel.tokenHint.collectAsState()
    val lastRun by viewModel.lastRun.collectAsState()

    // The one-time plaintext token lives in the ViewModel until dismissed:
    // in remember state, an activity recreation with the token dialog open
    // destroyed the only copy (only its hash persists).
    val newlyGeneratedToken by viewModel.newlyGeneratedToken.collectAsState()
    var confirmRevoke by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "External automation",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "External automation",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Let Tasker, MacroDroid or ADB command OpenDroid via intents.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Every intent must carry your API token — requests " +
                        "without it are rejected and logged. Commands run through " +
                        "the normal agent, with the same approval rules as typed " +
                        "chat, in the \"External commands\" chat. While the app " +
                        "lock is on, external commands are refused.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (tokenHint != null) {
                    Text(
                        text = "API token: ••••$tokenHint",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                    Row {
                        TextButton(onClick = { viewModel.generateToken() }) {
                            Text("Regenerate token")
                        }
                        TextButton(onClick = { confirmRevoke = true }) {
                            Text("Revoke", color = AccentRed)
                        }
                    }
                } else {
                    Text(
                        text = "No API token yet. Generate one and paste it into " +
                            "your Tasker tasks or ADB commands.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    TextButton(onClick = { viewModel.generateToken() }) {
                        Text("Generate token")
                    }
                }

                lastRun?.let { run ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Last external run: ${run.state} — ${run.label}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    run.summary?.let { summary ->
                        Text(
                            text = summary,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Actions: com.opendroid.ai.action.RUN_COMMAND " +
                        "(extra: instruction), .RUN_MACRO (macro_name), " +
                        ".QUERY_STATUS — always with extra auth_token. " +
                        "Full contract: docs/external-intents.md in the repo.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }
        }
    }

    newlyGeneratedToken?.let { token ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissNewlyGeneratedToken() },
            title = { Text("Your API token") },
            text = {
                Column {
                    Text(
                        text = "Copy this now — for your security it is shown " +
                            "only once and cannot be displayed again. " +
                            "Regenerating makes a new one and invalidates this one.",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = token,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OpenDroid API token", token))
                    Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                    viewModel.dismissNewlyGeneratedToken()
                }) {
                    Text("Copy & close")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNewlyGeneratedToken() }) {
                    Text("Close")
                }
            }
        )
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Revoke API token?") },
            text = {
                Text(
                    "All external intents will be rejected until you generate " +
                        "a new token. Tasker tasks and scripts using the old " +
                        "token will stop working."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revokeToken()
                    confirmRevoke = false
                }) {
                    Text("Revoke", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
