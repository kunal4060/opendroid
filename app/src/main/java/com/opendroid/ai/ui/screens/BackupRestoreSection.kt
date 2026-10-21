package com.opendroid.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.backup.BackupCrypto
import com.opendroid.ai.data.backup.BackupSummary
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.BackupRestoreUiState
import com.opendroid.ai.ui.viewmodel.BackupRestoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Backup & Restore (feature B2).
 *
 * Export asks for a backup password first (with an explicit opt-in before
 * any API keys are included), then hands the destination choice to the
 * system file picker — the file is AES-256-GCM encrypted with a
 * PBKDF2-derived key, never plaintext. Restore picks a file, asks for its
 * password, validates the whole backup *before* touching live data, shows
 * exactly what the backup holds, and only replaces the current data after
 * a second, explicit confirmation.
 */
@Composable
fun BackupRestoreSection(viewModel: BackupRestoreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val working = state as? BackupRestoreUiState.Working

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    // Passphrase/options captured by a dialog, consumed by the SAF result.
    var pendingExport by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingImportPassword by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            viewModel.exportTo(uri, pending.first, pending.second)
        }
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val password = pendingImportPassword
        pendingImportPassword = null
        if (uri != null && password != null) {
            viewModel.importFrom(uri, password)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Backup,
                    contentDescription = "Backup and Restore",
                    tint = AccentNeonGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BACKUP & RESTORE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Save your chats, memories, knowledge graph, macros, " +
                            "routines and settings to one encrypted file, or restore " +
                            "from a backup made on any device.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            Row {
                Button(
                    onClick = { showExportDialog = true },
                    enabled = working == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export backup")
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    enabled = working == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Restore backup")
                }
            }

            working?.let { current ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = current.label, fontSize = 12.sp, color = TextSecondary)
                }
            }

            (state as? BackupRestoreUiState.ExportDone)?.let { done ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Backup created: ${describeCounts(done.summary)}.",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Keep the file and its password safe — without the password " +
                        "the backup cannot be opened by anyone, including you.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            (state as? BackupRestoreUiState.RestoreDone)?.let { done ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Restore complete: ${describeCounts(done.summary)}.",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Restart the app so every screen picks up the restored data.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                TextButton(onClick = { viewModel.restartApp() }) {
                    Text("Restart app")
                }
            }

            (state as? BackupRestoreUiState.Error)?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = error.message, fontSize = 12.sp, color = AccentRed)
                TextButton(onClick = { viewModel.reset() }) {
                    Text("Dismiss")
                }
            }
        }
    }

    if (showExportDialog) {
        ExportPasswordDialog(
            onDismiss = { showExportDialog = false },
            onContinue = { password, includeCredentials ->
                showExportDialog = false
                pendingExport = password to includeCredentials
                createDocumentLauncher.launch(viewModel.suggestedFileName())
            }
        )
    }

    if (showImportDialog) {
        ImportPasswordDialog(
            onDismiss = { showImportDialog = false },
            onContinue = { password ->
                showImportDialog = false
                pendingImportPassword = password
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    (state as? BackupRestoreUiState.AwaitingRestoreConfirmation)?.let { awaiting ->
        val summary = awaiting.summary
        AlertDialog(
            onDismissRequest = { viewModel.cancelRestore() },
            title = { Text("Replace your data with this backup?") },
            text = {
                Column {
                    Text(
                        text = "Backup from ${formatBackupDate(summary.createdAt)} " +
                            "(OpenDroid ${summary.appVersion}).",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = describeCounts(summary) +
                            if (summary.includesSettings) ", plus settings" else "",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    if (summary.includesCredentials) {
                        Text(
                            text = "This backup also contains API keys and sensitive " +
                                "memories, which will be restored.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Restoring replaces your current chats, memories, macros, " +
                            "plans and routines with the backup's. This cannot be undone.",
                        fontSize = 12.sp,
                        color = AccentRed
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRestore() }) {
                    Text("Replace and restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRestore() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ExportPasswordDialog(
    onDismiss: () -> Unit,
    onContinue: (password: String, includeCredentials: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var includeCredentials by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a backup password") },
        text = {
            Column {
                Text(
                    text = "The backup file is encrypted with this password. " +
                        "There is no recovery if you forget it.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; problem = null },
                    label = { Text("Password (min. ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; problem = null },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Include API keys & sensitive memories",
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Off by default. Anyone who gets this file and your " +
                                "password would get your keys too.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = includeCredentials,
                        onCheckedChange = { includeCredentials = it }
                    )
                }
                problem?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = message, fontSize = 12.sp, color = AccentRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    problem = when {
                        password.length < BackupCrypto.MIN_PASSPHRASE_LENGTH ->
                            "Password must be at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters."
                        password != confirmation -> "The two passwords do not match."
                        else -> null
                    }
                    if (problem == null) onContinue(password, includeCredentials)
                }
            ) {
                Text("Choose location")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportPasswordDialog(
    onDismiss: () -> Unit,
    onContinue: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from backup") },
        text = {
            Column {
                Text(
                    text = "Enter the password the backup was created with, then pick " +
                        "the backup file. Nothing is changed until you confirm the restore.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (password.isNotEmpty()) onContinue(password) }
            ) {
                Text("Choose file")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** "12 chats · 312 messages · 148 memories · 9 macros · 3 plans · 2 routines" */
private fun describeCounts(summary: BackupSummary): String = listOf(
    "${summary.sessionCount} chats",
    "${summary.messageCount} messages",
    "${summary.memoryCount} memories",
    "${summary.macroCount} macros",
    "${summary.planCount} plans",
    "${summary.routineCount} routines"
).joinToString(" · ")

private fun formatBackupDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(timestamp))
