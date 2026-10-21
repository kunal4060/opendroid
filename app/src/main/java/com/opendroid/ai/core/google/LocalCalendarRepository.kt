package com.opendroid.ai.core.google

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D6 — real on-device calendar reads via [CalendarContract.Instances].
 *
 * This is the half of calendar that needs **no** Google Cloud project, no
 * OAuth and no network: Android already syncs the user's calendars
 * (including their Google calendar) to the device, and Instances expands
 * recurring events over the queried window. Results are labelled
 * [CalendarEventSource.DEVICE] everywhere — they are never presented as
 * Google API data. Until D6, the app's calendar "list" actions merely
 * opened the calendar app's UI; this repository is the first real read.
 *
 * Requires the already-declared READ_CALENDAR permission; [hasPermission]
 * lets the UI ask for it instead of failing quietly.
 */
@Singleton
class LocalCalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Events starting between now and [daysAhead] days from now, across every
     * visible calendar on the device, soonest first, capped at [limit].
     * Returns an empty list when permission is missing.
     */
    suspend fun upcomingEvents(limit: Int = 25, daysAhead: Long = 30): List<GoogleCalendarEvent> {
        if (!hasPermission()) return emptyList()
        return withContext(Dispatchers.IO) {
            val begin = System.currentTimeMillis()
            val end = begin + daysAhead * 24L * 60L * 60L * 1000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
                ContentUris.appendId(builder, begin)
                ContentUris.appendId(builder, end)
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )
            val events = mutableListOf<GoogleCalendarEvent>()
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val locationIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val calendarIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext() && events.size < limit) {
                    val title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: "(No title)"
                    events += GoogleCalendarEvent(
                        id = "device-${cursor.getLong(idIdx)}-${cursor.getLong(beginIdx)}",
                        title = title,
                        startMillis = cursor.getLong(beginIdx),
                        endMillis = cursor.getLong(endIdx),
                        allDay = cursor.getInt(allDayIdx) != 0,
                        location = cursor.getString(locationIdx),
                        calendarName = cursor.getString(calendarIdx),
                        source = CalendarEventSource.DEVICE
                    )
                }
            }
            events
        }
    }
}
