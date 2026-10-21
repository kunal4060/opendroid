package com.opendroid.ai.core.rag

/** A run of extracted text that shares one citation anchor. */
data class TextSegment(
    val text: String,
    /** 1-based PDF page, or null when the source has no pages. */
    val pageRef: Int? = null
)

/** A chunk ready to be stored: text plus the page it belongs to. */
data class ChunkDraft(
    val text: String,
    val pageRef: Int? = null
)

/**
 * Paragraph/sentence-aware chunking for the RAG index (feature D4).
 *
 * Chunks are built per [TextSegment] and never span segments, so a chunk's
 * page reference is always exact. Within a segment, paragraphs are packed
 * greedily up to [MAX_CHUNK_CHARS]; an over-long paragraph falls back to
 * sentence packing, and an over-long sentence is hard-split at a word
 * boundary. Every chunk after the first in a segment starts with an overlap
 * tail of the previous chunk (up to [OVERLAP_CHARS]) so a fact that sits on
 * a boundary is retrievable from both sides. No chunk exceeds
 * [MAX_CHUNK_CHARS] — the overlap spends part of the next chunk's budget.
 *
 * Pure Kotlin: the same code runs in JVM unit tests and on device.
 */
object DocumentChunker {

    const val MAX_CHUNK_CHARS = 800
    const val OVERLAP_CHARS = 120

    fun chunk(
        segments: List<TextSegment>,
        maxChars: Int = MAX_CHUNK_CHARS,
        overlapChars: Int = OVERLAP_CHARS
    ): List<ChunkDraft> {
        require(maxChars > 0) { "maxChars must be positive" }
        require(overlapChars in 0 until maxChars) { "overlapChars must be in [0, maxChars)" }
        val result = mutableListOf<ChunkDraft>()
        for (segment in segments) {
            val bodies = chunkSegment(segment.text, maxChars, overlapChars)
            var previous: String? = null
            for (body in bodies) {
                val text = if (previous != null && overlapChars > 0) {
                    var tail = overlapTail(previous, overlapChars)
                    // The joining space spends one char: shrink the tail
                    // until tail + space + body fits the cap exactly.
                    while (tail.isNotEmpty() && tail.length + 1 + body.length > maxChars) {
                        tail = tail.substring(1)
                    }
                    if (tail.isEmpty()) body else "$tail $body"
                } else {
                    body
                }
                result += ChunkDraft(text = text, pageRef = segment.pageRef)
                previous = text
            }
        }
        return result
    }

    /** Splits one segment's text into chunk bodies (overlap applied by the caller). */
    private fun chunkSegment(text: String, maxChars: Int, overlapChars: Int): List<String> {
        val paragraphs = text
            .split(Regex("""\n\s*\n"""))
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        // First chunk of the segment gets the full budget; later chunks
        // reserve room for the overlap prefix.
        val bodies = mutableListOf<String>()
        val current = StringBuilder()
        var budget = maxChars
        fun flush() {
            if (current.isNotEmpty()) {
                bodies += current.toString()
                current.clear()
                budget = maxChars - overlapChars
            }
        }
        for (paragraph in paragraphs) {
            if (paragraph.length <= budget) {
                if (current.isNotEmpty() && current.length + 1 + paragraph.length > budget) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(paragraph)
            } else {
                // Over-long paragraph: pack its sentences the same way.
                flush()
                for (piece in splitLongText(paragraph, maxChars - overlapChars)) {
                    if (current.isNotEmpty() && current.length + 1 + piece.length > budget) flush()
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(piece)
                }
            }
        }
        flush()
        return bodies
    }

    /**
     * Splits [text] (longer than [maxChars]) into pieces of at most
     * [maxChars]: sentence packing first, hard word-boundary splits for
     * sentences that are still too long.
     */
    private fun splitLongText(text: String, maxChars: Int): List<String> {
        val sentences = text
            .split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                pieces += current.toString()
                current.clear()
            }
        }
        for (sentence in sentences) {
            if (sentence.length <= maxChars) {
                if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            } else {
                flush()
                pieces += hardSplit(sentence, maxChars)
            }
        }
        flush()
        return pieces
    }

    /** Splits at the last space at or before [maxChars]; mid-word when no space exists. */
    private fun hardSplit(text: String, maxChars: Int): List<String> {
        val pieces = mutableListOf<String>()
        var rest = text
        while (rest.length > maxChars) {
            val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    /**
     * The trailing [maxChars] of [previous], cut forward to the first word
     * boundary so the overlap never starts mid-word.
     */
    private fun overlapTail(previous: String, maxChars: Int): String {
        if (previous.length <= maxChars) return previous
        val window = previous.substring(previous.length - maxChars)
        val firstSpace = window.indexOf(' ')
        return if (firstSpace in 0 until window.length - 1) {
            window.substring(firstSpace + 1)
        } else {
            window
        }
    }
}
