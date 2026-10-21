package com.opendroid.ai.core.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure due-time math for [ScheduledTask] — no Android dependencies, so the
 * whole firing policy is unit-testable.
 *
 * Vocabulary:
 * - An **occurrence** is a scheduled fire instant derived from the task's
 *   wall-clock fields in a given time zone.
 * - [nextFireAfter] is what the alarm lane arms.
 * - [dueOccurrence] is what the sweep lane / executor act on: the most
 *   recent occurrence that has not fired yet AND is still fresh enough to
 *   run. Freshness policy (from the C4 research):
 *   - Recurring tasks: an occurrence more than [LATE_EXECUTION_WINDOW_MILLIS]
 *     old is skipped, not executed — a 7 AM task must not suddenly run at
 *     3 PM because the phone was off; the next occurrence will come.
 *   - One-shot tasks: they fire late (with the run marked delayed) up to
 *     [ONE_SHOT_STALE_MILLIS] overdue; beyond that they are [isMissedOneShot]
 *     — marked missed, never executed blindly.
 */
object NextFireCalculator {

    /** Recurring occurrences older than this are skipped, not run late. */
    const val LATE_EXECUTION_WINDOW_MILLIS: Long = 60L * 60L * 1000L // 1 hour

    /** One-shot tasks older than this are missed, not run late. */
    const val ONE_SHOT_STALE_MILLIS: Long = 24L * 60L * 60L * 1000L // 24 hours

    /** How far back occurrence searches look (covers weekly + slack). */
    private const val SEARCH_BACK_DAYS: Long = 8

    /** The next scheduled fire instant strictly after [afterMillis], or null if none. */
    fun nextFireAfter(task: ScheduledTask, afterMillis: Long, zone: ZoneId): Long? {
        if (task.recurrence == ScheduleRecurrence.CRON) {
            val cron = task.cronExpression?.let { CronExpression.parse(it) } ?: return null
            return cron.nextFireAfter(afterMillis, zone)
        }
        if (task.recurrence == ScheduleRecurrence.ONCE) {
            // Derived directly from the stored date: a one-shot months
            // ahead still has a next fire (a fixed day-scan window would
            // return null for it and nothing would ever be armed).
            val epochDay = task.dateEpochDay ?: return null
            val candidate = fireInstantOn(task, LocalDate.ofEpochDay(epochDay), zone)
            return candidate.takeIf { it > afterMillis }
        }
        val afterDate = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        if (task.recurrence == ScheduleRecurrence.WEEKLY) {
            // Jump straight to the next matching weekday (this week, or
            // next week when today's slot has already passed).
            val dow = task.dayOfWeek ?: return null
            val daysAhead = (dow - afterDate.dayOfWeek.value + 7) % 7
            val thisWeek = fireInstantOn(task, afterDate.plusDays(daysAhead.toLong()), zone)
            if (thisWeek > afterMillis) return thisWeek
            return fireInstantOn(task, afterDate.plusDays(daysAhead + 7L), zone)
        }
        // DAILY / WEEKDAYS: a matching day is never more than a week out.
        for (offset in 0..7) {
            val date = afterDate.plusDays(offset.toLong())
            if (!dayMatches(task, date)) continue
            val candidate = fireInstantOn(task, date, zone)
            if (candidate > afterMillis) return candidate
        }
        return null
    }

    /** The most recent scheduled occurrence at or before [atMillis], or null if none. */
    fun occurrenceAtOrBefore(task: ScheduledTask, atMillis: Long, zone: ZoneId): Long? {
        if (task.recurrence == ScheduleRecurrence.CRON) {
            val cron = task.cronExpression?.let { CronExpression.parse(it) } ?: return null
            // Walk forward from the search horizon, keeping the last
            // occurrence that is still <= atMillis.
            var cursor = atMillis - SEARCH_BACK_DAYS * 24L * 60L * 60L * 1000L
            var last: Long? = null
            var guard = 0
            while (guard++ < 20_000) {
                val next = cron.nextFireAfter(cursor, zone) ?: break
                if (next > atMillis) break
                last = next
                cursor = next
            }
            return last
        }
        if (task.recurrence == ScheduleRecurrence.ONCE) {
            // Derived directly from the stored date, regardless of age:
            // a one-shot missed while the phone was off for weeks must
            // still be found here, or it can never be marked missed.
            val epochDay = task.dateEpochDay ?: return null
            val candidate = fireInstantOn(task, LocalDate.ofEpochDay(epochDay), zone)
            return candidate.takeIf { it <= atMillis }
        }
        val atDate = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        for (offset in 0..SEARCH_BACK_DAYS) {
            val date = atDate.minusDays(offset)
            if (!dayMatches(task, date)) continue
            val candidate = fireInstantOn(task, date, zone)
            if (candidate <= atMillis) return candidate
        }
        return null
    }

    /**
     * The occurrence that should fire at [nowMillis], or null when nothing
     * is due: the latest occurrence, not already fired, still inside its
     * freshness window. Disabled tasks are never due.
     */
    fun dueOccurrence(task: ScheduledTask, nowMillis: Long, zone: ZoneId): Long? {
        if (!task.enabled) return null
        if (task.status == ScheduledTaskStatus.DONE || task.status == ScheduledTaskStatus.MISSED) return null
        val occurrence = occurrenceAtOrBefore(task, nowMillis, zone) ?: return null
        // An occurrence earlier than the task's own creation can never be
        // due: the freshness windows exist for the phone-was-off case, but
        // applied to a task created minutes ago (a DAILY 09:00 task created
        // at 09:30) they would fire it the same morning at a time the user
        // never asked for. Legacy rows carry createdAtMillis = 0, which
        // keeps this a no-op for them.
        if (occurrence < task.createdAtMillis) return null
        if (occurrence == task.lastFiredOccurrenceMillis) return null
        val age = nowMillis - occurrence
        return when (task.recurrence) {
            ScheduleRecurrence.ONCE -> if (age <= ONE_SHOT_STALE_MILLIS) occurrence else null
            else -> if (age <= LATE_EXECUTION_WINDOW_MILLIS) occurrence else null
        }
    }

    /** True when a one-shot task's fire time is past the staleness cap and it never fired. */
    fun isMissedOneShot(task: ScheduledTask, nowMillis: Long, zone: ZoneId): Boolean {
        if (task.recurrence != ScheduleRecurrence.ONCE || !task.enabled) return false
        if (task.status == ScheduledTaskStatus.DONE || task.status == ScheduledTaskStatus.MISSED) return false
        val occurrence = occurrenceAtOrBefore(task, nowMillis, zone) ?: return false
        if (occurrence == task.lastFiredOccurrenceMillis) return false
        return nowMillis - occurrence > ONE_SHOT_STALE_MILLIS
    }

    private fun dayMatches(task: ScheduledTask, date: LocalDate): Boolean = when (task.recurrence) {
        ScheduleRecurrence.ONCE -> task.dateEpochDay != null && date.toEpochDay() == task.dateEpochDay
        ScheduleRecurrence.DAILY -> true
        ScheduleRecurrence.WEEKDAYS -> date.dayOfWeek.value in 1..5 // Mon..Fri
        ScheduleRecurrence.WEEKLY -> task.dayOfWeek != null && date.dayOfWeek.value == task.dayOfWeek
        ScheduleRecurrence.CRON -> false // handled by CronExpression, never here
    }

    private fun fireInstantOn(task: ScheduledTask, date: LocalDate, zone: ZoneId): Long =
        date.atTime(LocalTime.of(task.hour, task.minute)).atZone(zone).toInstant().toEpochMilli()
}
