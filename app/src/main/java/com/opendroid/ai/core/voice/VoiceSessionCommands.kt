package com.opendroid.ai.core.voice

/**
 * End-phrase parser for continuous voice conversation (D2), a sibling to
 * [VoiceApprovalParser]. While the session is idle-listening, a short
 * utterance that is *only* an end phrase closes the session politely
 * ("stop", "that's all", "goodbye").
 *
 * Deliberately strict: the whole normalised utterance must be the end
 * phrase (optionally wrapped in "please" / "thanks"), so a real request
 * like "stop the timer" or "cancel my 3pm meeting" is submitted to the
 * agent as a query, never mistaken for a session command. Matching here
 * can only *end* a conversation — it can never approve or execute
 * anything — so strictness costs nothing and misfires are merely an
 * early (and announced) session end.
 */
object VoiceSessionCommands {

    private val endPhrasePattern = Regex(
        """^(please\s+)?(stop|cancel|that'?s all|that is all|good ?bye|bye|never ?mind|end session|i'?m done|we'?re done|done)(\s+(thanks|thank you))?$""",
        RegexOption.IGNORE_CASE
    )

    /** True when [utterance] is nothing but a session-ending phrase. */
    fun isEndPhrase(utterance: String): Boolean {
        val normalised = utterance
            .trim()
            .lowercase()
            // Punctuation anywhere is wrapper noise ("that's all, thanks"):
            // strip it all, not just trailing — the strict whole-utterance
            // pattern still keeps real requests from matching.
            .replace(Regex("""[.!?,]+"""), "")
            .replace(Regex("""\s+"""), " ")
        if (normalised.isEmpty()) return false
        return endPhrasePattern.matches(normalised)
    }
}
