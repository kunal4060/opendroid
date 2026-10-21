package com.opendroid.ai.core.llm

/**
 * Token budgeting for on-device models with small, fixed context windows.
 *
 * LiteRT-LM engines abort natively (SIGABRT in liblitertlm_jni) when the prompt
 * exceeds the engine's `maxNumTokens` / the model's KV-cache size — that kills
 * the whole process as a force close, with no catchable exception. This object
 * lets providers check the prompt against the model's context window *before*
 * handing it to the native runtime, so oversized prompts surface as a normal
 * error message instead of a crash.
 */
object PromptBudget {

    /** Rough chars-per-token heuristic; deliberately conservative for safety. */
    private const val CHARS_PER_TOKEN = 4

    /** Smallest output allocation worth running inference for. */
    const val MIN_OUTPUT_TOKENS = 64

    /**
     * Estimates the token count of [text], rounding up.
     *
     * Latin-style text keeps the chars/[CHARS_PER_TOKEN] heuristic, but
     * scripts whose characters tokenize at roughly one token each (CJK
     * ideographs/kana/hangul, fullwidth forms) and emoji are counted per
     * code point: a flat chars/4 underestimates them by up to ~4×, which
     * let oversized prompts slip past the pre-native overflow guard this
     * estimate feeds.
     */
    fun estimateTokens(text: String): Int {
        var denseCodePoints = 0
        var otherChars = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (isDenseScript(codePoint)) denseCodePoints++ else otherChars++
            index += Character.charCount(codePoint)
        }
        return denseCodePoints + (otherChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    /** Code-point ranges that tokenize at ~1 token per character. */
    private fun isDenseScript(codePoint: Int): Boolean = when (codePoint) {
        in 0x1100..0x11FF,   // Hangul Jamo
        in 0x2E80..0x9FFF,   // CJK radicals/supplements, kana, CJK ideographs
        in 0xA960..0xA97F,   // Hangul Jamo Extended-A
        in 0xAC00..0xD7A3,   // Hangul syllables
        in 0xF900..0xFAFF,   // CJK compatibility ideographs
        in 0xFE30..0xFE4F,   // CJK compatibility forms
        in 0xFF00..0xFF60,   // Fullwidth forms
        in 0x1F000..0x1FAFF, // Emoji and pictographs
        in 0x20000..0x2FA1F  // CJK extensions B and later
        -> true
        else -> false
    }

    /**
     * Returns how many output tokens a request may use once [prompt] is loaded
     * into a [contextWindow]-token context: [requestedOutputTokens] when there is
     * room, clamped down to the remaining space otherwise, or `null` when fewer
     * than [MIN_OUTPUT_TOKENS] would remain — i.e. the prompt is too long to run.
     */
    fun outputBudget(prompt: String, contextWindow: Int, requestedOutputTokens: Int): Int? {
        val remaining = contextWindow - estimateTokens(prompt)
        if (remaining < MIN_OUTPUT_TOKENS) return null
        return minOf(requestedOutputTokens, remaining)
    }
}
