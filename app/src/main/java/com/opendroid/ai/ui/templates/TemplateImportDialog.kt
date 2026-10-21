package com.opendroid.ai.ui.templates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.templates.TemplateFormat
import com.opendroid.ai.core.templates.TemplateImportPreview
import com.opendroid.ai.ui.theme.AccentOrange
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

/**
 * C6 import preview: shown after a template file is picked and validated,
 * before anything is written. Blocking errors disable the Import button;
 * warnings (device-specific references, dropped dependency links) are shown
 * but do not block. A file that failed decoding shows only its error.
 */
@Composable
fun TemplateImportDialog(
    preview: TemplateImportPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = {
            Text(
                text = if (preview.fatalError != null) "Can't import this file" else "Import template",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val fatal = preview.fatalError
                if (fatal != null) {
                    Text(fatal, color = AccentRed, fontSize = 13.sp)
                } else {
                    val envelope = preview.envelope!!
                    val kindLabel = if (envelope.kind == TemplateFormat.KIND_MACRO) "Macro" else "Plan"
                    Text(
                        text = "'${preview.resolvedName}' — $kindLabel · ${preview.stepSummaries.size} steps",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    if (preview.resolvedName != envelope.name.trim()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Renamed so it doesn't overwrite your existing '${envelope.name.trim()}'.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    if (envelope.kind == TemplateFormat.KIND_MACRO && envelope.trigger.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Trigger: \"${envelope.trigger}\"",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    preview.stepSummaries.forEach { summary ->
                        Text(summary, fontSize = 11.sp, color = TextPrimary)
                    }
                    if (preview.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        preview.warnings.forEach { warning ->
                            // Theme AccentOrange (D8): the old hard-coded
                            // #FFB300 amber was 1.79:1 as text on light.
                            Text("⚠ $warning", fontSize = 11.sp, color = AccentOrange)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    if (preview.errors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "This file can't be imported:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = AccentRed
                        )
                        preview.errors.forEach { error ->
                            Text("• $error", fontSize = 11.sp, color = AccentRed)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    if (preview.canImport && envelope.kind == TemplateFormat.KIND_MACRO) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Imported macros start switched off — enable the macro to run it.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (preview.canImport) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TextPrimary,
                        contentColor = DarkBackground
                    )
                ) {
                    Text("Import", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (preview.canImport) "Cancel" else "Close", color = TextSecondary)
            }
        }
    )
}
