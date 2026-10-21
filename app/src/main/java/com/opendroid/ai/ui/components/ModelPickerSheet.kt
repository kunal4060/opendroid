package com.opendroid.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkSurface
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

/**
 * The provider + model a chat will actually be answered by (feature A2):
 * the session's persisted override when one is set and still usable,
 * otherwise the global Settings default. Produced by ChatViewModel from
 * the same resolution the send path uses, so the chip can never display a
 * stale override the request path would ignore.
 */
data class EffectiveProviderModel(
    val provider: String,
    val model: String,
    val isOverride: Boolean
)

/** One selectable (or visibly unavailable) model row in the picker. */
data class PickerModelOption(
    val id: String,
    val displayName: String,
    /** False only for on-device models that are not downloaded - shown, never silently selectable. */
    val available: Boolean,
    /** Short state label ("Not downloaded", "Built-in"); null when nothing needs saying. */
    val availabilityLabel: String? = null
)

/** One provider section of the picker, built entirely from real registry/config state. */
data class PickerProviderOption(
    val provider: String,
    val displayName: String,
    val isOnDevice: Boolean,
    val requiresApiKey: Boolean,
    /** True when the provider could actually serve a request (key/endpoint present, or none needed). */
    val isConfigured: Boolean,
    val latencyMs: Long? = null,
    val models: List<PickerModelOption> = emptyList()
)

data class ModelPickerUiState(
    val providers: List<PickerProviderOption> = emptyList(),
    /** Canonical name of the provider whose live model list is being fetched, if any. */
    val fetchingProvider: String? = null
)

/**
 * Tappable header chip showing the effective provider • model for the
 * current chat. A trailing "CHAT" tag marks a per-chat override so the
 * user can tell at a glance this chat does not follow the account default.
 */
@Composable
fun ModelPickerChip(
    effective: EffectiveProviderModel,
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
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = if (effective.isOverride) AccentCyan else TextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${effective.provider} • ${effective.model}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
                maxLines = 1
            )
            if (effective.isOverride) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CHAT",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Choose model for this chat",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Bottom sheet behind [ModelPickerChip]. Picking a model applies it to
 * THIS chat immediately (persisted per session); the footer can promote
 * the shown selection to the account-wide default, which also clears the
 * chat override (the chat then simply follows the default it now equals).
 * On-device models that are not downloaded are listed disabled with
 * their real state - never selectable-and-silently-broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    state: ModelPickerUiState,
    effective: EffectiveProviderModel,
    onSelectModel: (provider: String, model: String) -> Unit,
    onUseAccountDefault: () -> Unit,
    onMakeDefault: (provider: String, model: String) -> Unit,
    onExpandProvider: (provider: String) -> Unit,
    onDismiss: () -> Unit
) {
    var expandedProvider by remember { mutableStateOf<String?>(effective.provider) }
    // The selection the footer "make default" action would promote: the
    // last model tapped in this sheet, or the effective one if none was.
    var pendingSelection by remember {
        mutableStateOf<Pair<String, String>?>(null)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { onExpandProvider(effective.provider) }

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
                text = "Model for this chat",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Applies to this chat only. Other chats keep the account default.",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            if (effective.isOverride) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "This chat uses its own model.",
                        fontSize = 12.sp,
                        color = AccentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        onUseAccountDefault()
                        pendingSelection = null
                    }) {
                        Text("Use account default", fontSize = 12.sp, color = TextPrimary)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(state.providers, key = { it.provider }) { option ->
                    PickerProviderSection(
                        option = option,
                        isExpanded = expandedProvider == option.provider,
                        isFetching = state.fetchingProvider == option.provider,
                        effective = effective,
                        onToggleExpand = {
                            val expanding = expandedProvider != option.provider
                            expandedProvider = if (expanding) option.provider else null
                            if (expanding) onExpandProvider(option.provider)
                        },
                        onSelectModel = { model ->
                            pendingSelection = option.provider to model.id
                            onSelectModel(option.provider, model.id)
                        }
                    )
                    HorizontalDivider(color = BorderColor)
                }
            }

            val defaultTarget = pendingSelection ?: (effective.provider to effective.model)
            OutlinedButton(
                onClick = { onMakeDefault(defaultTarget.first, defaultTarget.second) },
                border = BorderStroke(1.dp, BorderColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(
                    text = "Make default for all chats: ${defaultTarget.first} • ${defaultTarget.second}",
                    fontSize = 12.sp,
                    maxLines = 1
                )
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
private fun PickerProviderSection(
    option: PickerProviderOption,
    isExpanded: Boolean,
    isFetching: Boolean,
    effective: EffectiveProviderModel,
    onToggleExpand: () -> Unit,
    onSelectModel: (PickerModelOption) -> Unit
) {
    val isEffectiveProvider = option.provider == effective.provider
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = option.displayName,
                        fontSize = 14.sp,
                        fontWeight = if (isEffectiveProvider) FontWeight.Bold else FontWeight.Normal,
                        color = TextPrimary
                    )
                    if (option.isOnDevice) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ON-DEVICE",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                    }
                    if (isEffectiveProvider) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Currently used by this chat",
                            tint = AccentCyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                val detail = buildString {
                    if (option.requiresApiKey && !option.isConfigured) {
                        append("No API key set")
                    } else if (!option.isConfigured && !option.isOnDevice) {
                        append("Not configured")
                    }
                    option.latencyMs?.let { latency ->
                        if (isNotEmpty()) append(" • ")
                        append("${latency} ms")
                    }
                    if (isFetching) {
                        if (isNotEmpty()) append(" • ")
                        append("Loading models…")
                    }
                }
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        fontSize = 11.sp,
                        color = if (option.requiresApiKey && !option.isConfigured) {
                            AccentRed
                        } else {
                            TextSecondary
                        }
                    )
                }
            }
            Icon(
                imageVector = if (isExpanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        if (isExpanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                option.models.forEach { model ->
                    val isSelected = isEffectiveProvider && model.id == effective.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = model.available) { onSelectModel(model) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model.displayName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (model.available) TextPrimary else TextSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        model.availabilityLabel?.let { label ->
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected for this chat",
                                tint = AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
                if (option.isOnDevice) {
                    Text(
                        text = "Download more models in Settings → On-Device Models.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
