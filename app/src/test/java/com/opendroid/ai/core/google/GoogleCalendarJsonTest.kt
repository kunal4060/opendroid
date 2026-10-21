package com.opendroid.ai.core.google

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarJsonTest {

    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    private val listResponse = """
        {
          "kind": "calendar#events",
          "items": [
            {
              "id": "evt1",
              "status": "confirmed",
              "summary": "ACM Meet & Greet",
              "location": "CB G-17",
              "start": {"dateTime": "2026-10-22T11:00:00+05:30", "timeZone": "Asia/Kolkata"},
              "end": {"dateTime": "2026-10-22T13:00:00+05:30", "timeZone": "Asia/Kolkata"}
            },
            {
              "id": "evt2",
              "status": "confirmed",
              "summary": "Dussehra holiday",
              "start": {"date": "2026-10-20"},
              "end": {"date": "2026-10-21"}
            },
            {
              "id": "evt3",
              "status": "cancelled",
              "summary": "Cancelled sync",
              "start": {"dateTime": "2026-10-23T09:00:00+05:30"},
              "end": {"dateTime": "2026-10-23T09:30:00+05:30"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `timed event parses with offset datetimes`() {
        val events = GoogleCalendarJson.parseEvents(listResponse, kolkata)
        val timed = events.first { it.id == "evt1" }
        assertEquals("ACM Meet & Greet", timed.title)
        assertEquals("CB G-17", timed.location)
        assertEquals(false, timed.allDay)
        assertEquals(
            OffsetDateTime.parse("2026-10-22T11:00:00+05:30").toInstant().toEpochMilli(),
            timed.startMillis
        )
        assertEquals(
            OffsetDateTime.parse("2026-10-22T13:00:00+05:30").toInstant().toEpochMilli(),
            timed.endMillis
        )
        assertEquals(CalendarEventSource.GOOGLE_API, timed.source)
    }

    @Test
    fun `all-day event parses date at start of day in the given zone`() {
        val events = GoogleCalendarJson.parseEvents(listResponse, kolkata)
        val allDay = events.first { it.id == "evt2" }
        assertTrue(allDay.allDay)
        assertEquals(
            LocalDate.parse("2026-10-20").atStartOfDay(kolkata).toInstant().toEpochMilli(),
            allDay.startMillis
        )
    }

    @Test
    fun `cancelled events are dropped`() {
        val events = GoogleCalendarJson.parseEvents(listResponse, kolkata)
        assertEquals(listOf("evt1", "evt2"), events.map { it.id })
    }

    @Test
    fun `missing items yields an empty list`() {
        assertEquals(emptyList<GoogleCalendarEvent>(), GoogleCalendarJson.parseEvents("{}"))
    }

    @Test
    fun `event without summary gets a placeholder title`() {
        val body = """{"id":"x1","start":{"dateTime":"2026-10-22T11:00:00+05:30"}}"""
        val root = Json.parseToJsonElement(body).jsonObject
        val event = GoogleCalendarJson.parseEvent(root, kolkata)
        assertEquals("(No title)", event?.title)
        assertEquals(event?.startMillis, event?.endMillis)
    }

    @Test
    fun `create body carries rfc3339 datetimes with the zone`() {
        val start = Instant.parse("2026-10-22T05:30:00Z").toEpochMilli()
        val draft = NewCalendarEvent(
            title = "Dentist",
            location = "Clinic",
            startMillis = start,
            endMillis = start + 60 * 60 * 1000L
        )
        val body = GoogleCalendarJson.buildCreateEventJson(draft, kolkata)
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("Dentist", root["summary"]?.jsonPrimitive?.content)
        assertEquals("Clinic", root["location"]?.jsonPrimitive?.content)
        val startObj = root["start"]?.jsonObject
        assertEquals("2026-10-22T11:00:00+05:30", startObj?.get("dateTime")?.jsonPrimitive?.content)
        assertEquals("Asia/Kolkata", startObj?.get("timeZone")?.jsonPrimitive?.content)
        val endObj = root["end"]?.jsonObject
        assertEquals("2026-10-22T12:00:00+05:30", endObj?.get("dateTime")?.jsonPrimitive?.content)
        assertNull(root["description"])
    }

    @Test
    fun `timeMin formats as an rfc3339 instant`() {
        val millis = Instant.parse("2026-10-21T03:00:00Z").toEpochMilli()
        assertEquals("2026-10-21T03:00:00Z", GoogleCalendarJson.formatTimeMin(millis))
    }
}
