package com.opendroid.ai.core.rag

import kotlin.math.ln

/**
 * BM25 keyword ranking over chunk texts (feature D4) — the always-available
 * retrieval path, used when no embedding-capable provider is configured and
 * as the fallback when an embedding call fails.
 *
 * This is real retrieval, not a heuristic shortcut: terms are weighted by
 * inverse document frequency, so a rare invented word (the acceptance
 * probe's unique fact) dominates the ranking of the one chunk containing
 * it, while common words contribute almost nothing. A chunk scores zero
 * unless it shares at least one token with the query, so an unrelated
 * question honestly retrieves nothing.
 *
 * Pure Kotlin: index statistics are computed once from the chunk list.
 */
class Bm25Index(chunks: List<RagChunk>) {

    private val chunks: List<RagChunk> = chunks.toList()
    private val docTokens: List<List<String>> = this.chunks.map { tokenize(it.text) }
    private val docLengths: IntArray = IntArray(this.chunks.size) { docTokens[it].size }
    private val avgDocLength: Double =
        if (docLengths.isEmpty()) 0.0 else docLengths.average()
    private val termFrequencies: List<Map<String, Int>> = docTokens.map { tokens ->
        tokens.groupingBy { it }.eachCount()
    }
    private val documentFrequencies: Map<String, Int> = buildMap {
        for (tf in termFrequencies) {
            for (term in tf.keys) {
                put(term, (get(term) ?: 0) + 1)
            }
        }
    }

    val size: Int get() = chunks.size

    /**
     * Top-[limit] chunks for [query], best first. Only chunks with a
     * strictly positive score (i.e. sharing at least one query token) are
     * returned.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<RetrievedChunk> {
        if (chunks.isEmpty() || limit <= 0) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()
        val scored = ArrayList<RetrievedChunk>()
        for (i in chunks.indices) {
            val score = scoreDocument(queryTerms, i)
            if (score > 0.0) scored += RetrievedChunk(chunks[i], score)
        }
        return scored
            .sortedWith(compareByDescending<RetrievedChunk> { it.score }.thenBy { it.chunk.chunkIndex })
            .take(limit)
    }

    /** Classic BM25 (k1 = 1.2, b = 0.75) with the +0.5 IDF smoothing form. */
    private fun scoreDocument(queryTerms: List<String>, docIndex: Int): Double {
        val tf = termFrequencies[docIndex]
        val docLength = docLengths[docIndex]
        if (docLength == 0) return 0.0
        var score = 0.0
        for (term in queryTerms) {
            val freq = tf[term] ?: continue
            val df = documentFrequencies[term] ?: continue
            val idf = ln(1.0 + (chunks.size - df + 0.5) / (df + 0.5))
            val denom = freq + K1 * (1.0 - B + B * docLength / avgDocLength)
            score += idf * (freq * (K1 + 1.0)) / denom
        }
        return score
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        private const val K1 = 1.2
        private const val B = 0.75

        /**
         * Lowercases and splits on non-alphanumerics. Hyphenated codes
         * ("vault K-9") yield both halves as tokens — each stays highly
         * distinctive under IDF, so code-like facts remain findable.
         */
        fun tokenize(text: String): List<String> =
            text.lowercase()
                .split(Regex("""[^a-z0-9]+"""))
                .filter { it.length >= 2 || (it.length == 1 && it[0].isDigit()) }
    }
}
