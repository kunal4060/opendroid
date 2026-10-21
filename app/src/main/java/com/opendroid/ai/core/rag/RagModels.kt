package com.opendroid.ai.core.rag

import kotlinx.serialization.Serializable

/**
 * Document RAG (feature D4): a local knowledge base over user documents.
 *
 * Documents are imported through the Storage Access Framework, extracted to
 * text, split into overlapping chunks, and indexed in app-private storage
 * (a JSON file under `filesDir/rag` — deliberately NOT Room, so the feature
 * ships without a schema migration). Retrieval is real on both paths:
 * semantic (provider embeddings + brute-force cosine) when an embedding
 * backend is configured, lexical (BM25) otherwise.
 */

enum class RagDocumentStatus {
    /** Extraction / chunking / embedding is in flight for this document. */
    INDEXING,

    /** Chunks are stored and retrievable. */
    READY,

    /** Import failed visibly; [RagDocument.errorMessage] says why. */
    ERROR
}

@Serializable
data class RagDocument(
    val id: String,
    /** The SAF URI the document was imported from (persisted grant). */
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** SHA-256 of the source bytes; unchanged re-imports are skipped. */
    val contentHash: String,
    val status: RagDocumentStatus,
    val errorMessage: String? = null,
    val chunkCount: Int = 0,
    val indexedAt: Long = 0L,
    /**
     * The embedding backend id ("provider|model") whose vectors this
     * document's chunks carry, or null when the document is keyword-indexed
     * only. Mixed states are possible per chunk; this is the last backend
     * that embedded the document as a whole.
     */
    val embeddingModelId: String? = null
)

@Serializable
data class RagChunk(
    val id: String,
    val documentId: String,
    /** Denormalised so retrieval results can cite without a join. */
    val documentName: String,
    /** Zero-based position of this chunk inside its document. */
    val chunkIndex: Int,
    /** 1-based PDF page the text came from; null for plain-text sources. */
    val pageRef: Int? = null,
    val text: String,
    val tokenCount: Int = 0,
    /** Float32 vector, little-endian, Base64 — null when not embedded. */
    val embedding: String? = null,
    /** Backend id that produced [embedding]; guards against mixing spaces. */
    val embeddingModelId: String? = null
) {
    /** Human citation label, e.g. "Invoice.pdf, page 2" / "Notes.md, chunk 4". */
    fun citationLabel(): String =
        if (pageRef != null) "$documentName, page $pageRef"
        else "$documentName, chunk ${chunkIndex + 1}"
}

/** The whole persisted index: documents plus every chunk of every document. */
@Serializable
data class RagIndex(
    val documents: List<RagDocument> = emptyList(),
    val chunks: List<RagChunk> = emptyList()
) {
    fun chunksOf(documentId: String): List<RagChunk> =
        chunks.filter { it.documentId == documentId }
}

/** One retrieval hit: the chunk plus the score its ranking path assigned. */
data class RetrievedChunk(
    val chunk: RagChunk,
    val score: Double
)

/** Which ranking path produced a retrieval outcome. */
enum class RetrievalMode {
    /** Provider embeddings + cosine similarity. */
    SEMANTIC,

    /** Local BM25 keyword ranking. */
    LEXICAL,

    /** The knowledge base holds no chunks at all. */
    EMPTY
}

data class RetrievalOutcome(
    val mode: RetrievalMode,
    val hits: List<RetrievedChunk>
)

/** Result of answering a question from the knowledge base. */
sealed interface RagAnswerResult {
    /** The configured chat model answered, grounded in [citations]. */
    data class Answered(
        val text: String,
        val citations: List<RetrievedChunk>,
        val mode: RetrievalMode
    ) : RagAnswerResult

    /**
     * Retrieval found nothing relevant. No model was called — the honest
     * answer is that the fact is not in the user's documents.
     */
    data class NoMatch(val mode: RetrievalMode) : RagAnswerResult
}
