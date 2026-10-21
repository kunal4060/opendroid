package com.opendroid.ai.core.schedule

import kotlinx.serialization.Serializable

/**
 * C4 — a user-created (or macro-materialised) agent task that fires on a
 * wall-clock schedule.
 *
 * Persistence note: schedules live in a DataStore JSON file owned by
 * [ScheduledTaskStore], NOT in Room — C4 is forbidden from changing the Room
 * schema, and a schedule is small, self-contained state that fits the
 * file-backed model (same split the settings store already uses).
 *
 * Wall-clock fields ([hour]/[minute]/[dayOfWeek]/[dateEpochDay]/
 * [cronExpression]) are the source of truth; absolute fire instants are always
 * derived from them by [NextFireCalculator] in the device's current time zone,
 * so a time-zone change or DST shift moves the fire time with the wall clock
 * instead of stranding an epoch timestamp in the wrong hour.
 */
@Serializable
enum class ScheduledTaskKind {
    /** Run a free-text instruction through the real agent loop. */
    INSTRUCTION,

    /** Run a saved macro's steps through the ActionSequenceExecutor. */
    MACRO
}

@Serializable
enum class ScheduleRecurrence {
    ONCE,
    DAILY,
    WEEKDAYS,
    WEEKLY,

    /** Fire times come from [ScheduledTask.cronExpression] (5-field cron). */
    CRON
}

@Serializable
enum class ScheduledTaskStatus {
    /** Armed: will fire at the next occurrence. */
    ARMED,

    /** A run has been claimed and is executing right now. */
    RUNNING,

    /**
     * The last run stopped at the approval gate: under the effective
     * AutoMode the plan was not auto-approvable, so nothing executed
     * silently. The plan (instruction kind) waits in the task's chat
     * session for the user to approve it there.
     */
    AWAITING_APPROVAL,

    /** Terminal: a one-shot task that completed. */
    DONE,

    /** Terminal: a one-shot task whose fire time passed >24h ago unrun. */
    MISSED,

    /** The last run failed (or a recurring task's last attempt failed). */
    FAILED
}

@Serializable
data class ScheduledTask(
    val id: String,
    val kind: ScheduledTaskKind,
    /** Short human label shown in lists and notifications. */
    val label: String,
    /** INSTRUCTION payload: the free-text instruction for the agent loop. */
    val instruction: String = "",
    /** MACRO payload: id of the MacroEntity to run. */
    val macroId: String? = null,
    /** MACRO payload: name snapshot (fallback lookup + display). */
    val macroName: String? = null,
    val recurrence: ScheduleRecurrence = ScheduleRecurrence.ONCE,
    /** Local wall-clock hour (0-23) for preset recurrences. */
    val hour: Int = 9,
    /** Local wall-clock minute (0-59) for preset recurrences. */
    val minute: Int = 0,
    /** WEEKLY only: java.time.DayOfWeek value (1 = Monday .. 7 = Sunday). */
    val dayOfWeek: Int? = null,
    /** ONCE only: the fire date as a java.time.LocalDate epoch day. */
    val dateEpochDay: Long? = null,
    /** CRON only: 5-field cron expression, e.g. "0 9 * * 1-5". */
    val cronExpression: String? = null,
    val enabled: Boolean = true,
    val status: ScheduledTaskStatus = ScheduledTaskStatus.ARMED,
    /** Chat session this task's runs write into; created lazily on first run. */
    val sessionId: String? = null,
    /** When the current/last run claimed the task (stuck-run detection). */
    val claimedAtMillis: Long? = null,
    val lastRunAtMillis: Long? = null,
    /**
     * The scheduled occurrence (epoch millis) the last firing was for.
     * This is the dedupe key between the alarm lane and the sweep lane:
     * an occurrence that has already fired never fires twice.
     */
    val lastFiredOccurrenceMillis: Long? = null,
    val lastResultSummary: String? = null,
    /**
     * "macro:<macroId>" for tasks materialised from a macro's `cron:`
     * trigger by [MacroScheduleSync]; null for user-created tasks.
     */
    val sourceKey: String? = null,
    val createdAtMillis: Long = 0L
) {
    /** True while the task still has future occurrences worth arming. */
    val isTerminal: Boolean
        get() = status == ScheduledTaskStatus.DONE || status == ScheduledTaskStatus.MISSED ||
            (recurrence == ScheduleRecurrence.ONCE && !enabled &&
                (status == ScheduledTaskStatus.FAILED || status == ScheduledTaskStatus.AWAITING_APPROVAL))
}
