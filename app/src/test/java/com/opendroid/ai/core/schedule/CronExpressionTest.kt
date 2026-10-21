package com.opendroid.ai.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class CronExpressionTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    @Test
    fun `parses the routine engine's weekday cron`() {
        val cron = CronExpression.parse("0 9 * * 1-5")
        assertNotNull(cron)
        // Monday 09:00 matches, Saturday 09:00 does not, Monday 10:00 does not.
        assertTrue(cron!!.matches(at(2026, 10, 26, 9, 0))) // Monday
        assertFalse(cron.matches(at(2026, 10, 24, 9, 0))) // Saturday
        assertFalse(cron.matches(at(2026, 10, 26, 10, 0)))
    }

    @Test
    fun `parses the routine engine's daily cron`() {
        val cron = CronExpression.parse("0 18 * * *")!!
        assertTrue(cron.matches(at(2026, 10, 24, 18, 0))) // any day
        assertFalse(cron.matches(at(2026, 10, 24, 18, 1)))
    }

    @Test
    fun `sunday is both 0 and 7`() {
        val sunday = at(2026, 10, 25, 8, 0) // Sunday 25 Oct 2026
        assertTrue(CronExpression.parse("0 8 * * 0")!!.matches(sunday))
        assertTrue(CronExpression.parse("0 8 * * 7")!!.matches(sunday))
        assertFalse(CronExpression.parse("0 8 * * 0")!!.matches(at(2026, 10, 26, 8, 0)))
    }

    @Test
    fun `supports steps, ranges and lists`() {
        val cron = CronExpression.parse("*/15 9-17 * * *")!!
        assertTrue(cron.matches(at(2026, 10, 21, 9, 0)))
        assertTrue(cron.matches(at(2026, 10, 21, 17, 45)))
        assertFalse(cron.matches(at(2026, 10, 21, 9, 10)))
        assertFalse(cron.matches(at(2026, 10, 21, 18, 0)))

        val list = CronExpression.parse("0 8,20 * * *")!!
        assertTrue(list.matches(at(2026, 10, 21, 8, 0)))
        assertTrue(list.matches(at(2026, 10, 21, 20, 0)))
        assertFalse(list.matches(at(2026, 10, 21, 9, 0)))
    }

    @Test
    fun `day-of-month and day-of-week combine with the vixie OR rule`() {
        // "1st of the month OR any Monday"
        val cron = CronExpression.parse("0 9 1 * 1")!!
        assertTrue(cron.matches(at(2026, 11, 1, 9, 0))) // 1 Nov 2026 (a Sunday) — dom matches
        assertTrue(cron.matches(at(2026, 10, 26, 9, 0))) // a Monday — dow matches
        assertFalse(cron.matches(at(2026, 10, 27, 9, 0))) // a Tuesday, not the 1st
    }

    @Test
    fun `rejects malformed expressions`() {
        assertNull(CronExpression.parse(""))
        assertNull(CronExpression.parse("0 9 * *")) // too few fields
        assertNull(CronExpression.parse("0 9 * * * *")) // too many
        assertNull(CronExpression.parse("61 9 * * *")) // minute out of range
        assertNull(CronExpression.parse("0 24 * * *")) // hour out of range
        assertNull(CronExpression.parse("0 9 32 * *")) // dom out of range
        assertNull(CronExpression.parse("0 9 * 13 *")) // month out of range
        assertNull(CronExpression.parse("0 9 * * 8")) // dow out of range
        assertNull(CronExpression.parse("x 9 * * *")) // not a number
        assertNull(CronExpression.parse("0 9 * * 5-1")) // reversed range
        assertNull(CronExpression.parse("*/0 9 * * *")) // zero step
    }

    @Test
    fun `next fire after finds the next matching minute`() {
        val cron = CronExpression.parse("30 6 * * *")!!
        val after = at(2026, 10, 21, 6, 29).toInstant().toEpochMilli()
        assertEquals(
            at(2026, 10, 21, 6, 30).toInstant().toEpochMilli(),
            cron.nextFireAfter(after, zone)
        )
        // Strictly after: asking at exactly the fire time gives tomorrow.
        val atFire = at(2026, 10, 21, 6, 30).toInstant().toEpochMilli()
        assertEquals(
            at(2026, 10, 22, 6, 30).toInstant().toEpochMilli(),
            cron.nextFireAfter(atFire, zone)
        )
    }

    @Test
    fun `fall-back day generates both passes through the repeated hour`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val cron = CronExpression.parse("30 2 * * *")!!
        // Sun 25 Oct 2026: Berlin falls back 03:00 CEST -> 02:00 CET, so
        // local 02:30 occurs twice — 00:30Z (CEST) and 01:30Z (CET).
        val beforeFirst = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
        val first = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        val second = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        assertEquals(first, cron.nextFireAfter(beforeFirst, berlin))
        // Asked again once the first pass has fired, the next fire is the
        // second pass through 02:30 — not tomorrow's.
        assertEquals(second, cron.nextFireAfter(first, berlin))
    }
}
