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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.research.ResearchProviderKind
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.ResearchSettingsViewModel

/**
 * Settings → Web research (feature D3).
 *
 * Picks the search backend the research pipeline runs with and collects
 * its credential. Keys are stored in the Keystore-backed research store —
 * the same values, read live, that [com.opendroid.ai.core.research
 * .ResearchPipeline] uses; this screen never keeps a saved key in state,
 * only a 4-character hint. SearXNG is the keyless option: the user points
 * it at an instance they run or trust.
 */
@Composable
fun ResearchSettingsSection(
    viewModel: ResearchSettingsViewModel = hiltViewModel()
) {
    val providerKind by viewModel.providerKind.collectAsState()
    val tavilyHint by viewModel.tavilyKeyHint.collectAsState()
    val braveHint by viewModel.braveKeyHint.collectAsState()
    val savedSearxngUrl by viewModel.searxngUrl.collectAsState()

    var tavilyKeyInput by remember { mutableStateOf("") }
    var braveKeyInput by remember { mutableStateOf("") }
    var searxngInput by remember(savedSearxngUrl) { mutableStateOf(savedSearxngUrl) }

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
                    imageVector = Icons.Default.Language,
                    contentDescription = "Web research",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Web research",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Live search + cited answers in chat's Research mode and the WEB_RESEARCH action.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Research answers cite only pages actually fetched in that run — " +
                    "every [n] marker resolves to a stored source under the answer.",
                fontSize = 12.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "SEARCH PROVIDER",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                ResearchProviderKind.entries.forEach { kind ->
                    FilterChip(
                        selected = providerKind == kind,
                        onClick = { viewModel.setProviderKind(kind) },
                        label = { Text(kind.displayName, fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            when (providerKind) {
                ResearchProviderKind.TAVILY -> {
                    Text(
                        text = "Tavily is built for agents: one key covers search and page " +
                            "extraction. A free key (1,000 credits/month) is enough to try it — " +
                            "get one at tavily.com. Stored encrypted on this device.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ApiKeyField(
                        label = "Tavily API key",
                        input = tavilyKeyInput,
                        onInputChange = { tavilyKeyInput = it },
                        savedHint = tavilyHint,
                        onSave = {
                            viewModel.saveTavilyApiKey(tavilyKeyInput)
                            tavilyKeyInput = ""
                        },
                        onClear = { viewModel.clearTavilyApiKey() }
                    )
                }

                ResearchProviderKind.BRAVE -> {
                    Text(
                        text = "Brave Search runs its own independent index. Page text is " +
                            "extracted on-device from the top results. Stored encrypted on " +
                            "this device.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ApiKeyField(
                        label = "Brave Search API key",
                        input = braveKeyInput,
                        onInputChange = { braveKeyInput = it },
                        savedHint = braveHint,
                        onSave = {
                            viewModel.saveBraveApiKey(braveKeyInput)
                            braveKeyInput = ""
                        },
                        onClear = { viewModel.clearBraveApiKey() }
                    )
                }

                ResearchProviderKind.SEARXNG -> {
                    Text(
                        text = "Keyless: point OpenDroid at a SearXNG instance you run or " +
                            "trust. The instance must have the JSON API enabled " +
                            "(formats: json) or searches will be refused.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searxngInput,
                        onValueChange = { searxngInput = it },
                        label = { Text("SearXNG instance URL") },
                        placeholder = { Text("https://search.example.org") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row {
                        Button(
                            onClick = { viewModel.saveSearxngUrl(searxngInput) },
                            enabled = searxngInput.isNotBlank()
                        ) {
                            Text("Save instance")
                        }
                        if (savedSearxngUrl.isNotBlank()) {
                            TextButton(onClick = {
                                viewModel.saveSearxngUrl("")
                                searxngInput = ""
                            }) {
                                Text("Clear", color = AccentRed)
                            }
                        }
                    }
                }
            }

            if (viewModel.isConfigured) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Ready — turn on Research in a chat, or ask the agent to research something.",
                    fontSize = 12.sp,
                    color = AccentCyan
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    input: String,
    onInputChange: (String) -> Unit,
    savedHint: String?,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    if (savedHint != null) {
        Text(
            text = "Saved key: $savedHint",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
    OutlinedTextField(
        value = input,
        onValueChange = onInputChange,
        label = { Text(if (savedHint != null) "Replace key" else label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row {
        Button(onClick = onSave, enabled = input.isNotBlank()) {
            Text("Save key")
        }
        if (savedHint != null) {
            TextButton(onClick = onClear) {
                Text("Remove key", color = AccentRed)
            }
        }
    }
}
