package com.opendroid.ai.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.Persona
import com.opendroid.ai.data.models.PersonaKind
import com.opendroid.ai.data.models.PersonaValidation
import com.opendroid.ai.ui.theme.AccentGreenButton
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.OnAccent
import com.opendroid.ai.ui.theme.DarkBackground
import com.opendroid.ai.ui.theme.DarkSurface
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.PersonasViewModel
import kotlinx.coroutines.launch

/**
 * Library management screen for feature B6: create / edit / duplicate /
 * delete personas and quick commands, and export / import the library as
 * JSON. Applying a persona to a chat happens in the chat itself (header
 * chip); this screen owns the library content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonasScreen(
    viewModel: PersonasViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val personas by viewModel.personas.collectAsState()
    val sessionBindings by viewModel.sessionBindings.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var editing by remember { mutableStateOf<Persona?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Persona?>(null) }
    var showImport by remember { mutableStateOf(false) }

    LaunchedEffect(userMessage) {
        userMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearUserMessage()
        }
    }

    val personaEntries = personas.filter { it.kind == PersonaKind.PERSONA }
    val quickCommands = personas.filter { it.kind == PersonaKind.QUICK_COMMAND }
    val usageByPersona = sessionBindings.values.groupingBy { it }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PERSONAS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        letterSpacing = 1.5.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val payload = viewModel.exportJson()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "OpenDroid persona library")
                                putExtra(Intent.EXTRA_TEXT, payload)
                            }
                            context.startActivity(
                                Intent.createChooser(sendIntent, "Export persona library")
                            )
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export library as JSON",
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = { showImport = true }) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Import library from JSON",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = AccentGreenButton,
                contentColor = OnAccent
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "New persona or command")
            }
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "intro") {
                Text(
                    text = "A persona is a saved system prompt you can apply to any chat " +
                        "from its header — it shapes that chat's AI replies and planning " +
                        "only. Quick commands are reusable snippets inserted into the " +
                        "message box, never sent automatically.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item(key = "personas-header") {
                LibrarySectionHeader("PERSONAS")
            }
            if (personaEntries.isEmpty()) {
                item(key = "personas-empty") {
                    Text(
                        text = "No personas yet. Tap + to create one.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            items(personaEntries, key = { it.id }) { persona ->
                PersonaLibraryRow(
                    persona = persona,
                    usageLabel = usageByPersona[persona.id]?.let { count ->
                        if (count == 1) "Used in 1 chat" else "Used in $count chats"
                    },
                    onEdit = { editing = persona },
                    onDuplicate = { viewModel.duplicatePersona(persona.id) },
                    onDelete = { pendingDelete = persona }
                )
            }

            item(key = "commands-header") {
                LibrarySectionHeader("QUICK COMMANDS")
            }
            if (quickCommands.isEmpty()) {
                item(key = "commands-empty") {
                    Text(
                        text = "No quick commands yet. Tap + and choose \"Quick command\".",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            items(quickCommands, key = { it.id }) { command ->
                PersonaLibraryRow(
                    persona = command,
                    usageLabel = null,
                    onEdit = { editing = command },
                    onDuplicate = { viewModel.duplicatePersona(command.id) },
                    onDelete = { pendingDelete = command }
                )
            }

            item(key = "bottom-space") {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (creating) {
        PersonaEditorDialog(
            initial = null,
            onSave = { name, prompt, kind ->
                viewModel.createPersona(name, prompt, kind)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }

    editing?.let { persona ->
        PersonaEditorDialog(
            initial = persona,
            onSave = { name, prompt, kind ->
                viewModel.updatePersona(persona.id, name, prompt, kind)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    pendingDelete?.let { persona ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = DarkSurface,
            title = { Text("Delete \"${persona.name}\"?", color = TextPrimary) },
            text = {
                Text(
                    text = if (persona.kind == PersonaKind.PERSONA) {
                        "Chats using this persona will fall back to the default prompt. " +
                            "This cannot be undone."
                    } else {
                        "This quick command will be removed from the library. " +
                            "This cannot be undone."
                    },
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePersona(persona.id)
                    pendingDelete = null
                }) {
                    Text("Delete", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showImport) {
        var importText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            containerColor = DarkSurface,
            title = { Text("Import library", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        text = "Paste a persona library export. Imported entries are " +
                            "added to your library; nothing is overwritten.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("{\"format\": \"opendroid-persona-library\", …}") },
                        minLines = 5,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importJson(importText)
                        showImport = false
                    },
                    enabled = importText.isNotBlank()
                ) {
                    Text("Import", color = AccentNeonGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun LibrarySectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = AccentNeonGreen,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun PersonaLibraryRow(
    persona: Persona,
    usageLabel: String?,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = persona.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    if (persona.isBuiltIn) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "BUILT-IN",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentNeonGreen
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = persona.prompt,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2
                )
                if (usageLabel != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = usageLabel,
                        fontSize = 11.sp,
                        color = AccentNeonGreen
                    )
                }
            }
            IconButton(onClick = onDuplicate) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Duplicate ${persona.name}",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete ${persona.name}",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PersonaEditorDialog(
    initial: Persona?,
    onSave: (name: String, prompt: String, kind: PersonaKind) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: PersonaKind.PERSONA) }

    val promptOverLimit = prompt.trim().length > PersonaValidation.MAX_PROMPT_CHARS
    val canSave = name.isNotBlank() && prompt.isNotBlank() && !promptOverLimit

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = if (initial == null) "New library entry" else "Edit entry",
                color = TextPrimary
            )
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == PersonaKind.PERSONA,
                        onClick = { kind = PersonaKind.PERSONA },
                        label = { Text("Persona") }
                    )
                    FilterChip(
                        selected = kind == PersonaKind.QUICK_COMMAND,
                        onClick = { kind = PersonaKind.QUICK_COMMAND },
                        label = { Text("Quick command") }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = {
                        Text(
                            if (kind == PersonaKind.PERSONA) {
                                "System prompt"
                            } else {
                                "Text to insert"
                            }
                        )
                    },
                    minLines = if (kind == PersonaKind.PERSONA) 4 else 2,
                    maxLines = 8,
                    isError = promptOverLimit,
                    supportingText = {
                        Text(
                            text = "${prompt.trim().length} / ${PersonaValidation.MAX_PROMPT_CHARS}",
                            color = if (promptOverLimit) AccentRed else TextSecondary
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (kind == PersonaKind.PERSONA) {
                        "Applied to a chat, this text leads that chat's system prompt."
                    } else {
                        "Picking this in a chat inserts the text into the message box."
                    },
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, prompt, kind) },
                enabled = canSave
            ) {
                Text("Save", color = AccentNeonGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
