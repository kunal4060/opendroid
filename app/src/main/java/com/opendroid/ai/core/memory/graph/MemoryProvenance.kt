package com.opendroid.ai.core.memory.graph

/**
 * Builds the plain-language "Why do you know this?" answer for a memory from
 * its **stored** [MemoryOrigin] — never from a generated guess.
 *
 * Honesty rules (checklist A7):
 * - A node whose origin was never recorded says exactly that
 *   ([NOT_RECORDED_HEADLINE]).
 * - The only inference allowed is one that is *derivable, not invented*:
 *   explicit-memory node ids embed their creation timestamp
 *   (`explicit_<label>_<millis>`), so an approximate save date may be shown
 *   and is labelled as derived.
 */
object MemoryProvenance {

    const val NOT_RECORDED_HEADLINE =
        "Origin not recorded — this memory was created before provenance tracking, so I can't say exactly where it came from."

    /** Sanity window for timestamps derived from node ids (2015-01-01 .. 2100-01-01). */
    private const val MIN_PLAUSIBLE_MILLIS = 1_420_070_400_000L
    private const val MAX_PLAUSIBLE_MILLIS = 4_102_444_800_000L

    /**
     * Headline sentence for [node]. [formatDate] renders epoch millis in the
     * caller's locale/format so this object stays pure and JVM-testable.
     */
    fun headline(node: KnowledgeNode, formatDate: (Long) -> String): String {
        val origin = node.origin ?: return notRecordedHeadline(node, formatDate)
        val date = if (origin.createdAt > 0L) formatDate(origin.createdAt) else null
        return when (origin.kind) {
            OriginKind.USER_EXPLICIT ->
                if (date != null) "You asked me to remember this on $date."
                else "You asked me to remember this."

            OriginKind.CHAT_EXTRACTION ->
                if (date != null) "I learned this from your chat on $date."
                else "I learned this from your chat messages."

            OriginKind.APP_USAGE -> buildString {
                append("I noticed this from your app usage")
                if (origin.detail.isNotBlank()) append(" (${origin.detail})")
                append(observedSuffix(node))
                append(".")
            }

            OriginKind.CONTACT_INTERACTION -> buildString {
                append("I noticed this from your contact interactions")
                if (origin.detail.isNotBlank()) append(" via ${origin.detail}")
                append(observedSuffix(node))
                append(".")
            }

            OriginKind.WEBSITE_VISIT -> buildString {
                append("I noticed this from websites you visit")
                if (origin.detail.isNotBlank()) append(" (${origin.detail})")
                append(observedSuffix(node))
                append(".")
            }

            OriginKind.TASK_PATTERN -> buildString {
                append("I noticed this pattern in your tasks and routines")
                if (origin.detail.isNotBlank()) append(" (${origin.detail})")
                append(observedSuffix(node))
                append(".")
            }

            OriginKind.SCHEDULE_PATTERN -> buildString {
                append("I noticed this in your schedule")
                if (origin.detail.isNotBlank()) append(" (${origin.detail})")
                append(observedSuffix(node))
                append(".")
            }

            OriginKind.USER_ENTERED ->
                if (date != null) "You added this yourself in the Memory screen on $date."
                else "You added this yourself in the Memory screen."

            OriginKind.IMPORTED -> buildString {
                append("This was restored from a backup")
                if (date != null) append(" on $date")
                if (origin.detail.isNotBlank()) append(" (${origin.detail})")
                append(".")
            }

            OriginKind.UNKNOWN -> buildString {
                append("This memory's origin was recorded in a form this version of the app doesn't recognise")
                if (date != null) append(" (recorded $date)")
                append(", so I can't say exactly where it came from.")
            }
        }
    }

    private fun notRecordedHeadline(node: KnowledgeNode, formatDate: (Long) -> String): String {
        val derived = derivedCreatedAtMillis(node.id)
        return if (derived != null) {
            "$NOT_RECORDED_HEADLINE It was saved around ${formatDate(derived)} (date derived from its record id)."
        } else {
            NOT_RECORDED_HEADLINE
        }
    }

    private fun observedSuffix(node: KnowledgeNode): String =
        if (node.accessCount > 1) " — observed ${node.accessCount} times" else ""

    /**
     * Explicit-memory node ids are minted as `explicit_<label>_<millis>`
     * (see [PersonalGrowthEngine.recordExplicitMemory]). Returns the embedded
     * creation time when present and plausible, else null. Never used for any
     * other claim than an approximate "saved around" date.
     */
    fun derivedCreatedAtMillis(nodeId: String): Long? {
        if (!nodeId.startsWith("explicit_")) return null
        val millis = nodeId.substringAfterLast('_').toLongOrNull() ?: return null
        return millis.takeIf { it in MIN_PLAUSIBLE_MILLIS..MAX_PLAUSIBLE_MILLIS }
    }
}
