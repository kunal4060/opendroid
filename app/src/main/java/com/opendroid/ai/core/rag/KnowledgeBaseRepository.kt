package com.opendroid.ai.core.rag

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.PromptBudget
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.models.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The document knowledge base (feature D4).
 *
 * Import: SAF URI → staged copy (hash while copying) → extraction →
 * chunking → optional embedding → persisted in [RagIndexStore]. Re-import
 * of unchanged content is skipped by hash; re-import of a changed file
 * under the same URI replaces its chunks. Deleting a document removes its
 * chunks in the same atomic store write, so deleted content immediately
 * stops being retrievable.
 *
 * Retrieval: semantic (provider embeddings + cosine) when a backend is
 * configured — chunks lacking current-model vectors are backfilled from
 * their stored text first — with BM25 keyword ranking as the fallback
 * whenever no backend exists, embedding fails, or semantic search finds
 * nothing above its floor. Both paths are real ranking over the stored
 * chunk texts; there is no third "pretend" path.
 */
@Singleton
class KnowledgeBaseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RagIndexStore,
    private val extractor: DocumentTextExtractor,
    private val embeddingResolver: EmbeddingBackendResolver,
    private val providerFactory: LLMProviderFactory
) {

    /** All imported documents, in import order. */
    val documents: Flow<List<RagDocument>> = store.index.map { it.documents }

    private val _indexingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Document id → 0..1 progress while that document is indexing. */
    val indexingProgress: StateFlow<Map<String, Float>> = _indexingProgress.asStateFlow()

    /** Loads the persisted index (idempotent). */
    suspend fun refresh() = store.ensureLoaded()

    // ── Import ────────────────────────────────────────────────────────

    /**
     * Imports (or re-indexes) the document at [uri]. Never throws: the
     * outcome is written into the document's status — READY with its chunk
     * count, or ERROR with the reason — so failures are visible in the list.
     */
    suspend fun importDocument(uri: Uri) {
        store.ensureLoaded()
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Document"
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                displayName.substringAfterLast('.', "")
            )

        val staged = try {
            stageToTempFile(uri)
        } catch (e: RagExtractionException) {
            recordFailedImport(uri.toString(), displayName, mimeType, e.message ?: "Import failed.")
            return
        }

        var progressDocId: String? = null
        try {
            val existing = store.index.value.documents
            val sameHash = existing.firstOrNull {
                it.contentHash == staged.sha256 && it.status == RagDocumentStatus.READY
            }
            if (sameHash != null && sameHash.sourceUri != uri.toString()) {
                // Identical content already indexed under another source:
                // the hash-skip — re-indexing would only duplicate chunks.
                return
            }
            val docId = existing.firstOrNull { it.sourceUri == uri.toString() }?.id
                ?: UUID.randomUUID().toString()
            progressDocId = docId
            val document = RagDocument(
                id = docId,
                sourceUri = uri.toString(),
                displayName = displayName,
                mimeType = mimeType ?: "application/octet-stream",
                sizeBytes = staged.file.length(),
                contentHash = staged.sha256,
                status = RagDocumentStatus.INDEXING
            )
            store.mutate { index ->
                index.copy(
                    documents = index.documents.filterNot { it.id == docId } + document,
                    chunks = index.chunks.filterNot { it.documentId == docId }
                )
            }
            setProgress(docId, 0.1f)
            try {
                val segments = extractor.extract(staged.file, mimeType, displayName)
                val drafts = DocumentChunker.chunk(segments)
                if (drafts.isEmpty()) {
                    throw RagExtractionException("No readable text was found in that document.")
                }
                var chunks = drafts.mapIndexed { i, draft ->
                    RagChunk(
                        id = "$docId:$i",
                        documentId = docId,
                        documentName = displayName,
                        chunkIndex = i,
                        pageRef = draft.pageRef,
                        text = draft.text,
                        tokenCount = PromptBudget.estimateTokens(draft.text)
                    )
                }
                setProgress(docId, 0.4f)
                val backend = runCatching { embeddingResolver.resolve() }.getOrNull()
                var embeddedModelId: String? = null
                if (backend != null) {
                    val vectors = runCatching {
                        val all = ArrayList<FloatArray>(chunks.size)
                        for (batch in chunks.chunked(OpenAiCompatibleEmbeddingBackend.BATCH_SIZE)) {
                            all += backend.embed(batch.map { it.text })
                            setProgress(
                                docId,
                                0.4f + 0.6f * (all.size.toFloat() / chunks.size.coerceAtLeast(1))
                            )
                        }
                        all
                    }.getOrNull()
                    if (vectors != null && vectors.size == chunks.size) {
                        chunks = chunks.mapIndexed { i, chunk ->
                            chunk.copy(
                                embedding = VectorMath.encode(vectors[i]),
                                embeddingModelId = backend.id
                            )
                        }
                        embeddedModelId = backend.id
                    }
                    // An embedding failure is NOT an import failure: the
                    // document is fully keyword-indexed either way.
                }
                val ready = document.copy(
                    status = RagDocumentStatus.READY,
                    errorMessage = null,
                    chunkCount = chunks.size,
                    indexedAt = System.currentTimeMillis(),
                    embeddingModelId = embeddedModelId
                )
                val finalChunks = chunks
                store.mutate { index ->
                    index.copy(
                        documents = index.documents.map { if (it.id == docId) ready else it },
                        chunks = index.chunks.filterNot { it.documentId == docId } + finalChunks
                    )
                }
            } catch (e: Exception) {
                val failed = document.copy(
                    status = RagDocumentStatus.ERROR,
                    errorMessage = e.message ?: "Indexing failed.",
                    chunkCount = 0
                )
                store.mutate { index ->
                    index.copy(
                        documents = index.documents.map { if (it.id == docId) failed else it },
                        chunks = index.chunks.filterNot { it.documentId == docId }
                    )
                }
            }
        } finally {
            staged.file.delete()
            progressDocId?.let { clearProgress(it) }
        }
    }

    private suspend fun recordFailedImport(
        sourceUri: String,
        displayName: String,
        mimeType: String?,
        message: String
    ) {
        val existing = store.index.value.documents.firstOrNull { it.sourceUri == sourceUri }
        val doc = RagDocument(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sourceUri = sourceUri,
            displayName = displayName,
            mimeType = mimeType ?: "application/octet-stream",
            sizeBytes = existing?.sizeBytes ?: 0L,
            contentHash = existing?.contentHash ?: "",
            status = RagDocumentStatus.ERROR,
            errorMessage = message,
            chunkCount = 0
        )
        store.mutate { index ->
            index.copy(
                documents = index.documents.filterNot { it.id == doc.id } + doc,
                chunks = index.chunks.filterNot { it.documentId == doc.id }
            )
        }
    }

    /** Deletes a document and all of its chunks, atomically. */
    suspend fun deleteDocument(documentId: String) {
        store.mutate { index ->
            index.copy(
                documents = index.documents.filterNot { it.id == documentId },
                chunks = index.chunks.filterNot { it.documentId == documentId }
            )
        }
        clearProgress(documentId)
    }

    // ── Retrieval ─────────────────────────────────────────────────────

    /**
     * Ranks the knowledge base against [query]. Semantic first when a
     * backend is configured (with stored-text backfill for chunks missing
     * current-model vectors); BM25 whenever semantic is unavailable, fails,
     * or finds nothing above its floor.
     */
    suspend fun retrieve(query: String, limit: Int = Bm25Index.DEFAULT_LIMIT): RetrievalOutcome {
        store.ensureLoaded()
        var chunks = store.index.value.chunks
        if (chunks.isEmpty()) return RetrievalOutcome(RetrievalMode.EMPTY, emptyList())

        val backend = runCatching { embeddingResolver.resolve() }.getOrNull()
        if (backend != null) {
            val semantic = runCatching {
                chunks = backfillEmbeddings(chunks, backend)
                val queryVector = backend.embed(listOf(query)).firstOrNull()
                if (queryVector == null) {
                    null
                } else {
                    SemanticRetriever.rank(chunks, queryVector, backend.id, limit)
                }
            }.getOrNull()
            if (!semantic.isNullOrEmpty()) {
                return RetrievalOutcome(RetrievalMode.SEMANTIC, semantic)
            }
        }
        val lexical = Bm25Index(chunks).search(query, limit)
        return RetrievalOutcome(RetrievalMode.LEXICAL, lexical)
    }

    /**
     * Embeds chunks that lack a vector for [backend]'s model, using their
     * stored text (documents never need re-importing when the embedding
     * model changes). Bounded per call; beyond the bound the caller simply
     * ranks lexically this time.
     */
    private suspend fun backfillEmbeddings(
        chunks: List<RagChunk>,
        backend: EmbeddingBackend
    ): List<RagChunk> {
        val missing = chunks.filter {
            it.embeddingModelId != backend.id || it.embedding == null
        }
        if (missing.isEmpty() || missing.size > MAX_BACKFILL_CHUNKS) return chunks
        val vectors = backend.embed(missing.map { it.text })
        if (vectors.size != missing.size) return chunks
        val byId = missing.mapIndexed { i, chunk ->
            chunk.id to chunk.copy(
                embedding = VectorMath.encode(vectors[i]),
                embeddingModelId = backend.id
            )
        }.toMap()
        val updated = chunks.map { byId[it.id] ?: it }
        store.mutate { index ->
            index.copy(
                chunks = index.chunks.map { stored ->
                    val fresh = byId[stored.id]
                    // Only fill still-missing vectors; never clobber a
                    // chunk another write has already embedded.
                    if (fresh != null && stored.embeddingModelId != backend.id) {
                        fresh
                    } else {
                        stored
                    }
                }
            )
        }
        return updated
    }

    // ── Answering ─────────────────────────────────────────────────────

    /**
     * Answers [question] from the knowledge base. When retrieval finds
     * nothing, returns [RagAnswerResult.NoMatch] WITHOUT calling the model
     * — the app must not dress up a guess as a document answer.
     */
    suspend fun answer(question: String): RagAnswerResult {
        val outcome = retrieve(question)
        if (outcome.hits.isEmpty()) return RagAnswerResult.NoMatch(outcome.mode)
        val provider = providerFactory.getActiveProvider()
        val response = provider.complete(
            LLMRequest(
                systemPrompt = GROUNDING_SYSTEM_PROMPT,
                messages = listOf(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = buildQuestionContent(question, outcome.hits),
                        sender = ChatMessage.Sender.USER
                    )
                ),
                temperature = 0.2f,
                maxTokens = 1500,
                responseFormat = ResponseFormat.TEXT
            )
        )
        return RagAnswerResult.Answered(response.content.trim(), outcome.hits, outcome.mode)
    }

    /**
     * The cited-context block Chat injects on a documents-scoped send
     * (feature D4's chat scope), or null when nothing relevant is indexed.
     */
    suspend fun buildChatContext(query: String): String? {
        val outcome = retrieve(query)
        if (outcome.hits.isEmpty()) return null
        return buildString {
            append("[Knowledge base context — excerpts from the user's own documents. ")
            append("Use them to answer where relevant and cite them as [1], [2], … ")
            append("If the answer is not in the excerpts, say it is not in the ")
            append("documents instead of guessing.]\n\n")
            append(formatCitations(outcome.hits))
        }
    }

    /** One-line description of the active retrieval path, for the UI. */
    suspend fun searchModeDescription(): String {
        val backend = runCatching { embeddingResolver.resolve() }.getOrNull()
        return if (backend != null) {
            "Semantic search · ${backend.providerName} (${backend.model})"
        } else {
            "Keyword search (BM25) · add an embedding provider's API key " +
                "(OpenAI, Gemini, Mistral, OpenRouter, Together) in Settings for semantic search"
        }
    }

    // ── Prompt assembly ───────────────────────────────────────────────

    private fun buildQuestionContent(question: String, hits: List<RetrievedChunk>): String =
        buildString {
            append("Document excerpts:\n\n")
            append(formatCitations(hits))
            append("\n\nQuestion: ")
            append(question)
        }

    /** The numbered citation block shared by the ask box and chat scope. */
    fun formatCitations(hits: List<RetrievedChunk>): String =
        hits.mapIndexed { i, hit ->
            val chunk = hit.chunk
            val where = if (chunk.pageRef != null) {
                "${chunk.documentName}, page ${chunk.pageRef}"
            } else {
                "${chunk.documentName}, chunk ${chunk.chunkIndex + 1}"
            }
            "[${i + 1}] $where:\n\"${chunk.text}\""
        }.joinToString("\n\n")

    // ── Staging ───────────────────────────────────────────────────────

    private data class StagedFile(val file: File, val sha256: String)

    /**
     * Copies the picked document into a cache temp file while hashing it.
     * Throws [RagExtractionException] past [MAX_IMPORT_BYTES] or on read
     * failure; the partial copy is removed.
     */
    private suspend fun stageToTempFile(uri: Uri): StagedFile = withContext(Dispatchers.IO) {
        val target = File(
            context.cacheDir,
            "rag-import-${UUID.randomUUID()}.tmp"
        )
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            throw RagExtractionException(
                                "That document is over the 32 MB import limit."
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: throw RagExtractionException("Couldn't read the picked file.")
            if (size == 0L) throw RagExtractionException("That file is empty.")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            StagedFile(target, hash)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun setProgress(docId: String, fraction: Float) {
        _indexingProgress.value = _indexingProgress.value + (docId to fraction)
    }

    private fun clearProgress(docId: String) {
        _indexingProgress.value = _indexingProgress.value - docId
    }

    companion object {
        /** Import cap: bounds the staged copy and the extraction memory. */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

        /** Per-retrieval cap on embedding backfill from stored chunk text. */
        const val MAX_BACKFILL_CHUNKS = 200

        const val GROUNDING_SYSTEM_PROMPT =
            "You answer questions using ONLY the document excerpts provided in " +
                "the user's message. Cite the excerpts inline as [1], [2], … for " +
                "every claim you take from them. If the excerpts do not contain " +
                "the answer, say plainly that you couldn't find it in the " +
                "documents. Never invent facts, numbers, or quotes that are not " +
                "in the excerpts."
    }
}
