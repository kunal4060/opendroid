package com.opendroid.ai.core.templates

import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.PlanValidator
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.repository.PlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TemplateShareServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val registeredActions = setOf("GET_WEATHER", "SEND_WHATSAPP", "OPEN_APP")

    private fun steps(): List<PlanStep> = listOf(
        PlanStep(
            stepId = "step-1",
            order = 1,
            description = "Check the weather",
            action = "GET_WEATHER",
            params = mapOf("city" to "Berlin")
        ),
        PlanStep(
            stepId = "step-2",
            order = 2,
            description = "Send the summary",
            action = "SEND_WHATSAPP",
            params = mapOf("contact" to "+91 98200 12345", "message" to "hi"),
            dependsOn = listOf("step-1")
        )
    )

    /**
     * Service under test: real codec/planning/insert logic, with only the
     * action-registry gate substituted (building the full ActionDispatcher
     * graph is out of scope for a JVM unit test — the production binding is
     * PlanValidator.validateStep, exercised via the same seam).
     */
    private fun service(macroDao: MacroDao, planDao: PlanDao): TemplateShareService {
        val unusedValidator = PlanValidator(
            actionDispatcher = dagger.Lazy<ActionDispatcher> { throw UnsupportedOperationException("unused in tests") },
            unknownActionDao = dagger.Lazy<UnknownActionDao> { throw UnsupportedOperationException("unused in tests") }
        )
        return FakeRegistryService(macroDao, planDao, unusedValidator, registeredActions)
    }

    @Test
    fun `export delete import round trip recreates a runnable disabled macro`() = runBlocking {
        val macroDao = InMemoryMacroDao()
        val planDao = InMemoryPlanDao()
        val svc = service(macroDao, planDao)

        val original = Macro(
            id = "macro-42",
            name = "Morning Routine",
            trigger = "cron:0 9 * * 1-5",
            steps = steps(),
            isSystem = false,
            isEnabled = true
        )
        macroDao.insertMacro(
            MacroEntity(original.id, original.name, original.trigger, json.encodeToString(original.steps), false, true)
        )

        // Export the live entity, then delete it — the file is all that remains.
        val export = svc.buildMacroExport(original)
        macroDao.deleteMacro(original.id)
        assertTrue(macroDao.getAllMacros().isEmpty())

        val preview = svc.previewImport(export.json)
        assertTrue(preview.canImport)
        assertEquals("Morning Routine", preview.resolvedName)

        val imported = svc.confirmImport(preview)
        assertEquals(TemplateFormat.KIND_MACRO, imported.kind)
        assertNotEquals(original.id, imported.id) // fresh id, never a resurrection of the old row

        val recreated = macroDao.getMacroById(imported.id)!!
        assertEquals("Morning Routine", recreated.name)
        assertEquals("cron:0 9 * * 1-5", recreated.trigger)
        // Steps/params survive the round trip byte-for-byte at the model level.
        assertEquals(json.encodeToString(steps()), recreated.stepsJson)
        // Untrusted-input defaults: not a system macro, starts disabled.
        assertFalse(recreated.isSystem)
        assertFalse(recreated.isEnabled)
    }

    @Test
    fun `malformed file previews as fatal and confirm writes nothing`() = runBlocking {
        val macroDao = InMemoryMacroDao()
        val svc = service(macroDao, InMemoryPlanDao())

        val preview = svc.previewImport("definitely not a template")
        assertFalse(preview.canImport)
        assertTrue(preview.fatalError != null)

        try {
            svc.confirmImport(preview)
            fail("confirmImport must refuse a non-importable preview")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertTrue(macroDao.getAllMacros().isEmpty())
    }

    @Test
    fun `unknown action blocks the import and creates nothing`() = runBlocking {
        val macroDao = InMemoryMacroDao()
        val svc = service(macroDao, InMemoryPlanDao())

        val alienSteps = listOf(
            PlanStep(stepId = "x1", order = 1, description = "Teleport", action = "TELEPORT_USER")
        )
        val export = svc.buildMacroExport(
            Macro(id = "m1", name = "Alien", trigger = "manual", steps = alienSteps)
        )

        val preview = svc.previewImport(export.json)
        assertFalse(preview.canImport)
        assertTrue(preview.errors.any { it.contains("TELEPORT_USER") })

        try {
            svc.confirmImport(preview)
            fail("confirmImport must refuse a blocked preview")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertTrue(macroDao.getAllMacros().isEmpty())
    }

    @Test
    fun `importing over an existing name renames instead of overwriting`() = runBlocking {
        val macroDao = InMemoryMacroDao()
        val svc = service(macroDao, InMemoryPlanDao())
        val existingStepsJson = json.encodeToString(steps())
        macroDao.insertMacro(MacroEntity("keep-me", "Morning Routine", "manual", existingStepsJson, false, true))

        val export = svc.buildMacroExport(
            Macro(id = "other", name = "Morning Routine", trigger = "voice", steps = steps())
        )
        val preview = svc.previewImport(export.json)
        assertEquals("Morning Routine (imported)", preview.resolvedName)

        val imported = svc.confirmImport(preview)
        assertEquals("Morning Routine (imported)", imported.name)

        // The pre-existing macro is untouched, the import is a second row.
        assertEquals(2, macroDao.getAllMacros().size)
        assertEquals("manual", macroDao.getMacroById("keep-me")!!.trigger)
        assertTrue(macroDao.getMacroById("keep-me")!!.isEnabled)
    }

    @Test
    fun `plan templates import through the plan repository as pending plans`() = runBlocking {
        val macroDao = InMemoryMacroDao()
        val planDao = InMemoryPlanDao()
        val svc = service(macroDao, planDao)

        val planExport = svc.buildPlanExport(
            com.opendroid.ai.data.models.Plan(
                planId = "p1",
                goal = "Berlin trip",
                estimatedDuration = "10 minutes",
                estimatedSteps = 2,
                steps = steps(),
                status = com.opendroid.ai.data.models.PlanStatus.COMPLETED,
                createdAt = 1L
            )
        )

        val preview = svc.previewImport(planExport.json)
        assertTrue(preview.canImport)

        val imported = svc.confirmImport(preview)
        assertEquals(TemplateFormat.KIND_PLAN, imported.kind)

        val stored = planDao.getPlanById(imported.id)!!
        assertEquals("Berlin trip", stored.goal)
        assertEquals("10 minutes", stored.estimatedDuration)
        assertEquals("PENDING", stored.status)
        assertEquals(json.encodeToString(steps()), stored.stepsJson)
        // Macros table untouched by a plan import.
        assertTrue(macroDao.getAllMacros().isEmpty())
    }
}

/** TemplateShareService with a fixed in-memory action registry. */
private class FakeRegistryService(
    macroDao: MacroDao,
    planDao: PlanDao,
    validator: PlanValidator,
    private val registered: Set<String>
) : TemplateShareService(macroDao, PlanRepository(planDao), validator) {
    override fun stepError(step: PlanStep): String? =
        if (step.action in registered) null
        else "Action '${step.action}' is not registered."
}

// ---------------------------------------------------------------------------
// In-memory fakes (same pattern as MacroActionsTest's InMemoryMacroDao)
// ---------------------------------------------------------------------------

private class InMemoryMacroDao(initial: List<MacroEntity> = emptyList()) : MacroDao {
    private val store = MutableStateFlow(initial)

    override fun getAllMacrosFlow(): Flow<List<MacroEntity>> = store
    override suspend fun getAllMacros(): List<MacroEntity> = store.value
    override suspend fun getMacroById(id: String): MacroEntity? = store.value.firstOrNull { it.id == id }
    override suspend fun getMacroByName(name: String): MacroEntity? = store.value.firstOrNull { it.name == name }
    override suspend fun insertMacro(macro: MacroEntity) {
        store.value = store.value.filterNot { it.id == macro.id } + macro
    }
    override suspend fun deleteMacro(id: String) {
        store.value = store.value.filterNot { it.id == id }
    }
    override suspend fun clearAllMacros() {
        store.value = emptyList()
    }
}

private class InMemoryPlanDao(initial: List<PlanEntity> = emptyList()) : PlanDao {
    private val store = MutableStateFlow(initial)

    override fun getAllPlansFlow(): Flow<List<PlanEntity>> =
        store.map { entities -> entities.sortedByDescending { it.createdAt } }
    override suspend fun getAllPlans(): List<PlanEntity> = store.value.sortedByDescending { it.createdAt }
    override suspend fun getPlanById(planId: String): PlanEntity? = store.value.firstOrNull { it.planId == planId }
    override suspend fun insertOrUpdatePlan(plan: PlanEntity) {
        store.value = store.value.filterNot { it.planId == plan.planId } + plan
    }
    override suspend fun deletePlan(planId: String) {
        store.value = store.value.filterNot { it.planId == planId }
    }
}
