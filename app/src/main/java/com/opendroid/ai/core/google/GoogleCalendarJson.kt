package com.opendroid.ai.core.google

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure mapping between Google Calendar API v3 JSON and [GoogleCalendarEvent].
 * No Android or network types here, so the mapping is unit-testable on the JVM.
 */
object GoogleCalendarJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses an `events.list` response body into events, in API order. */
    fun parseEvents(responseBody: String, zone: ZoneId = ZoneId.systemDefault()): List<GoogleCalendarEvent> {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element -> parseEvent(element.jsonObject, zone) }
    }

    /** Parses one event resource (also the shape `events.insert` returns). */
    fun parseEvent(item: JsonObject, zone: ZoneId = ZoneId.systemDefault()): GoogleCalendarEvent? {
        if (item.string("status") == "cancelled") return null
        val id = item.string("id") ?: return null
        val start = item["start"]?.jsonObject ?: return null
        val end = item["end"]?.jsonObject
        val allDay = start["date"] != null
        val startMillis = parseTimePoint(start, zone) ?: return null
        val endMillis = end?.let { parseTimePoint(it, zone) } ?: startMillis
        return GoogleCalendarEvent(
            id = id,
            title = item.string("summary")?.takeIf { it.isNotBlank() } ?: "(No title)",
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            location = item.string("location"),
            description = item.string("description"),
            calendarName = null,
            source = CalendarEventSource.GOOGLE_API
        )
    }

    /** Builds the `events.insert` request body for [event]. */
    fun buildCreateEventJson(event: NewCalendarEvent, zone: ZoneId = ZoneId.systemDefault()): String {
        val body = buildJsonObject {
            put("summary", event.title)
            event.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            event.location?.takeIf { it.isNotBlank() }?.let { put("location", it) }
            putJsonObject("start") {
                put("dateTime", formatTimePoint(event.startMillis, zone))
                put("timeZone", zone.id)
            }
            putJsonObject("end") {
                put("dateTime", formatTimePoint(event.endMillis, zone))
                put("timeZone", zone.id)
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    /** RFC 3339 timestamp for an instant, as the API's `timeMin` parameter expects. */
    fun formatTimeMin(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))

    private fun formatTimePoint(millis: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        )

    private fun parseTimePoint(point: JsonObject, zone: ZoneId): Long? {
        point.string("dateTime")?.let { raw ->
            return try {
                OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
        point.string("date")?.let { raw ->
            return try {
                LocalDate.parse(raw).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }
}
