package com.opendroid.ai.core.launch

import android.content.Context
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.routine.HabitRoutineEngine
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.models.PlanStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Headless execution facade for external triggers (widget buttons, launcher
 * shortcuts, and later the scheduler / intent API).
 *
 * It exists so an outside surface never re-implements "run the agent": a
 * routine goes through [HabitRoutineEngine.executeRoutine] — the exact path
 * the Routines screen uses, including its `lastExecutedAt` bookkeeping — and
 * a macro's steps go through the same [ActionSequenceExecutor] the agent's
 * own RUN_MACRO action uses, so approval/risk behaviour inside the steps is
 * identical no matter who pressed the button.
 */
@Singleton
class AgentCommandRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRoutineEngine: HabitRoutineEngine,
    private val macroDao: MacroDao,
    private val actionSequenceExecutor: dagger.Lazy<ActionSequenceExecutor>
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Runs an approved/active routine by id, exactly as the Routines UI does. */
    suspend fun runRoutine(routineId: String): ActionResult =
        habitRoutineEngine.executeRoutine(routineId, context)

    /** Runs a macro by id through the shared step executor. */
    suspend fun runMacro(macroId: String): ActionResult {
        val macro = macroDao.getMacroById(macroId)
            ?: return ActionResult.Failure("Macro not found.")
        if (!macro.isEnabled) {
            return ActionResult.Failure("Macro '${macro.name}' is turned off.")
        }
        if (macro.stepsJson.isBlank()) {
            return ActionResult.Failure("Macro '${macro.name}' has no steps.")
        }
        val steps = try {
            json.decodeFromString<List<PlanStep>>(macro.stepsJson)
        } catch (e: SerializationException) {
            return ActionResult.Failure("Macro '${macro.name}' has invalid step data.")
        }
        return actionSequenceExecutor.get().execute(steps, context)
    }
}
