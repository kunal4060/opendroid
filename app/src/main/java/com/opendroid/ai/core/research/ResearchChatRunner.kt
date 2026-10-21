package com.opendroid.ai.core.research

import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.ConversationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one composer research-mode exchange (feature D3): persists the
 * user's message, runs the [ResearchPipeline], and persists the reply
 * under an id the pipeline already knows — the reply id is generated
 * BEFORE the run, so the sources the pipeline stores point at exactly
 * this message and citations re-render after a restart.
 *
 * Deliberately separate from AgentLoop: a research exchange has no plan,
 * no actions and no approval gate — it is a question, a retrieval, and a
 * grounded answer. It also does not feed the memory extractor
 * (MemoryManager.storeMessage): web content the user never said should
 * not become long-term "memories" about them.
 */
@Singleton
class ResearchChatRunner @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val pipeline: ResearchPipeline
) {

    suspend fun run(
        sessionId: String,
        query: String,
        configOverride: LLMConfig? = null
    ): ResearchOutcome {
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = query,
            sender = ChatMessage.Sender.USER
        )
        conversationRepository.insertMessage(sessionId, userMsg)

        val replyId = UUID.randomUUID().toString()
        val outcome = pipeline.run(
            query = query,
            sessionId = sessionId,
            messageId = replyId,
            runId = UUID.randomUUID().toString(),
            configOverride = configOverride
        )

        val reply = ChatMessage(
            id = replyId,
            text = outcome.displayText,
            sender = ChatMessage.Sender.AGENT,
            modelBadge = RESEARCH_BADGE
        )
        conversationRepository.insertMessage(sessionId, reply)
        return outcome
    }

    companion object {
        /** Badge shown over research replies so they read as research, not plain chat. */
        const val RESEARCH_BADGE = "Web research"
    }
}
