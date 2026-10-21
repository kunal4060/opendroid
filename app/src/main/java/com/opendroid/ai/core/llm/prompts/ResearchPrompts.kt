package com.opendroid.ai.core.llm.prompts

/**
 * Prompts for web research (feature D3). The grounded-answer contract is
 * strict on purpose: the model may only use the numbered source blocks it
 * is handed, and every factual claim must carry the `[n]` marker of the
 * source it came from. Markers are not trusted — the pipeline validates
 * them against the stored source rows and strips any that don't resolve —
 * but this prompt is what makes valid citations the norm rather than the
 * exception.
 */
object ResearchPrompts {

    const val GROUNDED_ANSWER_SYSTEM: String = """
You are OpenDroid's research mode. You answer the user's question using ONLY the numbered web sources provided in the conversation. Those sources were fetched live for this question; they are your entire evidence base.

Rules:
1. Every factual claim in your answer must end with the citation marker of the source it comes from, written exactly like [1] or [2] — the number of the source block the claim is supported by. One marker per claim is enough; a claim supported by two sources may carry both, e.g. [1][3].
2. Use ONLY facts that appear in the provided sources. Do not add facts from your own knowledge, do not guess, and do not cite a source for a claim it does not support.
3. If the sources do not cover part of the question, say plainly that the retrieved sources don't cover it — never fill the gap from memory.
4. Never invent a citation number: the only valid markers are the numbers of the source blocks you were given.
5. Match the user's language. Be direct and specific; quote figures, names and dates exactly as the sources give them.
6. Do not add a bibliography or source list at the end — the app renders the sources under your answer automatically.
"""

    /** One source block as presented to the model. [position] is the citation number. */
    fun sourceBlock(position: Int, title: String, url: String, text: String): String =
        "[$position] $title\nURL: $url\n---\n$text"

    /** The user-side message: the question plus every numbered source block. */
    fun groundedUserMessage(question: String, blocks: List<String>): String = buildString {
        append("Question: ")
        append(question)
        append("\n\nSources fetched for this question:\n\n")
        append(blocks.joinToString("\n\n"))
    }
}
