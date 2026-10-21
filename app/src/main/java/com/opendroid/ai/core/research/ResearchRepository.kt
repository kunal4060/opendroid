package com.opendroid.ai.core.research

import com.opendroid.ai.data.db.dao.ResearchDao
import com.opendroid.ai.data.db.entities.ResearchSourceEntity
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write access to stored research citations (feature D3) for the UI
 * layer, plus the pure resolution of "which stored sources does this chat
 * message cite".
 */
@Singleton
class ResearchRepository @Inject constructor(
    private val researchDao: ResearchDao
) {
    /** Every stored source; the chat layer groups them per message. */
    val allSources: Flow<List<ResearchSourceEntity>> = researchDao.getAllSources()

    fun runsForSession(sessionId: String) = researchDao.getRunsForSession(sessionId)

    /** Every run; the chat layer filters to the visible session before resolving. */
    val allRuns: Flow<List<com.opendroid.ai.data.db.entities.ResearchRunEntity>> =
        researchDao.getAllRuns()

    suspend fun deleteSourcesForMessage(messageId: String) =
        researchDao.deleteSourcesForMessage(messageId)
}

/**
 * Resolves, for each message in a chat, the stored sources its citations
 * refer to. Two deterministic links, tried in order:
 *
 *  1. **Direct** — sources stored under the message's own id (composer
 *     research mode knows the reply id before the run starts).
 *  2. **Run containment** — a `WEB_RESEARCH` plan step runs before the
 *     plan's summary message exists, so its sources live under the run id;
 *     the summary copies step results verbatim, so the message in this
 *     session whose text contains the run's validated answer text is the
 *     message the answer landed in.
 *
 * A message with no link gets no sources — the UI then renders its text
 * with no citation links and no source list, exactly like a normal reply.
 */
object ResearchSourceResolver {

    /** Containment matching needs a distinctive answer, not a fragment. */
    private const val MIN_CONTAINMENT_LENGTH = 30

    fun resolve(
        messages: List<ChatMessage>,
        sources: List<ResearchSourceEntity>,
        runsAnswerTexts: Map<String, String> // run messageId -> validated answer text
    ): Map<String, List<ResearchSourceEntity>> {
        if (sources.isEmpty()) return emptyMap()
        val byMessageId = sources.groupBy { it.messageId }
        val out = mutableMapOf<String, List<ResearchSourceEntity>>()
        for (message in messages) {
            if (message.sender != ChatMessage.Sender.AGENT) continue
            val direct = byMessageId[message.id]
            if (!direct.isNullOrEmpty()) {
                out[message.id] = direct.sortedBy { it.position }
                continue
            }
            // Attach the UNION of every run whose answer this message
            // contains: a plan can run several WEB_RESEARCH steps into
            // one summary message, and first-match-only left the later
            // runs' [n] markers unlinked and their sources dropped.
            val viaRuns = runsAnswerTexts.entries
                .filter { (_, answerText) ->
                    answerText.length >= MIN_CONTAINMENT_LENGTH && message.text.contains(answerText)
                }
                .flatMap { (runId, _) -> byMessageId[runId].orEmpty() }
                .distinctBy { it.id }
            if (viaRuns.isNotEmpty()) {
                out[message.id] = viaRuns.sortedBy { it.position }
            }
        }
        return out
    }
}
