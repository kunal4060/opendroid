package com.opendroid.ai.widget

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opendroid.ai.core.llm.RoutingStatus
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.HabitRoutine

/**
 * Keys of the Glance widget's Preferences state.
 *
 * The widget process never opens Room: [WidgetStateSync] (running in the app
 * process, on the app's own repositories) distils the live data into these
 * flat keys, and the widget renders whatever is here. That split is what
 * keeps the widget honest — every string below was computed from real rows,
 * and when there is no routine/briefing/task yet the key is simply absent
 * and the widget shows its genuine empty state.
 */
object OpenDroidWidgetKeys {
    val NEXT_ROUTINE_ID = stringPreferencesKey("next_routine_id")
    val NEXT_ROUTINE_NAME = stringPreferencesKey("next_routine_name")
    val NEXT_ROUTINE_TRIGGER = stringPreferencesKey("next_routine_trigger")
    val LAST_TASK_TEXT = stringPreferencesKey("last_task_text")
    val LAST_BRIEFING_AT = longPreferencesKey("last_briefing_at")
    val ROUTING_LABEL = stringPreferencesKey("routing_label")
}

/** Everything the widget renders, distilled from live app data. */
data class WidgetContent(
    val nextRoutineId: String? = null,
    val nextRoutineName: String? = null,
    val nextRoutineTrigger: String? = null,
    val lastTaskText: String? = null,
    val lastBriefingAt: Long? = null,
    val routingLabel: String = ""
)

/**
 * Pure distillation of repository data into [WidgetContent]. Kept free of
 * Android and Glance types so the choices the widget makes — which routine
 * is "next", how the last task reads, how routing is labelled — are unit
 * testable on the JVM.
 */
object WidgetContentMapper {

    /**
     * The routine the widget offers to run: among the active routines, one
     * that actually has a schedule first (a routine with a trigger is the
     * one that is "coming up"), then most recently detected — the same
     * order [com.opendroid.ai.data.db.dao.HabitDao.getActiveRoutinesFlow]
     * already returns, kept stable here so the pick is testable.
     */
    fun pickNextRoutine(activeRoutines: List<HabitRoutine>): HabitRoutine? {
        if (activeRoutines.isEmpty()) return null
        val scheduled = activeRoutines.filter { it.triggerCron.isNotBlank() || it.triggerLabel.isNotBlank() }
        val pool = scheduled.ifEmpty { activeRoutines }
        return pool.maxByOrNull { it.lastDetectedAt }
    }

    /** One-line summary of the most recent executed step, or null if none. */
    fun lastTaskText(latestTask: TaskHistoryEntity?): String? {
        if (latestTask == null) return null
        val description = latestTask.description.trim()
        if (description.isBlank()) return null
        return if (latestTask.success) "Done: $description" else "Failed: $description"
    }

    /** Short routing label for the widget header, from the live A8 status. */
    fun routingLabel(status: RoutingStatus): String = when (status) {
        is RoutingStatus.Cloud -> "Cloud · ${status.provider}"
        is RoutingStatus.OnDevice -> "On-device · ${status.backend}"
        is RoutingStatus.OfflineWillUseOnDevice -> "Offline · on-device ready"
        RoutingStatus.OfflineNoRoute -> "Offline · no route"
    }

    fun content(
        activeRoutines: List<HabitRoutine>,
        latestTask: TaskHistoryEntity?,
        lastBriefingAt: Long?,
        routingStatus: RoutingStatus
    ): WidgetContent {
        val next = pickNextRoutine(activeRoutines)
        return WidgetContent(
            nextRoutineId = next?.id,
            nextRoutineName = next?.name,
            nextRoutineTrigger = next?.triggerLabel?.takeIf { it.isNotBlank() },
            lastTaskText = lastTaskText(latestTask),
            lastBriefingAt = lastBriefingAt,
            routingLabel = routingLabel(routingStatus)
        )
    }
}
