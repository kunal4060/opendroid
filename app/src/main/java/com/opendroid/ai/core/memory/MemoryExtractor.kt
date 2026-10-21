package com.opendroid.ai.core.memory

import android.util.Log
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryExtractor @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val personalGrowthEngine: PersonalGrowthEngine
) {
    companion object {
        private const val TAG = "MemoryExtractor"

        private val MEMORY_BLACKLIST = listOf(
            "is not registered",
            "invalid_action",
            "actiondispatcher",
            "do not use this action",
            "whitelisted actions",
            "not registered in",
            "execution error",
            "action module",
            "fallback routine",
            "plan sequence",
            "stepid",
            "dependson",
            "canparallelize",
            "estimatedduration",
            "planid",
            "unknown action",
            "not a valid action",
            "action failed"
        )
    }

    fun shouldStoreInSemanticMemory(content: String): Boolean {
        val lower = content.lowercase().trim()
        if (lower.isEmpty()) return false
        if (MEMORY_BLACKLIST.any { lower.contains(it) }) return false
        if (lower.startsWith("invalid_")) return false
        return true
    }

    private val patterns = listOf(
        Regex("my name is ([a-zA-Z\\s]+)", RegexOption.IGNORE_CASE) to "user_name",
        Regex("my wife's name is ([a-zA-Z\\s]+)", RegexOption.IGNORE_CASE) to "wife_name",
        Regex("my husband's name is ([a-zA-Z\\s]+)", RegexOption.IGNORE_CASE) to "husband_name",
        Regex("I live in ([a-zA-Z\\s,]+)", RegexOption.IGNORE_CASE) to "user_location",
        Regex("my office address is ([a-zA-Z0-9\\s,]+)", RegexOption.IGNORE_CASE) to "office_address",
        Regex("my home address is ([a-zA-Z0-9\\s,]+)", RegexOption.IGNORE_CASE) to "home_address",
        Regex("my phone number is ([0-9+\\-]+)", RegexOption.IGNORE_CASE) to "user_phone"
    )

    /**
     * Extracts facts from [messages]. [sessionId] is the chat session the
     * messages belong to (when the caller knows it — the agent layer does);
     * it is stored as provenance on the knowledge-graph node recorded for
     * each fact, so the Memory screen can later answer "why do you know
     * this?" with the real extraction path. Additive: existing callers that
     * don't know a session pass nothing.
     */
    suspend fun extractFacts(messages: List<ChatMessage>, sessionId: String? = null) {
        messages.forEach { msg ->
            if (msg.sender == ChatMessage.Sender.USER) {
                // Heuristic pattern checking
                patterns.forEach { (regex, key) ->
                    val match = regex.find(msg.text)
                    if (match != null) {
                        val value = match.groupValues[1].trim()
                        if (shouldStoreInSemanticMemory(key) && shouldStoreInSemanticMemory(value)) {
                            storeExtractedFact(key, value, msg, sessionId)
                        }
                    }
                }

                // Dynamic extraction helper
                val wifeMatch = Regex("([a-zA-Z]+) is my wife", RegexOption.IGNORE_CASE).find(msg.text)
                if (wifeMatch != null) {
                    val value = wifeMatch.groupValues[1].trim()
                    if (shouldStoreInSemanticMemory("wife_name") && shouldStoreInSemanticMemory(value)) {
                        storeExtractedFact("wife_name", value, msg, sessionId)
                    }
                }

                val husbandMatch = Regex("([a-zA-Z]+) is my husband", RegexOption.IGNORE_CASE).find(msg.text)
                if (husbandMatch != null) {
                    val value = husbandMatch.groupValues[1].trim()
                    if (shouldStoreInSemanticMemory("husband_name") && shouldStoreInSemanticMemory(value)) {
                        storeExtractedFact("husband_name", value, msg, sessionId)
                    }
                }
            }
        }
    }

    /**
     * Persists an extracted fact exactly as before (plain semantic row) and
     * additionally records its provenance in the knowledge graph. The plain
     * row has no field for origin, so without the graph node a chat-learned
     * fact could never show where it came from. A provenance failure must
     * never lose the fact itself, hence the guarded second write.
     */
    private suspend fun storeExtractedFact(
        key: String,
        value: String,
        sourceMessage: ChatMessage,
        sessionId: String?
    ) {
        memoryRepository.saveMemory(
            Memory(key = key, value = value, type = MemoryType.SEMANTIC)
        )
        try {
            personalGrowthEngine.recordChatExtractedFact(
                factKey = key,
                factValue = value,
                sessionId = sessionId,
                sourceMessageTimestamp = sourceMessage.timestamp
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record provenance for extracted fact '$key': ${e.message}")
        }
    }
}
