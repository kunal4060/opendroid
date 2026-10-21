package com.opendroid.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.rag.RAG_IMPORT_MIME_TYPES
import com.opendroid.ai.core.rag.RagDocument
import com.opendroid.ai.core.rag.RagDocumentStatus
import com.opendroid.ai.core.rag.RetrievedChunk
import com.opendroid.ai.ui.components.MarkdownMessage
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.KnowledgeBaseViewModel
import java.util.Locale

/**
 * Knowledge Base (feature D4): the user's local document index.
 *
 * Documents are imported from the SAF picker (PDF + text/Markdown),
 * extracted and chunked on-device, and indexed in app-private storage.
 * The ask box answers from the index with numbered citations that open
 * the exact source chunk. Statuses are honest by construction: a failed
 * import shows its error on the row, and a question with no matching
 * chunk gets a "not in your documents" answer instead of a guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    viewModel: KnowledgeBaseViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val documents by viewModel.documents.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    val question by viewModel.question.collectAsState()
    val isAnswering by viewModel.isAnswering.collectAsState()
    val answer by viewModel.answer.collectAsState()
    val askError by viewModel.askError.collectAsState()

    var selectedCitation by remember { mutableStateOf<RetrievedChunk?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "KNOWLEDGE BASE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = searchMode,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }

            // ── Ask box ───────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ASK YOUR DOCUMENTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentNeonGreen
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = question,
                            onValueChange = viewModel::onQuestionChange,
                            label = { Text("Question") },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = viewModel::ask,
                            enabled = question.isNotBlank() && !isAnswering &&
                                documents.any { it.status == RagDocumentStatus.READY },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isAnswering) "Searching documents…" else "Ask")
                        }
                        askError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(error, color = AccentRed, fontSize = 12.sp)
                        }
                        answer?.let { result ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Divider(color = BorderColor)
                            Spacer(modifier = Modifier.height(12.dp))
                            MarkdownMessage(text = result.text)
                            if (result.citations.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "SOURCES",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                result.citations.forEachIndexed { index, hit ->
                                    Text(
                                        text = "[${index + 1}] ${hit.chunk.citationLabel()}",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AccentNeonGreen,
                                        modifier = Modifier
                                            .clickable { selectedCitation = hit }
                                            .padding(vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Documents ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DOCUMENTS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                    Button(
                        onClick = { importLauncher.launch(RAG_IMPORT_MIME_TYPES) }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add documents")
                    }
                }
            }

            if (documents.isEmpty()) {
                item {
                    Text(
                        text = "No documents yet. Add PDFs or text files and ask " +
                            "questions over them — everything is indexed privately " +
                            "on this device.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            } else {
                items(documents, key = { it.id }) { document ->
                    DocumentRow(
                        document = document,
                        progress = indexingProgress[document.id],
                        onDelete = { viewModel.deleteDocument(document.id) }
                    )
                }
            }

            item {
                Text(
                    text = "Tip: turn on the Documents chip in Chat to ground " +
                        "chat answers in this knowledge base.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }

    selectedCitation?.let { hit ->
        AlertDialog(
            onDismissRequest = { selectedCitation = null },
            title = { Text(hit.chunk.citationLabel(), fontSize = 15.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(hit.chunk.text, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCitation = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun DocumentRow(
    document: RagDocument,
    progress: Float?,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(3.dp))
                val statusText = when (document.status) {
                    RagDocumentStatus.INDEXING -> {
                        val pct = progress?.let { " ${(it * 100).toInt()}%" } ?: ""
                        "Indexing…$pct"
                    }
                    RagDocumentStatus.READY -> buildString {
                        append("Ready · ${document.chunkCount} chunks")
                        append(
                            if (document.embeddingModelId != null) " · semantic index"
                            else " · keyword index"
                        )
                        if (document.sizeBytes > 0) {
                            append(" · ${formatSize(document.sizeBytes)}")
                        }
                    }
                    RagDocumentStatus.ERROR ->
                        "Error: ${document.errorMessage ?: "indexing failed"}"
                }
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (document.status == RagDocumentStatus.ERROR) {
                        AccentRed
                    } else {
                        TextSecondary
                    }
                )
                if (document.status == RagDocumentStatus.INDEXING) {
                    progress?.let { fraction ->
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove ${document.displayName}",
                    tint = TextSecondary
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
