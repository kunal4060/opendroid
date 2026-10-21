package com.opendroid.ai.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NextFireCalculatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun task(
        recurrence: ScheduleRecurrence,
        hour: Int = 7,
        minute: Int = 0,
        dayOfWeek: Int? = null,
        date: LocalDate? = null,
        cron: String? = null,
        enabled: Boolean = true,
        status: ScheduledTaskStatus = ScheduledTaskStatus.ARMED,
        lastFiredOccurrenceMillis: Long? = null
    ) = ScheduledTask(
        id = "test",
        kind = ScheduledTaskKind.INSTRUCTION,
        label = "test",
        instruction = "do something",
        recurrence = recurrence,
        hour = hour,
        minute = minute,
        dayOfWeek = dayOfWeek,
        dateEpochDay = date?.toEpochDay(),
        cronExpression = cron,
        enabled = enabled,
        status = status,
        lastFiredOccurrenceMillis = lastFiredOccurrenceMillis
    )

    // 2026-10-21 is a Wednesday.

    @Test
    fun `daily next fire is today when the time is still ahead`() {
        val t = task(ScheduleRecurrence.DAILY, hour = 7)
        val now = millis(2026, 10, 21, 6, 30)
        assertEquals(millis(2026, 10, 21, 7, 0), NextFireCalculator.nextFireAfter(t, now, zone))
    }

    @Test
    fun `daily next fire rolls to tomorrow once the time has passed`() {
        val t = task(ScheduleRecurrence.DAILY, hour = 7)
        val now = millis(2026, 10, 21, 7, 0) // exactly at fire time: strictly after -> tomorrow
        assertEquals(millis(2026, 10, 22, 7, 0), NextFireCalculator.nextFireAfter(t, now, zone))
    }

    @Test
    fun `weekdays skips the weekend`() {
        val t = task(ScheduleRecurrence.WEEKDAYS, hour = 7)
        val fridayAfterFire = millis(2026, 10, 23, 8, 0) // Friday 23 Oct 2026
        // Next weekday occurrence is Monday 26 Oct, not Saturday.
        assertEquals(millis(2026, 10, 26, 7, 0), NextFireCalculator.nextFireAfter(t, fridayAfterFire, zone))
    }

    @Test
    fun `weekly fires on the configured day of week`() {
        val t = task(ScheduleRecurrence.WEEKLY, hour = 9, dayOfWeek = 1) // Mondays
        val wednesday = millis(2026, 10, 21, 10, 0)
        assertEquals(millis(2026, 10, 26, 9, 0), NextFireCalculator.nextFireAfter(t, wednesday, zone))
    }

    @Test
    fun `once fires only on its date`() {
        val t = task(ScheduleRecurrence.ONCE, hour = 7, date = LocalDate.of(2026, 10, 25))
        assertEquals(
            millis(2026, 10, 25, 7, 0),
            NextFireCalculator.nextFireAfter(t, millis(2026, 10, 21, 0, 0), zone)
        )
        assertNull(NextFireCalculator.nextFireAfter(t, millis(2026, 10, 25, 7, 0), zone))
    }

    @Test
    fun `due occurrence is the latest unfired occurrence inside the window`() {
        val t = task(ScheduleRecurrence.DAILY, hour = 7)
        val now = millis(2026, 10, 21, 7, 20) // 20 minutes after today's fire time
        assertEquals(millis(2026, 10, 21, 7, 0), NextFireCalculator.dueOccurrence(t, now, zone))
    }

    @Test
    fun `an occurrence that already fired is not due again`() {
        val fired = millis(2026, 10, 21, 7, 0)
        val t = task(ScheduleRecurrence.DAILY, hour = 7, lastFiredOccurrenceMillis = fired)
        assertNull(NextFireCalculator.dueOccurrence(t, millis(2026, 10, 21, 7, 20), zone))
        // ...but tomorrow's occurrence becomes due normally.
        assertEquals(
            millis(2026, 10, 22, 7, 0),
            NextFireCalculator.dueOccurrence(t, millis(2026, 10, 22, 7, 5), zone)
        )
    }

    @Test
    fun `a recurring occurrence older than the late window is skipped, not run`() {
        val t = task(ScheduleRecurrence.DAILY, hour = 7)
        val now = millis(2026, 10, 21, 12, 0) // 5 hours late — beyond the 1h window
        assertNull(NextFireCalculator.dueOccurrence(t, now, zone))
    }

    @Test
    fun `a one-shot fires late within 24 hours and is missed after that`() {
        val t = task(ScheduleRecurrence.ONCE, hour = 7, date = LocalDate.of(2026, 10, 21))
        val sixHoursLate = millis(2026, 10, 21, 13, 0)
        assertEquals(millis(2026, 10, 21, 7, 0), NextFireCalculator.dueOccurrence(t, sixHoursLate, zone))
        assertFalse(NextFireCalculator.isMissedOneShot(t, sixHoursLate, zone))

        val twentyFiveHoursLate = millis(2026, 10, 22, 8, 0)
        assertNull(NextFireCalculator.dueOccurrence(t, twentyFiveHoursLate, zone))
        assertTrue(NextFireCalculator.isMissedOneShot(t, twentyFiveHoursLate, zone))
    }

    @Test
    fun `disabled and terminal tasks are never due`() {
        val disabled = task(ScheduleRecurrence.DAILY, hour = 7, enabled = false)
        assertNull(NextFireCalculator.dueOccurrence(disabled, millis(2026, 10, 21, 7, 5), zone))

        val done = task(ScheduleRecurrence.DAILY, hour = 7, status = ScheduledTaskStatus.DONE)
        assertNull(NextFireCalculator.dueOccurrence(done, millis(2026, 10, 21, 7, 5), zone))
    }

    @Test
    fun `cron recurrence delegates to the cron expression`() {
        val t = task(ScheduleRecurrence.CRON, cron = "0 9 * * 1-5")
        val saturday = millis(2026, 10, 24, 10, 0)
        // Next weekday 09:00 after Saturday is Monday 26 Oct.
        assertEquals(millis(2026, 10, 26, 9, 0), NextFireCalculator.nextFireAfter(t, saturday, zone))
        // And it is due on a weekday morning shortly after 09:00.
        assertEquals(
            millis(2026, 10, 26, 9, 0),
            NextFireCalculator.dueOccurrence(t, millis(2026, 10, 26, 9, 10), zone)
        )
    }

    @Test
    fun `wall clock fields follow the zone across a DST boundary`() {
        // America/New_York springs forward on 2026-03-08: a 07:00 daily
        // task must still fire at 07:00 local, not drift by an hour.
        val ny = ZoneId.of("America/New_York")
        val t = task(ScheduleRecurrence.DAILY, hour = 7)
        val beforeDst = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, ny).toInstant().toEpochMilli()
        val next = NextFireCalculator.nextFireAfter(t, beforeDst, ny)
        val nextLocal = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next!!), ny)
        assertEquals(2026, nextLocal.year)
        assertEquals(3, nextLocal.monthValue)
        assertEquals(8, nextLocal.dayOfMonth)
        assertEquals(7, nextLocal.hour)
        assertEquals(0, nextLocal.minute)
    }
}
