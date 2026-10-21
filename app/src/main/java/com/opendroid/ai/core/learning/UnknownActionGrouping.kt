package com.opendroid.ai.core.learning

import com.opendroid.ai.data.db.entities.UnknownActionEntity

/**
 * One review-queue row: every recorded occurrence of the same invented verb,
 * collapsed together (B5). Grouping key is the normalised verb, so
 * "send-whatsapp" and "SEND_WHATSAPP" land in one group.
 */
data class UnknownActionGroup(
    /** Normalised verb — also the learned-mapping key. */
    val key: String,
    /** The raw attempted string of the most recent occurrence, as the model wrote it. */
    val displayAction: String,
    val occurrences: Int,
    val lastSeen: Long,
    /** Goal of the most recent occurrence that recorded one ("" when none did). */
    val sampleGoal: String,
    /** Occurrence counts per stored fixStatus ("AUTO_FIXED" / "REPLANNED" / "FAILED"). */
    val fixStatusCounts: Map<String, Int>,
    /**
     * The action that eventually worked for this verb, when a recorded
     * occurrence was auto-fixed with a real action name. Used to pre-select
     * the editor's action picker; null when nothing sensible was recorded.
     */
    val suggestedTarget: String?,
    /** Most recent occurrences, newest first (capped for display). */
    val recent: List<UnknownActionEntity>
)

/** Pure grouping over raw [UnknownActionEntity] rows — no Android, no DB. */
object UnknownActionGrouping {

    private const val MAX_RECENT = 5

    /** Values of `fixedWith` that describe an outcome, not an action to suggest. */
    private val nonActionFixedWith = setOf("SKIP", "CHAT")

    fun group(rows: List<UnknownActionEntity>): List<UnknownActionGroup> {
        if (rows.isEmpty()) return emptyList()
        return rows
            .groupBy { normalizeLearnedActionKey(it.attemptedAction) }
            .map { (key, occurrences) ->
                val newestFirst = occurrences.sortedByDescending { it.timestamp }
                val latest = newestFirst.first()
                val suggested = newestFirst.firstNotNullOfOrNull { row ->
                    row.fixedWith
                        ?.takeIf { it.isNotBlank() && it.uppercase() !in nonActionFixedWith }
                        ?.let { normalizeLearnedActionKey(it) }
                }
                UnknownActionGroup(
                    key = key,
                    displayAction = latest.attemptedAction,
                    occurrences = occurrences.size,
                    lastSeen = latest.timestamp,
                    sampleGoal = newestFirst.firstOrNull { it.goal.isNotBlank() }?.goal.orEmpty(),
                    fixStatusCounts = occurrences.groupingBy { it.fixStatus }.eachCount(),
                    suggestedTarget = suggested,
                    recent = newestFirst.take(MAX_RECENT)
                )
            }
            .sortedByDescending { it.lastSeen }
    }
}
