package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.agent.ActionCategory
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.schedule.ScheduleRecurrence
import com.opendroid.ai.core.schedule.ScheduledTask
import com.opendroid.ai.core.schedule.ScheduledTaskKind
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskStore
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric since Loop-2 #7: DELETE_MACRO cascades into the real
// ScheduledTaskStore/ScheduledTaskScheduler, so constructions need a
// working application context and real schedule collaborators.
@RunWith(RobolectricTestRunner::class)
class MacroActionsTest {
    private val context: Context = ContextWrapper(null)

    private lateinit var taskStore: ScheduledTaskStore
    private lateinit var taskScheduler: ScheduledTaskScheduler

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        taskStore = ScheduledTaskStore(appContext)
        taskScheduler = ScheduledTaskScheduler(appContext, taskStore)
    }

    private fun macroActions(
        dao: MacroDao,
        executor: ActionSequenceExecutor
    ): MacroActions = MacroActions(
        macroDao = dao,
        actionSequenceExecutor = executor,
        scheduledTaskStore = dagger.Lazy { taskStore },
        scheduledTaskScheduler = dagger.Lazy { taskScheduler }
    )

    private fun failingExecutor() =
        ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })

    @Test
    fun `run macro dispatches decoded steps instead of returning raw JSON`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity(
                id = "macro-1",
                name = "morning",
                trigger = "manual",
                stepsJson = "[{\"stepId\":\"one\",\"order\":1,\"description\":\"First\",\"action\":\"FIRST\"}]",
                isSystem = false,
                isEnabled = true
            )
        )
        val calls = mutableListOf<String>()
        val actions = macroActions(
            dao = dao,
            executor = ActionSequenceExecutor(
                executeAction = { action, _, _ ->
                    calls += action
                    com.opendroid.ai.actions.base.ActionResult.Success()
                },
                hasAction = { true }
            )
        )

        val result = actions.getActions().first { it.name == "RUN_MACRO" }
            .execute(mapOf("macroName" to "morning"), context)

        assertTrue(result.success)
        assertTrue(result.data!!.contains("completed successfully"))
        assertFalse(result.data!!.contains("stepsJson"))
        assertTrue(calls == listOf("FIRST"))
    }

    @Test
    fun `run macro reports missing and malformed data truthfully`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity("bad", "bad", "manual", "not-json", false, true),
            MacroEntity("empty", "empty", "manual", "", false, true)
        )
        val actions = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "RUN_MACRO" }

        val missing = actions.execute(mapOf("macroName" to "missing"), context)
        val malformed = actions.execute(mapOf("macroName" to "bad"), context)
        val empty = actions.execute(mapOf("macroName" to "empty"), context)

        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("not found"))
        assertFalse(malformed.success)
        assertTrue(malformed.error!!.contains("invalid step data"))
        assertFalse(empty.success)
        assertTrue(empty.error!!.contains("no step data"))
    }

    @Test
    fun `delete macro removes a found custom macro`() = runBlocking {
        val dao = InMemoryMacroDao(MacroEntity("macro-1", "morning", "manual", "[]", false, true))
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "morning"), context)

        assertTrue(result.success)
        assertEquals("Macro 'morning' deleted.", result.data)
        assertNull(dao.getMacroByName("morning"))
    }

    @Test
    fun `delete macro disarms scheduled tasks created for it`() = runBlocking {
        taskStore.clearAll()
        val dao = InMemoryMacroDao(MacroEntity("macro-1", "morning", "manual", "[]", false, true))
        // Agent-created task: macroId set, sourceKey null (the shape
        // SCHEDULE_AGENT_TASK writes — invisible to the un-mirror sweep).
        taskStore.upsert(
            ScheduledTask(
                id = "task-agent",
                kind = ScheduledTaskKind.MACRO,
                label = "morning",
                macroId = "macro-1",
                macroName = "morning",
                recurrence = ScheduleRecurrence.CRON,
                cronExpression = "0 9 * * *"
            )
        )
        // Sync-mirrored task for the same macro.
        taskStore.upsert(
            ScheduledTask(
                id = "task-mirror",
                kind = ScheduledTaskKind.MACRO,
                label = "morning",
                macroId = "macro-1",
                macroName = "morning",
                recurrence = ScheduleRecurrence.CRON,
                cronExpression = "0 9 * * *",
                sourceKey = "macro:macro-1"
            )
        )
        // Another macro's task must survive.
        taskStore.upsert(
            ScheduledTask(
                id = "task-other",
                kind = ScheduledTaskKind.MACRO,
                label = "evening",
                macroId = "macro-2",
                macroName = "evening",
                recurrence = ScheduleRecurrence.CRON,
                cronExpression = "0 18 * * *"
            )
        )
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "morning"), context)

        assertTrue(result.success)
        val remainingIds = taskStore.snapshot().map { it.id }
        assertEquals(listOf("task-other"), remainingIds)
    }

    @Test
    fun `delete macro reports when the requested macro does not exist`() = runBlocking {
        val dao = InMemoryMacroDao()
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "missing"), context)

        assertFalse(result.success)
        assertEquals("Macro with name 'missing' not found.", result.error)
    }

    @Test
    fun `delete macro refuses to remove a system macro`() = runBlocking {
        val dao = InMemoryMacroDao(MacroEntity("system-1", "startup", "manual", "[]", true, true))
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "startup"), context)

        assertFalse(result.success)
        assertEquals("System macro 'startup' cannot be deleted.", result.error)
        assertTrue(dao.getMacroByName("startup") != null)
    }

    @Test
    fun `delete macro reports failure when the DAO leaves the macro in place`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity("macro-1", "morning", "manual", "[]", false, true),
            deleteSucceeds = false
        )
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "morning"), context)

        assertFalse(result.success)
        assertEquals("Couldn't delete macro 'morning'.", result.error)
        assertTrue(dao.getMacroByName("morning") != null)
    }

    @Test
    fun `schedule macro stores a parseable cron on an existing macro`() = runBlocking {
        val dao = InMemoryMacroDao(MacroEntity("macro-1", "morning", "manual", "[]", false, true))
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "SCHEDULE_MACRO" }

        val result = action.execute(
            mapOf("macroName" to "morning", "cronExpression" to "0 9 * * *"),
            context
        )

        assertTrue(result.success)
        assertEquals("Macro 'morning' is scheduled!", result.data)
        assertEquals("cron:0 9 * * *", dao.getMacroByName("morning")!!.trigger)
    }

    @Test
    fun `schedule macro rejects an unparseable cron instead of arming nothing`() = runBlocking {
        val dao = InMemoryMacroDao(MacroEntity("macro-1", "morning", "manual", "[]", false, true))
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "SCHEDULE_MACRO" }

        val result = action.execute(
            mapOf("macroName" to "morning", "cronExpression" to "not a cron"),
            context
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("isn't a valid cron expression"))
        // The bad trigger must NOT have been stored.
        assertEquals("manual", dao.getMacroByName("morning")!!.trigger)
    }

    @Test
    fun `schedule macro reports a missing macro instead of creating an empty shell`() = runBlocking {
        val dao = InMemoryMacroDao()
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "SCHEDULE_MACRO" }

        val result = action.execute(
            mapOf("macroName" to "ghost", "cronExpression" to "0 9 * * *"),
            context
        )

        assertFalse(result.success)
        assertEquals("Macro 'ghost' not found.", result.error)
        assertNull(dao.getMacroByName("ghost"))
    }

    @Test
    fun `list macros reports when none are saved`() = runBlocking {
        val action = macroActions(InMemoryMacroDao(), failingExecutor())
            .getActions().first { it.name == "LIST_MACROS" }

        val result = action.execute(emptyMap(), context)

        assertTrue(result.success)
        assertEquals("No macros found.", result.data)
    }

    @Test
    fun `list macros returns names without exposing stored step data`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity("macro-2", "evening", "manual", "secret-evening-steps", false, true),
            MacroEntity("macro-1", "morning", "manual", "secret-morning-steps", false, true)
        )
        val action = macroActions(dao, failingExecutor())
            .getActions().first { it.name == "LIST_MACROS" }

        val result = action.execute(emptyMap(), context)

        assertTrue(result.success)
        assertEquals("Saved macros:\n- evening\n- morning", result.data)
        assertFalse(result.data!!.contains("secret-"))
    }

    @Test
    fun `macro management actions are registered with the expected schemas`() {
        val delete = ActionSchema.getAction("DELETE_MACRO")
        val list = ActionSchema.getAction("LIST_MACROS")

        assertEquals(ActionCategory.MACRO, delete!!.category)
        assertEquals(listOf("macroName"), delete.params.map { it.name })
        assertTrue(delete.params.single().required)
        assertTrue(delete.neverAutoApprove)
        val (missingDeleteParam, _) = ActionSchema.validateParams("DELETE_MACRO", emptyMap())
        assertTrue(missingDeleteParam is ActionSchema.ValidationResult.MissingParams)

        assertEquals(ActionCategory.MACRO, list!!.category)
        assertTrue(list.params.isEmpty())
    }

    private class InMemoryMacroDao(
        vararg initial: MacroEntity,
        private val deleteSucceeds: Boolean = true
    ) : MacroDao {
        private val macros = initial.toMutableList()
        private val flow = MutableStateFlow(macros.toList())

        override fun getAllMacrosFlow(): Flow<List<MacroEntity>> = flow
        override suspend fun getAllMacros(): List<MacroEntity> = macros.toList()
        override suspend fun getMacroById(id: String): MacroEntity? = macros.firstOrNull { it.id == id }
        override suspend fun getMacroByName(name: String): MacroEntity? = macros.firstOrNull { it.name == name }
        override suspend fun insertMacro(macro: MacroEntity) {
            macros.removeAll { it.id == macro.id }
            macros += macro
            flow.value = macros.toList()
        }
        override suspend fun deleteMacro(id: String) {
            if (!deleteSucceeds) return
            macros.removeAll { it.id == id }
            flow.value = macros.toList()
        }
        override suspend fun clearAllMacros() {
            macros.clear()
            flow.value = emptyList()
        }
    }
}
