package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One web source actually retrieved during a research run (feature D3).
 *
 * This row is the citation: the answer text's `[position]` markers mean
 * nothing on their own — they resolve to rows of this table, written by
 * [com.opendroid.ai.core.research.ResearchPipeline] from the search/fetch
 * responses of THIS run before the answer is shown. A marker that has no
 * row here is never rendered as a link (and the pipeline strips such
 * markers from the answer before persisting it), so a citation can never
 * point at a page the app did not fetch — citations are true by
 * construction, not by trusting the model.
 *
 * [messageId] is the assistant [com.opendroid.ai.data.models.ChatMessage]
 * the answer landed in. In composer research mode the pipeline knows that
 * id up front; on the planner-action path it is the research run's id and
 * the link to the summary message is recorded in
 * [ResearchRunEntity] instead (same table family, see its doc).
 *
 * [contentHash] is the SHA-256 of the exact source text that was placed
 * in the model's context for this source (fetched page text, or the
 * search snippet when the fetch failed) — it makes a citation auditable:
 * what the model saw is provably what was retrieved in this run.
 */
@Entity(
    tableName = "research_sources",
    indices = [Index(value = ["messageId"])]
)
data class ResearchSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    /** The 1-based `[n]` number shown to the user for this source. */
    val position: Int,
    val url: String,
    val title: String,
    val snippet: String,
    /** Epoch millis when this source was retrieved in the run. */
    val fetchedAt: Long,
    val contentHash: String
)

/**
 * One research run (feature D3): the bookkeeping that ties a set of
 * [ResearchSourceEntity] rows to the chat it served.
 *
 * Composer research mode knows the assistant message's id before the run
 * starts, so [messageId] is that id and sources resolve directly. A
 * `WEB_RESEARCH` plan step runs before the plan's summary message exists,
 * so there [messageId] is the run id itself and the summary message is
 * found afterwards by exact containment: the plan summary copies step
 * results verbatim, so the message whose text contains [answerText] in
 * this session is the message this run's answer landed in. Both links are
 * deterministic — no timestamp guessing.
 */
@Entity(tableName = "research_runs")
data class ResearchRunEntity(
    @PrimaryKey val runId: String,
    val sessionId: String,
    val messageId: String,
    val query: String,
    /** The validated answer text exactly as returned to the caller. */
    val answerText: String,
    val createdAt: Long
)
