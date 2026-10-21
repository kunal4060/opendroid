package com.opendroid.ai.core.memory

import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.StepStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Outcome of one recorded agent action, as shown by the audit-log viewer (B4). */
enum class AuditOutcome {
    SUCCESS,
    FAILED,
    DENIED
}

/**
 * Shared audit-log vocabulary for the `task_history` store.
 *
 * There is no outcome column in `task_history` (and B4 must not change the
 * schema), so an entry's outcome is derived from the two fields the execution
 * chain already writes:
 *  - `success = true`                                   -> [AuditOutcome.SUCCESS]
 *  - `success = false`, error starts with [DENIED_PREFIX] -> [AuditOutcome.DENIED]
 *  - any other `success = false`                        -> [AuditOutcome.FAILED]
 *
 * [DENIED_PREFIX] is the marker the approval gate writes when the user rejects
 * a proposed plan (see AgentLoop's denial path). The DAO's filtered query
 * matches the same literal in SQL — keep the two in sync.
 */
object AuditLog {

    /** Error-message prefix that marks a history row as a user denial. */
    const val DENIED_PREFIX = "Denied by user"

    /** Full error message written for a step denied at the approval gate. */
    const val DENIED_MESSAGE = "Denied by user at the approval prompt"

    /** Action type used for the single fallback row when a denied plan has no pending steps left. */
    const val PLAN_DENIED_ACTION_TYPE = "PLAN_DENIED"

    fun outcomeOf(entry: TaskHistoryEntity): AuditOutcome = when {
        entry.success -> AuditOutcome.SUCCESS
        entry.errorMessage?.startsWith(DENIED_PREFIX) == true -> AuditOutcome.DENIED
        else -> AuditOutcome.FAILED
    }

    fun isDenied(entry: TaskHistoryEntity): Boolean = outcomeOf(entry) == AuditOutcome.DENIED

    /** One step of a denied plan, ready to be written through the normal history sink. */
    data class DeniedStepRecord(
        val stepId: String,
        val planId: String,
        val description: String,
        val actionType: String,
        val params: Map<String, String>
    )

    /**
     * Builds the history records for a plan the user just rejected.
     *
     * Only steps that never ran ([StepStatus.PENDING]) are recorded: a step
     * that already executed has its own real execution row, and writing a
     * second "denied" row for it would falsify the log. If the plan somehow
     * has no pending steps (e.g. it was re-proposed after a replan merged in
     * already-completed steps), a single plan-level record is produced so the
     * rejection itself is never silently lost.
     */
    fun deniedStepRecords(plan: Plan): List<DeniedStepRecord> {
        val pending = plan.steps.filter { it.status == StepStatus.PENDING }
        if (pending.isEmpty()) {
            return listOf(
                DeniedStepRecord(
                    stepId = "plan-denied",
                    planId = plan.planId,
                    description = plan.goal,
                    actionType = PLAN_DENIED_ACTION_TYPE,
                    params = emptyMap()
                )
            )
        }
        return pending.map { step ->
            DeniedStepRecord(
                stepId = step.stepId,
                planId = plan.planId,
                description = step.description,
                actionType = step.action,
                params = step.params
            )
        }
    }

    private val dayHeaderFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())

    /** "Today" / "Yesterday" / a full date label for the day containing [timestamp]. */
    fun dayLabel(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(dayHeaderFormatter)
        }
    }

    /**
     * Groups entries under day labels, preserving the incoming order (the DAO
     * already returns newest-first, so groups and their entries stay
     * chronological-descending).
     */
    fun groupByDay(
        entries: List<TaskHistoryEntity>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Pair<String, List<TaskHistoryEntity>>> {
        val grouped = LinkedHashMap<String, MutableList<TaskHistoryEntity>>()
        for (entry in entries) {
            grouped.getOrPut(dayLabel(entry.timestamp, now, zone)) { mutableListOf() }.add(entry)
        }
        return grouped.entries.map { (label, dayEntries) -> label to dayEntries.toList() }
    }
}
