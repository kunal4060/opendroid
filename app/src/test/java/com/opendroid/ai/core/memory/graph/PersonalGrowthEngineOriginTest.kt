package com.opendroid.ai.core.memory.graph

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.core.routine.FakeMacroDao
import com.opendroid.ai.core.routine.FakeMemoryDao
import com.opendroid.ai.core.routine.FakeNotificationDao
import com.opendroid.ai.core.routine.FakeSensitiveMemoryStore
import com.opendroid.ai.core.routine.FakeTaskHistoryDao
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A7 provenance: every PersonalGrowthEngine creation funnel must stamp a
 * real [MemoryOrigin], pattern re-observation must preserve the first
 * origin, and deletion must remove the node from snapshot + recall.
 */
class PersonalGrowthEngineOriginTest {

    private val context: Context = ContextWrapper(null)
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var engine: PersonalGrowthEngine

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
    }

    private fun nodeById(id: String): KnowledgeNode? = runBlocking {
        engine.getSnapshot().nodes[id]
    }

    @Test
    fun `explicit memory is stamped user-explicit with creation time`() = runBlocking {
        val before = System.currentTimeMillis()
        val node = engine.recordExplicitMemory("Coffee", "Likes filter coffee")
        val after = System.currentTimeMillis()

        assertEquals(OriginKind.USER_EXPLICIT, node.origin?.kind)
        assertTrue(node.origin!!.createdAt in before..after)

        // …and the stamp is what actually got persisted, not just returned.
        val persisted = nodeById(node.id)
        assertEquals(OriginKind.USER_EXPLICIT, persisted?.origin?.kind)
        assertEquals(node.origin?.createdAt, persisted?.origin?.createdAt)
    }

    @Test
    fun `caller-supplied origin wins over the default`() = runBlocking {
        val custom = MemoryOrigin(OriginKind.USER_ENTERED, detail = "Memory screen", createdAt = 123L)
        val node = engine.recordExplicitMemory("Note", "Typed in", origin = custom)
        assertEquals(OriginKind.USER_ENTERED, node.origin?.kind)
        assertEquals(123L, node.origin?.createdAt)
    }

    @Test
    fun `app usage stamps app-usage origin and preserves it across observations`() = runBlocking {
        engine.recordAppUsage("Spotify", "Music Playback")
        val first = nodeById("pattern_app_spotify")
        assertEquals(OriginKind.APP_USAGE, first?.origin?.kind)
        assertEquals("Music Playback", first?.origin?.detail)

        engine.recordAppUsage("Spotify", "Music Playback")
        engine.recordAppUsage("Spotify", "Music Playback")
        val third = nodeById("pattern_app_spotify")

        assertEquals(3, third?.accessCount)
        // First-learned moment must not drift forward on re-observation.
        assertEquals(first?.origin?.createdAt, third?.origin?.createdAt)
        assertEquals(OriginKind.APP_USAGE, third?.origin?.kind)
    }

    @Test
    fun `contact interaction stamps channel as origin detail`() = runBlocking {
        engine.recordContactInteraction("Mom", "WhatsApp")
        val node = nodeById("pattern_contact_mom")
        assertEquals(OriginKind.CONTACT_INTERACTION, node?.origin?.kind)
        assertEquals("WhatsApp", node?.origin?.detail)
    }

    @Test
    fun `website visit stamps website origin`() = runBlocking {
        engine.recordWebsiteVisit("https://news.example.com/article", "news.example.com")
        val node = nodeById("pattern_web_news_example_com")
        assertEquals(OriginKind.WEBSITE_VISIT, node?.origin?.kind)
        assertEquals("news.example.com", node?.origin?.detail)
    }

    @Test
    fun `task routine stamps task-pattern origin`() = runBlocking {
        engine.recordTaskOrRoutine("Alarm at 7:00", "7:00", "daily")
        val node = nodeById("pattern_routine_alarm_at_7_00")
        assertEquals(OriginKind.TASK_PATTERN, node?.origin?.kind)
    }

    @Test
    fun `common schedule stamps schedule-pattern origin`() = runBlocking {
        engine.recordCommonSchedule("Gym", "Mon 7 AM")
        val node = nodeById("pattern_schedule_gym")
        assertEquals(OriginKind.SCHEDULE_PATTERN, node?.origin?.kind)
        assertEquals("Mon 7 AM", node?.origin?.detail)
    }

    @Test
    fun `sensitive secret node is stamped without leaking the secret into provenance`() = runBlocking {
        val ok = engine.recordSensitiveSecret("locker", "9981", "Locker code")
        assertTrue(ok)
        val node = nodeById("sensitive_locker")
        assertNotNull(node)
        assertEquals(OriginKind.USER_EXPLICIT, node?.origin?.kind)
        assertFalse(node?.origin?.detail?.contains("9981") == true)
        assertFalse(node?.summary?.contains("9981") == true)
    }

    @Test
    fun `chat extracted fact stamps session and message time, then preserves them`() = runBlocking {
        val first = engine.recordChatExtractedFact(
            factKey = "user_name",
            factValue = "Tim",
            sessionId = "session-1",
            sourceMessageTimestamp = 1_760_000_000_000L
        )
        assertEquals(OriginKind.CHAT_EXTRACTION, first.origin?.kind)
        assertEquals("session-1", first.origin?.sessionId)
        assertEquals(1_760_000_000_000L, first.origin?.createdAt)

        // Same fact re-extracted in a later session: value updates, the
        // original provenance (first learned) does not move.
        val second = engine.recordChatExtractedFact(
            factKey = "user_name",
            factValue = "Tim U.",
            sessionId = "session-2",
            sourceMessageTimestamp = 1_760_500_000_000L
        )
        assertEquals(first.id, second.id)
        assertEquals("Tim U.", second.summary)
        assertEquals(2, second.accessCount)
        assertEquals("session-1", second.origin?.sessionId)
        assertEquals(1_760_000_000_000L, second.origin?.createdAt)
    }

    @Test
    fun `legacy node rows without origin still load and report no origin`() = runBlocking {
        // A node JSON exactly as written before provenance existed.
        val legacyJson = """
            {"id":"pattern_app_legacy","label":"Legacy","category":"APP_PREFERENCE",
             "tier":"LEARNED_PATTERN","summary":"Preferred app (used 4 times)",
             "properties":{},"confidence":0.7,"accessCount":4,"lastUpdated":1760000000000,
             "ttlHours":-1}
        """.trimIndent()
        memoryRepository.saveMemory(
            Memory(key = "pkg_node_pattern_app_legacy", value = legacyJson, type = MemoryType.SEMANTIC)
        )

        val node = nodeById("pattern_app_legacy")
        assertNotNull(node)
        assertNull(node?.origin)
    }

    @Test
    fun `promoting a pattern keeps its original provenance`() = runBlocking {
        engine.recordAppUsage("Maps", "Navigation")
        val promoted = engine.promotePatternToExplicit("pattern_app_maps")
        assertTrue(promoted)
        val node = nodeById("pattern_app_maps")
        assertEquals(MemoryTier.LONG_TERM, node?.tier)
        assertEquals(OriginKind.APP_USAGE, node?.origin?.kind)
    }

    @Test
    fun `deleted memory leaves the graph and stops surfacing in recall context`() = runBlocking {
        val node = engine.recordExplicitMemory("GoaTrip", "Planning a Goa trip in December")
        assertTrue(engine.generateSynthesizedContext().contains("GoaTrip"))

        assertTrue(engine.deleteNode(node.id))

        assertNull(nodeById(node.id))
        assertFalse(engine.generateSynthesizedContext().contains("GoaTrip"))
    }
}
