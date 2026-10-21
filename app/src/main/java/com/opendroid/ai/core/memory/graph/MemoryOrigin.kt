package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.Serializable

/**
 * How a knowledge-graph memory came to exist. Recorded at creation time by
 * whichever [PersonalGrowthEngine] funnel created the node, so the Memory
 * screen can answer "Why do you know this?" from the record itself instead
 * of inventing an explanation after the fact.
 */
@Serializable
enum class OriginKind {
    /** The user asked the agent to remember it (chat command, agent action, approved routine). */
    USER_EXPLICIT,

    /** Automatically extracted from chat messages by `MemoryExtractor`. */
    CHAT_EXTRACTION,

    /** Inferred from observed app usage (Level 3 pattern). */
    APP_USAGE,

    /** Inferred from calls/messages with a contact (Level 3 pattern). */
    CONTACT_INTERACTION,

    /** Inferred from visited websites (Level 3 pattern). */
    WEBSITE_VISIT,

    /** Inferred from recurring tasks/routines (Level 3 pattern). */
    TASK_PATTERN,

    /** Inferred from a recurring schedule (Level 3 pattern). */
    SCHEDULE_PATTERN,

    /** Typed directly into the Memory screen by the user. */
    USER_ENTERED,

    /** Restored from a backup import. */
    IMPORTED
}

/**
 * The stored provenance of a [KnowledgeNode].
 *
 * @param kind which creation path produced the memory.
 * @param detail path-specific context (e.g. interaction channel for contacts,
 *   action category for app usage, domain for website visits). Never embeds
 *   message text.
 * @param sessionId originating chat session, for [OriginKind.CHAT_EXTRACTION].
 * @param createdAt when the memory was first learned (epoch millis). Unlike
 *   [KnowledgeNode.lastUpdated], this never advances when a pattern is
 *   observed again — re-observation preserves the original origin.
 */
@Serializable
data class MemoryOrigin(
    val kind: OriginKind,
    val detail: String = "",
    val sessionId: String? = null,
    val createdAt: Long = 0L
)
