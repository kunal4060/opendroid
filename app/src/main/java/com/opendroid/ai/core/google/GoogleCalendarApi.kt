package com.opendroid.ai.core.google

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * D6 — Google Calendar API v3 over the app's shared OkHttp client.
 *
 * Reads the primary calendar's upcoming events and creates events on it —
 * always with the OAuth access token from [GoogleAuthManager], always
 * labelled [CalendarEventSource.GOOGLE_API]. A 401 surfaces as
 * [GoogleApiException.isUnauthorized] so the caller can re-authorize once
 * and turn a dead grant into a reconnect prompt, never a silent fallback.
 */
@Singleton
class GoogleCalendarApi @Inject constructor(
    private val httpClient: OkHttpClient
) {

    /** Upcoming events on the primary calendar, starting now, soonest first. */
    suspend fun listUpcomingEvents(
        accessToken: String,
        maxResults: Int = 25,
        fromMillis: Long = System.currentTimeMillis()
    ): List<GoogleCalendarEvent> {
        val url = "$BASE_URL/calendars/primary/events".toHttpUrl().newBuilder()
            .addQueryParameter("timeMin", GoogleCalendarJson.formatTimeMin(fromMillis))
            .addQueryParameter("maxResults", maxResults.toString())
            .addQueryParameter("orderBy", "startTime")
            .addQueryParameter("singleEvents", "true")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val body = executeForBody(request)
        return GoogleCalendarJson.parseEvents(body)
    }

    /** Creates [event] on the primary calendar; returns the stored event. */
    suspend fun createEvent(accessToken: String, event: NewCalendarEvent): GoogleCalendarEvent {
        val url = "$BASE_URL/calendars/primary/events".toHttpUrl()
        val payload = GoogleCalendarJson.buildCreateEventJson(event)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = executeForBody(request)
        val root = Json.parseToJsonElement(body).jsonObject
        return GoogleCalendarJson.parseEvent(root)
            ?: throw GoogleApiException(200, "Calendar API returned an unparseable created event.")
    }

    private suspend fun executeForBody(request: Request): String = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GoogleApiException(
                    response.code,
                    "Google Calendar API failed (HTTP ${response.code})."
                )
            }
            text
        }
    }

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/calendar/v3"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
