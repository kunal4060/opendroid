package com.opendroid.ai.core.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/**
 * Vector helpers for the semantic retrieval path (feature D4).
 *
 * Vectors persist as Base64 of little-endian float32 bytes — compact enough
 * for a JSON index file at personal scale and exact (no decimal rounding).
 * `java.util.Base64` is used (not `android.util.Base64`) so the same code
 * runs in JVM unit tests; it exists on Android from API 26, the app's
 * minSdk.
 */
object VectorMath {

    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vector dimensions differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    fun encode(vector: FloatArray): String {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decode(encoded: String): FloatArray {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size % 4 == 0) { "Encoded vector is not a whole number of floats" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat(it * 4) }
    }
}

/**
 * Brute-force cosine ranking over stored chunk vectors (feature D4).
 *
 * Personal corpora (tens of documents, thousands of chunks) make brute
 * force entirely adequate — 10k × 768-dim cosines are tens of milliseconds
 * on `Dispatchers.Default`. Only chunks carrying a vector from
 * [queryModelId] participate: vectors from a different embedding model live
 * in a different space and are never compared. A similarity floor keeps
 * unrelated questions from retrieving noise.
 */
object SemanticRetriever {

    /** Minimum cosine for a chunk to count as relevant. */
    const val SIMILARITY_FLOOR = 0.20

    fun rank(
        chunks: List<RagChunk>,
        queryVector: FloatArray,
        queryModelId: String,
        limit: Int = Bm25Index.DEFAULT_LIMIT,
        floor: Double = SIMILARITY_FLOOR
    ): List<RetrievedChunk> {
        val hits = ArrayList<RetrievedChunk>()
        for (chunk in chunks) {
            if (chunk.embeddingModelId != queryModelId) continue
            val encoded = chunk.embedding ?: continue
            val vector = runCatching { VectorMath.decode(encoded) }.getOrNull() ?: continue
            if (vector.size != queryVector.size) continue
            val score = VectorMath.cosine(queryVector, vector)
            if (score >= floor) hits += RetrievedChunk(chunk, score)
        }
        return hits
            .sortedWith(compareByDescending<RetrievedChunk> { it.score }.thenBy { it.chunk.chunkIndex })
            .take(limit)
    }
}
