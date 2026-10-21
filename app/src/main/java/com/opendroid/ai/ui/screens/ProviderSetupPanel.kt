package com.opendroid.ai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.llm.ConnectionTestPlanner
import com.opendroid.ai.core.llm.ConnectionTestState
import com.opendroid.ai.core.llm.DownloadFit
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.OnboardingViewModel
import com.opendroid.ai.ui.viewmodel.ProviderSetupUiState
import java.util.Locale

/** Where the free Gemini key comes from (AI Studio, any Google account, no billing). */
private const val GEMINI_KEY_URL = "https://aistudio.google.com/app/apikey"

/**
 * The onboarding PROVIDER stage (A10): "How should OpenDroid think?"
 *
 * Three cards wire machinery that already exists — the on-device backends, the
 * free Gemini tier, and the Settings provider matrix — so completing any card
 * lands in a Chat that can actually answer. Skipping is allowed and honest:
 * the app enters its limited state and Chat keeps a setup banner until a
 * provider exists. Nothing here writes a parallel config; every action goes
 * through [OnboardingViewModel] into the same stores Settings uses.
 */
@Composable
fun ProviderSetupPanel(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.providerSetupState.collectAsState()
    val setupComplete = state.canAnswer || state.onDeviceDone || state.geminiDone || state.byokDone

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "How should OpenDroid think?",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Pick one way to power your answers. None of these needs a paid " +
                "account, and you can change it any time in Settings.",
            fontSize = 14.sp,
            color = TextSecondary
        )

        if (state.canAnswer && !state.onDeviceDone && !state.geminiDone && !state.byokDone) {
            Spacer(modifier = Modifier.height(16.dp))
            SetupNoteCard(
                text = "You're already set up — ${state.activeProviderName} is ready to answer. " +
                    "You can keep it, or switch below."
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OnDeviceCard(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        GeminiKeyCard(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        ByokCard(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(28.dp))

        if (setupComplete) {
            Button(
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Start chatting", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            Text(
                text = "Without a provider, OpenDroid can't answer yet — a reminder will " +
                    "stay on the Chat screen until you set one up.",
                fontSize = 12.sp,
                color = AccentOrange
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        TextButton(
            onClick = onFinished,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (setupComplete) "Keep this for now" else "Skip for now — I'll set this up later",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SetupNoteCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = AccentNeonGreen,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun OnDeviceCard(state: ProviderSetupUiState, viewModel: OnboardingViewModel) {
    SetupCard(
        title = "On this device — no key, no account",
        subtitle = "Answers are generated locally. Nothing leaves your phone."
    ) {
        if (state.aiCoreAvailable) {
            Text(
                text = "Gemini Nano via Android AI Core is available on this device — " +
                    "no download needed.",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (state.onDeviceDone && state.activeProviderName == "On-Device AI") {
                Text("On-device AI is on.", fontSize = 13.sp, color = AppTheme.colors.success)
            } else {
                OutlinedButton(
                    onClick = { viewModel.enableAiCore() },
                    enabled = !state.onDeviceBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use on-device AI", color = TextPrimary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        val spec = state.liteRtSpec
        if (spec != null) {
            Text(
                text = "Or download ${spec.displayName} (${spec.sizeLabel})",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatBytes(spec.expectedSize)} download · ${formatBytes(state.freeBytes)} free on this device",
                fontSize = 12.sp,
                color = TextSecondary
            )

            when (state.downloadFit) {
                DownloadFit.DoesNotFit -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Not enough free space for this download.",
                        fontSize = 12.sp,
                        color = AccentRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DownloadFit.FitsWithLowSpaceWarning -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "It fits, but would leave very little free space on this device. " +
                            "Downloading anyway counts as confirmation.",
                        fontSize = 12.sp,
                        color = AccentOrange
                    )
                }
                DownloadFit.Fits -> Unit
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (state.liteRtStatus) {
                ModelStatus.READY -> {
                    Text("Downloaded and ready.", fontSize = 13.sp, color = AppTheme.colors.success)
                }
                ModelStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { state.liteRtProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentNeonGreen
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Downloading… ${state.liteRtProgress}% — you can start chatting; " +
                            "the download keeps running in the background.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                ModelStatus.PAUSED -> {
                    OutlinedButton(
                        onClick = { viewModel.resumeLiteRtModel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Resume download", color = TextPrimary)
                    }
                }
                ModelStatus.FAILED -> {
                    Text(
                        text = "The last download failed.",
                        fontSize = 12.sp,
                        color = AccentRed
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.downloadLiteRtModel() },
                        enabled = !state.onDeviceBusy && state.downloadFit != DownloadFit.DoesNotFit,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try again", color = TextPrimary)
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = { viewModel.downloadLiteRtModel() },
                        enabled = !state.onDeviceBusy && state.downloadFit != DownloadFit.DoesNotFit,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download & use on-device", color = TextPrimary)
                    }
                }
            }
        }

        state.onDeviceError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GeminiKeyCard(state: ProviderSetupUiState, viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    SetupCard(
        title = "Free Gemini key — about 2 minutes",
        subtitle = "Google gives out free Gemini API keys with any Google account — " +
            "no card or billing. The free tier has modest rate limits, and Google may " +
            "use free-tier requests to improve its products."
    ) {
        OutlinedButton(
            onClick = {
                val uri = Uri.parse(GEMINI_KEY_URL)
                runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
                    .onFailure {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get a free key", color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.geminiKey,
            onValueChange = { viewModel.onGeminiKeyChange(it) },
            label = { Text("Paste your Gemini API key", color = TextSecondary) },
            colors = setupFieldColors(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.saveAndTestGeminiKey() },
            enabled = !state.geminiBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (state.geminiBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = DarkBackground,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing…", fontWeight = FontWeight.Bold)
            } else {
                Text("Save & test key", fontWeight = FontWeight.Bold)
            }
        }

        state.geminiError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.SemiBold)
        }

        ConnectionResultText(state.geminiResult, done = state.geminiDone)
    }
}

@Composable
private fun ByokCard(state: ProviderSetupUiState, viewModel: OnboardingViewModel) {
    val providers = remember { ConnectionTestPlanner.cloudProviders() }
    var menuExpanded by remember { mutableStateOf(false) }
    val provider = state.byokProvider

    SetupCard(
        title = "I have a key / my own server",
        subtitle = "The full provider list lives in Settings — this is the short version."
    ) {
        Text("Provider", fontSize = 12.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(provider, color = TextPrimary, modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                providers.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            viewModel.onByokProviderChange(name)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (provider) {
            "Ollama" -> {
                OutlinedTextField(
                    value = state.byokEndpoint,
                    onValueChange = { viewModel.onByokEndpointChange(it) },
                    label = { Text("Ollama server URL", color = TextSecondary) },
                    placeholder = { Text("http://127.0.0.1:11434", color = TextSecondary.copy(alpha = 0.6f)) },
                    colors = setupFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.byokModel,
                    onValueChange = { viewModel.onByokModelChange(it) },
                    label = { Text("Model (optional)", color = TextSecondary) },
                    placeholder = { Text("llama3", color = TextSecondary.copy(alpha = 0.6f)) },
                    colors = setupFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Works with a server on this device (for example via Termux). " +
                        "The app's network policy blocks plain HTTP to other addresses.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            "Copilot API" -> {
                OutlinedTextField(
                    value = state.byokEndpoint,
                    onValueChange = { viewModel.onByokEndpointChange(it) },
                    label = { Text("Copilot API URL", color = TextSecondary) },
                    colors = setupFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            "Custom OpenAI Compatible" -> {
                OutlinedTextField(
                    value = state.byokEndpoint,
                    onValueChange = { viewModel.onByokEndpointChange(it) },
                    label = { Text("Server URL", color = TextSecondary) },
                    colors = setupFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                ApiKeyField(state = state, viewModel = viewModel)
            }
            else -> ApiKeyField(state = state, viewModel = viewModel)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.saveAndTestByok() },
            enabled = !state.byokBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (state.byokBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = DarkBackground,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing…", fontWeight = FontWeight.Bold)
            } else {
                Text("Save & test", fontWeight = FontWeight.Bold)
            }
        }

        state.byokError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.SemiBold)
        }

        ConnectionResultText(state.byokResult, done = state.byokDone)
    }
}

@Composable
private fun ApiKeyField(state: ProviderSetupUiState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.byokKey,
        onValueChange = { viewModel.onByokKeyChange(it) },
        label = { Text("API key", color = TextSecondary) },
        colors = setupFieldColors(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Renders a probe outcome with the same wording Settings uses for its rows. */
@Composable
private fun ConnectionResultText(state: ConnectionTestState?, done: Boolean) {
    if (state == null) return
    Spacer(modifier = Modifier.height(8.dp))
    when (state) {
        is ConnectionTestState.Connected -> Text(
            text = "Connected with ${state.model} · ${state.latencyMs} ms — you're ready to chat.",
            fontSize = 12.sp,
            color = AccentNeonGreen,
            fontWeight = FontWeight.SemiBold
        )
        is ConnectionTestState.Failed -> Text(
            text = when (state.error) {
                LLMError.AuthInvalid -> "That key was rejected. Check it and try again."
                LLMError.AuthMissing -> "A key is required."
                LLMError.QuotaExhausted -> "Quota exhausted for that key."
                LLMError.RateLimited -> "Rate limited — try again in a moment."
                LLMError.Network -> "Network error — check the connection and try again."
                LLMError.ModelUnavailable -> "That model isn't available on this provider."
                else -> "Connection failed — check the details and try again."
            },
            fontSize = 12.sp,
            color = AccentRed,
            fontWeight = FontWeight.SemiBold
        )
        is ConnectionTestState.ConfigMissing -> Text(
            text = when (state.reason) {
                LLMError.AuthMissing -> "A key is required."
                else -> "Configuration required."
            },
            fontSize = 12.sp,
            color = AccentRed,
            fontWeight = FontWeight.SemiBold
        )
        else -> Unit
    }
}

@Composable
private fun setupFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentNeonGreen,
    unfocusedBorderColor = BorderColor,
    focusedLabelColor = TextPrimary,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = TextPrimary
)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.lastIndex)
    return String.format(
        Locale.getDefault(),
        "%.1f %s",
        bytes / Math.pow(1024.0, digitGroups.toDouble()),
        units[digitGroups]
    )
}
