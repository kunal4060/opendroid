package com.opendroid.ai.core.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A tiny 5-field cron evaluator: `minute hour day-of-month month day-of-week`.
 *
 * It exists so the cron strings the app already produces — the routine
 * engine's `triggerCron` values ("0 9 * * 1-5") and the ones
 * `SCHEDULE_MACRO` writes into a macro's trigger — finally MEAN something:
 * before C4 those strings were write-only and nothing ever fired.
 *
 * Supported field syntax (deliberately capped, this is not a cron library):
 * `*`, a single value `5`, a range `1-5`, a step `* /15` or `1-30/2`, and
 * comma-separated lists of those. Day-of-week accepts 0-7 (0 and 7 = Sunday).
 *
 * Day matching follows the Vixie cron rule: when both day-of-month and
 * day-of-week are restricted, a day matches if EITHER matches; when one is
 * `*`, the other alone decides.
 *
 * Pure JVM (java.time only) so the due-time math is unit-testable.
 */
class CronExpression private constructor(
    val source: String,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>, // cron numbering: 0 = Sunday .. 6 = Saturday
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean
) {

    fun matches(dateTime: ZonedDateTime): Boolean {
        if (dateTime.minute !in minutes) return false
        if (dateTime.hour !in hours) return false
        if (dateTime.monthValue !in months) return false
        val domMatch = dateTime.dayOfMonth in daysOfMonth
        // java.time: Monday=1..Sunday=7. Cron: Sunday=0, Monday=1..Saturday=6.
        val cronDow = dateTime.dayOfWeek.value % 7
        val dowMatch = cronDow in daysOfWeek
        val dayMatch = when {
            domRestricted && dowRestricted -> domMatch || dowMatch
            domRestricted -> domMatch
            dowRestricted -> dowMatch
            else -> true
        }
        return dayMatch
    }

    /**
     * The next fire instant strictly after [afterMillis], or null if none
     * exists within a year (e.g. an impossible date like Feb 31 combined
     * with restrictions that never line up). Steps minute by minute; the
     * expressions this app produces are coarse (hour-level), so the scan
     * is short in practice.
     */
    fun nextFireAfter(afterMillis: Long, zone: ZoneId): Long? {
        var cursor = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
            .withSecond(0).withNano(0).plusMinutes(1)
        val deadline = cursor.plusYears(1)
        while (cursor.isBefore(deadline)) {
            if (matches(cursor.atZone(zone))) {
                return cursor.atZone(zone).toInstant().toEpochMilli()
            }
            cursor = cursor.plusMinutes(1)
        }
        return null
    }

    companion object {
        /** Parses [expression]; returns null for anything malformed or out of range. */
        fun parse(expression: String): CronExpression? {
            val fields = expression.trim().split(Regex("\\s+"))
            if (fields.size != 5) return null
            val minutes = parseField(fields[0], 0, 59) ?: return null
            val hours = parseField(fields[1], 0, 23) ?: return null
            val dom = parseField(fields[2], 1, 31) ?: return null
            val months = parseField(fields[3], 1, 12) ?: return null
            val dowRaw = parseField(fields[4], 0, 7) ?: return null
            // Normalise cron Sunday: both 0 and 7 mean Sunday -> store 0.
            val dow = dowRaw.map { if (it == 7) 0 else it }.toSet()
            return CronExpression(
                source = expression.trim(),
                minutes = minutes,
                hours = hours,
                daysOfMonth = dom,
                months = months,
                daysOfWeek = dow,
                domRestricted = fields[2] != "*",
                dowRestricted = fields[4] != "*"
            )
        }

        private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
            val values = mutableSetOf<Int>()
            for (part in field.split(",")) {
                val stepParts = part.split("/")
                if (stepParts.size > 2) return null
                val step = if (stepParts.size == 2) {
                    stepParts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                } else {
                    1
                }
                val rangePart = stepParts[0]
                val range: IntRange = when {
                    rangePart == "*" -> min..max
                    rangePart.contains("-") -> {
                        val bounds = rangePart.split("-")
                        if (bounds.size != 2) return null
                        val lo = bounds[0].toIntOrNull() ?: return null
                        val hi = bounds[1].toIntOrNull() ?: return null
                        if (lo > hi) return null
                        lo..hi
                    }
                    else -> {
                        val single = rangePart.toIntOrNull() ?: return null
                        single..single
                    }
                }
                if (range.first < min || range.last > max) return null
                var v = range.first
                while (v <= range.last) {
                    values += v
                    v += step
                }
            }
            return values.ifEmpty { null }
        }
    }
}
