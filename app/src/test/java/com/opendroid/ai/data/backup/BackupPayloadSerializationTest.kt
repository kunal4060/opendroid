package com.opendroid.ai.data.backup

import com.opendroid.ai.data.db.entities.ChatSessionEntity
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.db.entities.MemoryEntity
import com.opendroid.ai.data.db.entities.PlanEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun samplePayload(): BackupPayload = BackupPayload(
        formatVersion = 1,
        exportedAt = 1_761_000_000_000L,
        appVersion = "1.0.7",
        dbVersion = 10,
        sessions = listOf(
            ChatSessionBackup.from(
                ChatSessionEntity(
                    id = "s1", title = "Trip plan", createdAt = 10L, updatedAt = 20L,
                    isCurrent = true, providerOverride = "OpenAI", modelOverride = "gpt-x"
                )
            ),
            ChatSessionBackup.from(
                ChatSessionEntity(
                    id = "s2", title = "Old chat", createdAt = 5L, updatedAt = 6L,
                    isCurrent = false
                )
            )
        ),
        conversations = listOf(
            ConversationBackup.from(
                ConversationEntity(
                    id = "m1", text = "hello there", sender = "USER", timestamp = 11L,
                    sessionId = "s1"
                )
            ),
            ConversationBackup.from(
                ConversationEntity(
                    id = "m2", text = "hi!", sender = "AGENT", timestamp = 12L,
                    modelBadge = "gpt-x", sessionId = "s1"
                )
            )
        ),
        memories = listOf(
            // A knowledge-graph node row: the KG travels inside memories verbatim.
            MemoryBackup.from(
                MemoryEntity(
                    key = "pkg_node_abc",
                    value = "{\"id\":\"abc\",\"label\":\"Prefers tea\"}",
                    type = "SEMANTIC", timestamp = 100L, ttlHours = -1, category = "PREFERENCE"
                )
            ),
            MemoryBackup.from(
                MemoryEntity(
                    key = "user_name", value = "Tim", type = "SEMANTIC",
                    timestamp = 101L, ttlHours = -1, category = "FACT"
                )
            )
        ),
        macros = listOf(
            MacroBackup.from(
                MacroEntity(
                    id = "mac1", name = "Morning", trigger = "good morning",
                    stepsJson = "[]", isSystem = false, isEnabled = true
                )
            )
        ),
        plans = listOf(
            PlanBackup.from(
                PlanEntity(
                    planId = "p1", goal = "Book a cab", estimatedDuration = "5m",
                    estimatedSteps = 3, stepsJson = "[]", status = "COMPLETED",
                    createdAt = 200L
                )
            )
        ),
        routines = listOf(
            RoutineBackup.from(
                HabitRoutineEntity(
                    id = "r1", name = "Gym", description = "d", triggerLabel = "6 PM",
                    triggerCron = "0 18 * * *", detectedActionsJson = "[]",
                    suggestedStepsJson = "[]", repetitionCount = 7, confidence = 0.85f,
                    status = "APPROVED", suggestionMessage = "s", createdAt = 1L,
                    lastDetectedAt = 2L, lastExecutedAt = 3L, macroId = "mac1"
                )
            )
        ),
        settings = mapOf(
            "llm_config" to BackupSettingValue(BackupSettingValue.KIND_STRING, scalar = "{}"),
            "auto_reply_global" to BackupSettingValue(BackupSettingValue.KIND_BOOLEAN, scalar = "true"),
            "auto_reply_delay_minutes" to BackupSettingValue(BackupSettingValue.KIND_INT, scalar = "5"),
            "auto_reply_blacklist" to BackupSettingValue(
                BackupSettingValue.KIND_STRING_SET, stringSet = listOf("a", "b")
            )
        ),
        appSettings = AppSettingsBackup(onboardingCompleted = true, huggingFaceLastVerified = "Today"),
        credentials = CredentialsBackup(
            providerApiKeys = mapOf("OpenAI" to "sk-test"),
            elevenLabsApiKey = "el-test",
            huggingFaceToken = null,
            sensitiveMemories = mapOf("wifi" to "hunter2")
        )
    )

    @Test
    fun `payload round trips through JSON unchanged`() {
        val payload = samplePayload()
        val encoded = json.encodeToString(BackupPayload.serializer(), payload)
        val decoded = json.decodeFromString(BackupPayload.serializer(), encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun `DTO mappers are lossless both ways`() {
        val session = ChatSessionEntity(
            id = "s", title = "t", createdAt = 1L, updatedAt = 2L,
            isCurrent = true, providerOverride = "p", modelOverride = "m"
        )
        assertEquals(session, ChatSessionBackup.from(session).toEntity())

        val memory = MemoryEntity(
            key = "k", value = "v", type = "EPISODIC", timestamp = 3L,
            ttlHours = 24, category = "CONTEXT"
        )
        assertEquals(memory, MemoryBackup.from(memory).toEntity())

        val routine = HabitRoutineEntity(
            id = "r", name = "n", description = "d", triggerLabel = "l",
            triggerCron = "c", detectedActionsJson = "[]", suggestedStepsJson = "[1]",
            repetitionCount = 2, confidence = 0.5f, status = "SUGGESTED",
            suggestionMessage = "s", createdAt = 1L, lastDetectedAt = 2L,
            lastExecutedAt = null, macroId = null
        )
        assertEquals(routine, RoutineBackup.from(routine).toEntity())
    }

    @Test
    fun `summary counts match the payload sections`() {
        val summary = samplePayload().summary()
        assertEquals(2, summary.sessionCount)
        assertEquals(2, summary.messageCount)
        assertEquals(2, summary.memoryCount)
        assertEquals(1, summary.macroCount)
        assertEquals(1, summary.planCount)
        assertEquals(1, summary.routineCount)
        assertTrue(summary.includesSettings)
        assertTrue(summary.includesCredentials)
    }

    @Test
    fun `payload without optional sections reports them absent`() {
        val payload = BackupPayload(
            formatVersion = 1, exportedAt = 0L, appVersion = "1.0.7", dbVersion = 10
        )
        val summary = payload.summary()
        assertFalse(summary.includesSettings)
        assertFalse(summary.includesCredentials)
        assertFalse(payload.sections().contains(BackupPayload.SECTION_CREDENTIALS))
        assertTrue(payload.sections().contains(BackupPayload.SECTION_CHATS))
    }

    @Test
    fun `setting values convert from every DataStore type`() {
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_BOOLEAN, scalar = "true"),
            BackupSettingValue.fromAny(true)
        )
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_INT, scalar = "42"),
            BackupSettingValue.fromAny(42)
        )
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_LONG, scalar = "42"),
            BackupSettingValue.fromAny(42L)
        )
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_FLOAT, scalar = "1.5"),
            BackupSettingValue.fromAny(1.5f)
        )
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_DOUBLE, scalar = "1.5"),
            BackupSettingValue.fromAny(1.5)
        )
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_STRING, scalar = "x"),
            BackupSettingValue.fromAny("x")
        )
        assertEquals(
            BackupSettingValue(BackupSettingValue.KIND_STRING_SET, stringSet = listOf("a", "b")),
            BackupSettingValue.fromAny(setOf("a", "b"))
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupSettingValue.fromAny(ByteArray(3))
        }
    }
}
