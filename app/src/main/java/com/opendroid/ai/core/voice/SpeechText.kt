package com.opendroid.ai.core.voice

/**
 * Text preparation for read-aloud (message actions → [TextToSpeechEngine]).
 *
 * Pure functions, no Android dependencies: [stripMarkdown] removes the
 * markdown syntax that a TTS engine would otherwise read out literally
 * ("asterisk asterisk bold asterisk asterisk"), and [chunkForSpeech] splits
 * the result into sentence-sized chunks, because the engine flushes its
 * queue on every `speak()` call and platform TTS truncates very long single
 * utterances — the caller feeds chunks one at a time, advancing on each
 * utterance completion.
 */
object SpeechText {

    /**
     * Converts markdown to speakable plain text: fenced code blocks lose
     * their fences (the code itself is kept — the user asked to hear the
     * message), links keep their link text, images drop out entirely, and
     * emphasis/heading/list markers are removed.
     */
    fun stripMarkdown(text: String): String {
        var result = text
        // Fence lines (```lang / ``` / ~~~) — drop the whole line including
        // its newline (so no blank gap remains), keep the code itself.
        result = result.replace(Regex("(?m)^\\s*(```|~~~)[a-zA-Z0-9_+.-]*\\s*$\\n?"), "")
        // Images carry no speakable text.
        result = result.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        // Links: keep the visible text, drop the URL.
        result = result.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        result = result.replace(Regex("\\[([^\\]]+)\\]\\[[^\\]]*\\]"), "$1")
        // Heading markers.
        result = result.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        // Blockquote markers.
        result = result.replace(Regex("(?m)^\\s*>\\s?"), "")
        // List markers (bullets and numbered).
        result = result.replace(Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+"), "")
        // Table separator rows (|---|---|) — drop the whole line.
        result = result.replace(Regex("(?m)^\\s*\\|?[\\s:|-]+\\|\\s*$"), "")
        // Table cell separators become pauses.
        result = result.replace("|", " ")
        // Emphasis markers: strong first, then any remaining asterisks and
        // backticks (inline code). Underscores are left alone — stripping
        // them would mangle snake_case identifiers.
        result = result.replace("**", "").replace("__", "")
        result = result.replace("*", "").replace("`", "")
        // Tidy whitespace left behind by the removals.
        result = result.replace(Regex("[ \\t]{2,}"), " ")
        result = result.replace(Regex("\\n{3,}"), "\n\n")
        return result.trim()
    }

    /**
     * Splits [text] into chunks of at most [maxChars], breaking on sentence
     * boundaries where possible. A single sentence longer than [maxChars] is
     * split on word boundaries; a single word longer than that is hard-split.
     * Blank input yields an empty list.
     */
    fun chunkForSpeech(text: String, maxChars: Int = 800): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val sentences = splitSentences(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                chunks += current.toString().trim()
                current.clear()
            }
        }
        for (sentence in sentences) {
            when {
                sentence.length <= maxChars -> {
                    if (current.length + sentence.length + 1 > maxChars) flush()
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                }
                else -> {
                    flush()
                    chunks += splitLongSentence(sentence, maxChars)
                }
            }
        }
        flush()
        return chunks
    }

    /** Splits into sentences on ., !, ? or line breaks, keeping punctuation. */
    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            val endsSentence = (c == '.' || c == '!' || c == '?') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            if (c == '\n' || endsSentence) {
                val piece = current.toString().trim()
                if (piece.isNotEmpty()) out += piece
                current.clear()
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    /** Word-boundary split for a sentence that alone exceeds [maxChars]. */
    private fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = sentence.trim()
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
