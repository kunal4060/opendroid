package com.opendroid.ai.ui.screens.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.memory.graph.KnowledgeNode
import com.opendroid.ai.core.memory.graph.KnowledgeRelation
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.ui.lock.BiometricAuth
import com.opendroid.ai.ui.lock.findFragmentActivity
import com.opendroid.ai.ui.screens.MemoryProvenancePanel
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentOrange
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.DarkSurface
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail sheet for one node of the memory graph visualiser (C5). Everything
 * shown comes from the node's stored record; every action writes back to
 * the real knowledge graph through the same ViewModel/engine paths the list
 * view uses:
 * - Promote → `PersonalGrowthEngine.promotePatternToExplicit`
 * - Edit    → `PersonalGrowthEngine.updateNodeContent` (non-sensitive only)
 * - Delete  → `PersonalGrowthEngine.deleteNode` (also removes the Level-4
 *   secret from the Keystore store and the node from recall)
 *
 * Level-4 (Sensitive) records show only their metadata here. The secret
 * value itself is revealed solely after the app-lock gate (A5): the same
 * biometric/device-credential prompt as the lock screen, or the app PIN as
 * fallback — if neither exists, the sheet says so instead of revealing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphNodeDetailSheet(
    node: KnowledgeNode,
    edges: List<KnowledgeRelation>,
    nodeLabels: Map<String, String>,
    viewModel: MemoryViewModel,
    onDismiss: () -> Unit,
    onSelectNode: (String) -> Unit,
    onPromote: () -> Unit,
    onSaveEdit: (label: String, summary: String) -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }
    val isSensitive = node.tier == MemoryTier.SENSITIVE

    var confirmDelete by remember(node.id) { mutableStateOf(false) }
    var editing by remember(node.id) { mutableStateOf(false) }
    var editLabel by remember(node.id) { mutableStateOf(node.label) }
    var editSummary by remember(node.id) { mutableStateOf(node.summary) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSensitive) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = AccentOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isSensitive) "Locked sensitive record" else node.label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = AccentCyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Text(
                        text = GraphRenderModel.tierDisplayName(node.tier),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    color = TextPrimary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = node.category.name.replace('_', ' '),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (node.tier == MemoryTier.LEARNED_PATTERN) {
                    Text(
                        text = "${(node.confidence * 100).toInt()}% confidence",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Content ─────────────────────────────────────────────────
            if (editing && !isSensitive) {
                OutlinedTextField(
                    value = editLabel,
                    onValueChange = { editLabel = it },
                    label = { Text("Label", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editSummary,
                    onValueChange = { editSummary = it },
                    label = { Text("Details", fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { editing = false }) {
                        Text("Cancel", color = AccentRed)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (editLabel.isNotBlank() && editSummary.isNotBlank()) {
                                onSaveEdit(editLabel.trim(), editSummary.trim())
                            }
                        },
                        enabled = editLabel.isNotBlank() && editSummary.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimary,
                            contentColor = DarkSurface
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save changes", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    text = node.summary,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                if (isSensitive) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The value itself is encrypted in the device Keystore " +
                            "and is never shown on the graph. Reveal it below only " +
                            "after authentication.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            if (!isSensitive && node.properties.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                node.properties.entries.take(6).forEach { (key, value) ->
                    Text(
                        text = "$key: $value",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Text(
                text = "Last updated: ${dateFormat.format(Date(node.lastUpdated))}",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 10.dp)
            )

            // ── Connections (stored relations only) ─────────────────────
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "CONNECTIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (edges.isEmpty()) {
                Text(
                    text = "No recorded connections for this memory yet.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            } else {
                edges.forEach { edge ->
                    val otherId = GraphRenderModel.otherEnd(edge, node.id)
                    val otherLabel = nodeLabels[otherId] ?: "Memory"
                    val arrow = if (edge.sourceId == node.id) "→" else "←"
                    TextButton(
                        onClick = { onSelectNode(otherId) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$arrow $otherLabel · ${edge.relationType.replace('_', ' ')}",
                            fontSize = 12.sp,
                            color = AccentCyan
                        )
                    }
                }
            }

            // ── Provenance (A7 panel, reads the stored origin) ─────────
            Spacer(modifier = Modifier.height(8.dp))
            MemoryProvenancePanel(
                node = node,
                onDelete = { confirmDelete = true }
            )

            // ── Sensitive reveal (A5 app-lock gate) ─────────────────────
            if (isSensitive) {
                Spacer(modifier = Modifier.height(10.dp))
                SensitiveRevealSection(node = node, viewModel = viewModel)
            }

            // ── Actions ─────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (node.tier == MemoryTier.LEARNED_PATTERN) {
                    OutlinedButton(
                        onClick = onPromote,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Promote to Long-Term", fontSize = 11.sp, color = AccentCyan)
                    }
                }
                if (!isSensitive && !editing) {
                    OutlinedButton(
                        onClick = { editing = true },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, BorderColor)
                    ) {
                        Text("Edit", fontSize = 11.sp, color = TextPrimary)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (confirmDelete) {
                    TextButton(onClick = onDelete) {
                        Text(
                            "Tap again to delete",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentRed
                        )
                    }
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", fontSize = 11.sp, color = AccentRed)
                    }
                }
            }
        }
    }
}

/**
 * The Level-4 reveal gate. Authentication is the app lock's own machinery
 * (A5): the biometric/device-credential system prompt used by the lock
 * screen, with the app PIN as the fallback when no system credential can be
 * checked. Only after a success is the secret read from the Keystore-backed
 * [com.opendroid.ai.core.security.SensitiveMemoryStore] (through the
 * ViewModel) and shown; a fresh sheet always starts hidden again.
 */
@Composable
private fun SensitiveRevealSection(
    node: KnowledgeNode,
    viewModel: MemoryViewModel
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val biometricAvailable = remember(context) { BiometricAuth.canAuthenticate(context) }
    val hasPin by viewModel.appLockHasPin.collectAsState()
    val scope = rememberCoroutineScope()

    var revealedSecret by remember(node.id) { mutableStateOf<String?>(null) }
    var revealError by remember(node.id) { mutableStateOf<String?>(null) }
    var pin by remember(node.id) { mutableStateOf("") }
    var verifying by remember(node.id) { mutableStateOf(false) }

    val secretKey = node.properties["key"] ?: node.id.removePrefix("sensitive_")

    fun reveal() {
        val value = viewModel.sensitiveSecret(secretKey)
        revealedSecret = value ?: "This record no longer exists in the secure store."
        revealError = null
    }

    Text(
        text = "ENCRYPTED VALUE",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = AccentOrange
    )
    Spacer(modifier = Modifier.height(6.dp))

    val shown = revealedSecret
    if (shown != null) {
        Surface(
            color = TextPrimary.copy(alpha = 0.06f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = shown,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { revealedSecret = null }) {
            Text("Hide", fontSize = 11.sp, color = AccentCyan)
        }
        return
    }

    revealError?.let { message ->
        Text(
            text = message,
            fontSize = 12.sp,
            color = AccentRed,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }

    if (biometricAvailable) {
        Button(
            onClick = {
                val host = activity ?: run {
                    revealError = "Biometric unlock is unavailable here."
                    return@Button
                }
                BiometricAuth.showPrompt(
                    activity = host,
                    title = "Reveal sensitive memory",
                    subtitle = "Authenticate to view this encrypted record",
                    onSuccess = { reveal() },
                    onError = { message -> revealError = message }
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = DarkSurface,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reveal secret", color = DarkSurface, fontWeight = FontWeight.Bold)
        }
    } else if (hasPin) {
        OutlinedTextField(
            value = pin,
            onValueChange = { candidate ->
                pin = candidate.filter { it.isDigit() }.take(6)
                revealError = null
            },
            label = { Text("App PIN") },
            singleLine = true,
            enabled = !verifying,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentOrange,
                unfocusedBorderColor = BorderColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                verifying = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        viewModel.verifyAppLockPin(pin)
                    }
                    verifying = false
                    pin = ""
                    when (result) {
                        AppLockManager.PinVerification.Success -> reveal()
                        is AppLockManager.PinVerification.Incorrect ->
                            revealError =
                                "Incorrect PIN. ${result.attemptsRemaining} attempt(s) left."
                        is AppLockManager.PinVerification.InCooldown ->
                            revealError =
                                "Too many incorrect attempts. Try again in " +
                                    "${(result.remainingMs + 999) / 1000} seconds."
                        AppLockManager.PinVerification.NoPinSet ->
                            revealError = "No app PIN is set."
                    }
                }
            },
            enabled = pin.length >= 4 && !verifying,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.6f))
        ) {
            Text("Unlock with PIN to reveal", fontSize = 11.sp, color = AccentOrange)
        }
    } else {
        Text(
            text = "To reveal this secret, the device needs a screen lock or " +
                "biometric, or an app PIN (Settings → App Lock). None is set " +
                "up, so the value stays hidden.",
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}
