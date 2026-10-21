package com.opendroid.ai.core.schedule

import com.opendroid.ai.data.db.dao.MacroDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the pre-C4 world into the scheduler: macros whose trigger is a
 * `cron:` string (written by `SCHEDULE_MACRO`, or materialised from habit
 * routines) are mirrored into real [ScheduledTask]s of kind MACRO, so
 * those previously write-only cron strings actually fire.
 *
 * Rules:
 * - Only enabled macros with a parseable `cron:` trigger are mirrored.
 * - The mirrored task keeps the user's own edits (label aside, a pause
 *   via `enabled=false` survives every sync).
 * - If the macro loses its cron trigger, is disabled, or is deleted, the
 *   mirrored task is removed and its alarm cancelled.
 * - If the user deletes a mirrored task in the Schedules UI, the store
 *   remembers the dismissal (sourceKey + the cron at the time): sync will
 *   not recreate it unless the macro's cron expression changes, which is
 *   the signal the user scheduled it afresh.
 */
@Singleton
class MacroScheduleSync @Inject constructor(
    private val store: ScheduledTaskStore,
    private val macroDao: MacroDao,
    private val scheduler: ScheduledTaskScheduler
) {

    suspend fun sync() {
        val dismissed = store.dismissedSourceCrons()
        val macros = macroDao.getAllMacros()
        val liveKeys = mutableSetOf<String>()

        for (macro in macros) {
            if (!macro.isEnabled) continue
            val trigger = macro.trigger
            if (!trigger.startsWith(CRON_PREFIX)) continue
            val cron = trigger.removePrefix(CRON_PREFIX).trim()
            if (CronExpression.parse(cron) == null) continue

            val sourceKey = "$SOURCE_PREFIX${macro.id}"
            liveKeys += sourceKey
            if (dismissed[sourceKey] == cron) continue // user deleted this exact schedule

            val existing = store.snapshot().firstOrNull { it.sourceKey == sourceKey }
            val task = if (existing != null) {
                existing.copy(
                    label = macro.name,
                    macroName = macro.name,
                    cronExpression = cron
                )
            } else {
                ScheduledTask(
                    id = UUID.randomUUID().toString(),
                    kind = ScheduledTaskKind.MACRO,
                    label = macro.name,
                    macroId = macro.id,
                    macroName = macro.name,
                    recurrence = ScheduleRecurrence.CRON,
                    cronExpression = cron,
                    sourceKey = sourceKey,
                    createdAtMillis = System.currentTimeMillis()
                )
            }
            store.upsert(task)
            scheduler.arm(task)
        }

        // Un-mirror tasks whose macro no longer carries a live cron trigger.
        for (task in store.snapshot()) {
            val key = task.sourceKey ?: continue
            if (key !in liveKeys) {
                store.delete(task.id)
                scheduler.cancel(task.id)
            }
        }
    }

    companion object {
        const val CRON_PREFIX = "cron:"
        const val SOURCE_PREFIX = "macro:"
    }
}
