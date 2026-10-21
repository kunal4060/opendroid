package com.opendroid.ai.core.google

/** Where a calendar event came from — the two paths are never blurred. */
enum class CalendarEventSource {
    /** Served by the Google Calendar API with the OAuth access token. */
    GOOGLE_API,

    /** Read on-device via CalendarContract from calendars synced to this phone. */
    DEVICE
}

/** One upcoming calendar event, from either the API or the on-device store. */
data class GoogleCalendarEvent(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
    val calendarName: String? = null,
    val source: CalendarEventSource
)

/** User input for creating an event through the Google Calendar API. */
data class NewCalendarEvent(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startMillis: Long,
    val endMillis: Long
)

/** One row of a Gmail list/search result (metadata only — no body fetched). */
data class GmailMessageSummary(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val dateMillis: Long?,
    val snippet: String,
    val unread: Boolean
)

/** A single opened message: metadata plus the decoded plain-text body. */
data class GmailMessageDetail(
    val id: String,
    val threadId: String,
    val from: String,
    val to: String,
    val subject: String,
    val dateMillis: Long?,
    val snippet: String,
    val bodyText: String
)

/** Failure of a Google API HTTP call; [statusCode] 401 means re-auth is needed. */
class GoogleApiException(
    val statusCode: Int,
    message: String
) : Exception(message) {
    val isUnauthorized: Boolean get() = statusCode == 401
}
