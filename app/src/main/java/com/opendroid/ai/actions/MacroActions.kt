package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.schedule.CronExpression
import com.opendroid.ai.core.schedule.MacroScheduleSync
import com.opendroid.ai.core.schedule.ScheduledTaskScheduler
import com.opendroid.ai.core.schedule.ScheduledTaskStore
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MacroActions @Inject constructor(
    private val macroDao: MacroDao,
    private val actionSequenceExecutor: ActionSequenceExecutor,
    // Schedule collaborators for the DELETE_MACRO cascade (Loop-2 #7):
    // deleting a macro must also disarm the scheduled tasks that would
    // keep firing it. Lazy so the store/scheduler are only resolved when
    // a deletion actually happens.
    private val scheduledTaskStore: dagger.Lazy<ScheduledTaskStore>,
    private val scheduledTaskScheduler: dagger.Lazy<ScheduledTaskScheduler>
) {

    fun getActions(): List<Action> = listOf(
        RunMacroAction(macroDao, actionSequenceExecutor),
        CreateMacroAction(macroDao),
        ScheduleMacroAction(macroDao),
        DeleteMacroAction(macroDao, scheduledTaskStore, scheduledTaskScheduler),
        ListMacrosAction(macroDao)
    )

    private class RunMacroAction(
        private val macroDao: MacroDao,
        private val actionSequenceExecutor: ActionSequenceExecutor
    ) : Action {
        override val name: String = "RUN_MACRO"
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val macroName = params["macroName"] ?: return ActionResult(false, null, "macroName parameter missing")
            return try {
                val macro = macroDao.getMacroByName(macroName)
                    ?: return ActionResult(false, null, "Macro with name '$macroName' not found.")
                if (macro.stepsJson.isBlank()) {
                    return ActionResult(false, null, "Macro '$macroName' has no step data.")
                }
                val steps = try {
                    json.decodeFromString<List<PlanStep>>(macro.stepsJson)
                } catch (e: SerializationException) {
                    Log.e("RunMacro", "Invalid steps for '$macroName': ${e.localizedMessage}")
                    return ActionResult(false, null, "Macro '$macroName' has invalid step data.")
                }
                actionSequenceExecutor.execute(steps, context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("RunMacro", "Macro failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't run macro '$macroName' right now.")
            }
        }
    }

    private class CreateMacroAction(private val macroDao: MacroDao) : Action {
        override val name: String = "CREATE_MACRO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val name = params["name"] ?: return ActionResult(false, null, "name parameter missing")
            val steps = params["steps"] ?: return ActionResult(false, null, "steps parameter missing")
            return try {
                val entity = MacroEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    trigger = "manual",
                    stepsJson = steps,
                    isSystem = false,
                    isEnabled = true
                )
                macroDao.insertMacro(entity)
                ActionResult(true, "Macro '$name' is ready to go!", null)
            } catch (e: Exception) {
                Log.e("CreateMacro", "Create failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't create that macro.")
            }
        }
    }

    private class ScheduleMacroAction(private val macroDao: MacroDao) : Action {
        override val name: String = "SCHEDULE_MACRO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val macroName = params["macroName"] ?: return ActionResult(false, null, "macroName parameter missing")
            val cronExpression = params["cronExpression"] ?: return ActionResult(false, null, "cronExpression parameter missing")
            return try {
                // Validate the cron BEFORE storing it: MacroScheduleSync
                // silently skips unparseable triggers, so storing one
                // verbatim and reporting "scheduled" armed nothing at all —
                // the macro simply never fired and no error ever surfaced.
                if (CronExpression.parse(cronExpression.trim()) == null) {
                    return ActionResult(false, null, "Couldn't schedule '$macroName': '$cronExpression' isn't a valid cron expression.")
                }
                val macro = macroDao.getMacroByName(macroName)
                    // No empty shells: the old code CREATED a macro with
                    // stepsJson = "[]" for a missing name and reported it
                    // scheduled; the shell then failed at every firing
                    // ("has no steps"). Fail honestly, like
                    // SCHEDULE_AGENT_TASK does.
                    ?: return ActionResult(false, null, "Macro '$macroName' not found.")
                val updated = macro.copy(trigger = "cron:$cronExpression")
                macroDao.insertMacro(updated)
                ActionResult(true, "Macro '$macroName' is scheduled!", null)
            } catch (e: Exception) {
                Log.e("ScheduleMacro", "Schedule failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't schedule that macro.")
            }
        }
    }

    private class DeleteMacroAction(
        private val macroDao: MacroDao,
        private val scheduledTaskStore: dagger.Lazy<ScheduledTaskStore>,
        private val scheduledTaskScheduler: dagger.Lazy<ScheduledTaskScheduler>
    ) : Action {
        override val name: String = "DELETE_MACRO"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val macroName = params["macroName"] ?: return ActionResult(false, null, "macroName parameter missing")
            return try {
                val macro = macroDao.getMacroByName(macroName)
                    ?: return ActionResult(false, null, "Macro with name '$macroName' not found.")
                if (macro.isSystem) {
                    return ActionResult(false, null, "System macro '$macroName' cannot be deleted.")
                }

                macroDao.deleteMacro(macro.id)
                if (macroDao.getMacroById(macro.id) != null) {
                    return ActionResult(false, null, "Couldn't delete macro '$macroName'.")
                }

                // Cascade: disarm everything that would keep firing this
                // macro. Agent-created tasks reference it by macroId with
                // a null sourceKey, which MacroScheduleSync's un-mirror
                // sweep never sees (it only sweeps sourceKey tasks), so
                // they survived forever — failing at every firing and
                // re-arming indefinitely. The sync-mirrored task (sourceKey
                // "macro:<id>") is removed here too rather than waiting for
                // the next sync. Same cascade HabitRoutineEngine.
                // deleteRoutine performs for routine deletion.
                val store = scheduledTaskStore.get()
                val sourceKey = "${MacroScheduleSync.SOURCE_PREFIX}${macro.id}"
                for (task in store.snapshot().filter { it.macroId == macro.id || it.sourceKey == sourceKey }) {
                    store.delete(task.id)
                    scheduledTaskScheduler.get().cancel(task.id)
                }

                ActionResult(true, "Macro '$macroName' deleted.", null)
            } catch (e: Exception) {
                Log.e("DeleteMacro", "Delete failed", e)
                ActionResult(false, null, "Couldn't delete macro '$macroName'.")
            }
        }
    }

    private class ListMacrosAction(private val macroDao: MacroDao) : Action {
        override val name: String = "LIST_MACROS"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val macroNames = macroDao.getAllMacros()
                    .map { it.name }
                    .sorted()
                val message = if (macroNames.isEmpty()) {
                    "No macros found."
                } else {
                    "Saved macros:\n${macroNames.joinToString("\n") { "- $it" }}"
                }
                ActionResult(true, message, null)
            } catch (e: Exception) {
                Log.e("ListMacros", "List failed", e)
                ActionResult(false, null, "Couldn't list your macros.")
            }
        }
    }
}
