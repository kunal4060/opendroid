package com.opendroid.ai.core.memory

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.graph.OriginKind
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.routine.FakeMacroDao
import com.opendroid.ai.core.routine.FakeMemoryDao
import com.opendroid.ai.core.routine.FakeNotificationDao
import com.opendroid.ai.core.routine.FakeSensitiveMemoryStore
import com.opendroid.ai.core.routine.FakeTaskHistoryDao
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A7: facts that MemoryExtractor learns from chat must carry their real
 * extraction path (session + source message time) as knowledge-graph
 * provenance, while the legacy plain semantic row keeps being written.
 */
class MemoryExtractorProvenanceTest {

    private val context: Context = ContextWrapper(null)
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var engine: PersonalGrowthEngine
    private lateinit var extractor: MemoryExtractor

    @Before
    fun setUp() {
        memoryRepository = MemoryRepository(
            memoryDao = FakeMemoryDao(),
            taskHistoryDao = FakeTaskHistoryDao(),
            macroDao = FakeMacroDao()
        )
        engine = PersonalGrowthEngine(
            memoryRepository = memoryRepository,
            sensitiveMemoryStore = FakeSensitiveMemoryStore(),
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            notificationIntelligence = NotificationIntelligence(FakeNotificationDao(), memoryRepository)
        )
        extractor = MemoryExtractor(memoryRepository, engine)
    }

    @Test
    fun `extracted fact gains chat-extraction provenance with session linkage`() = runBlocking {
        val messageTime = 1_760_000_000_000L
        val messages = listOf(
            ChatMessage(
                id = "m1",
                text = "My name is Tim",
                sender = ChatMessage.Sender.USER,
                timestamp = messageTime
            )
        )

        extractor.extractFacts(messages, sessionId = "session-xyz")

        // Legacy behaviour preserved: the plain semantic fact row exists.
        val plainFacts = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
        assertTrue(plainFacts.any { it.key == "user_name" && it.value == "Tim" })

        // Provenance: a KG node naming the real extraction path.
        val node = engine.getSnapshot().nodes["chatfact_user_name"]
        assertNotNull(node)
        assertEquals(OriginKind.CHAT_EXTRACTION, node?.origin?.kind)
        assertEquals("session-xyz", node?.origin?.sessionId)
        assertEquals(messageTime, node?.origin?.createdAt)
        // Provenance must reference the session, never embed message text.
        assertTrue(node?.origin?.detail?.contains("My name is") == false)
    }

    @Test
    fun `extraction without a session still stamps the extraction path honestly`() = runBlocking {
        val messages = listOf(
            ChatMessage(
                id = "m2",
                text = "I live in Pune",
                sender = ChatMessage.Sender.USER,
                timestamp = 1_760_100_000_000L
            )
        )

        extractor.extractFacts(messages)

        val node = engine.getSnapshot().nodes["chatfact_user_location"]
        assertNotNull(node)
        assertEquals(OriginKind.CHAT_EXTRACTION, node?.origin?.kind)
        assertEquals(null, node?.origin?.sessionId)
        assertEquals(1_760_100_000_000L, node?.origin?.createdAt)
    }

    @Test
    fun `assistant messages are never treated as user facts`() = runBlocking {
        val messages = listOf(
            ChatMessage(
                id = "m3",
                text = "My name is OpenDroid",
                sender = ChatMessage.Sender.AGENT,
                timestamp = 1_760_200_000_000L
            )
        )

        extractor.extractFacts(messages, sessionId = "session-xyz")

        assertTrue(engine.getSnapshot().nodes.values.none { it.id.startsWith("chatfact_") })
    }
}
