package com.opendroid.ai.actions

import com.opendroid.ai.core.util.DurationParser

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.VisionEngine
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import java.util.Calendar
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Date/time patterns the planner commonly emits for event start/end values. */
private val EVENT_DATE_TIME_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm",
    "yyyy-MM-dd HH:mm",
    "yyyy-MM-dd",
    "dd MMM yyyy HH:mm",
    "dd MMM yyyy",
    "d MMM yyyy",
    "dd/MM/yyyy HH:mm",
    "dd/MM/yyyy"
)

/**
 * Parses an event date/time value: epoch millis, an ISO-8601 instant, or
 * one of [EVENT_DATE_TIME_FORMATS]. Returns null when unparseable — callers
 * must fail honestly instead of silently substituting "now".
 */
private fun parseEventInstant(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { return it }
    try {
        return java.time.Instant.parse(trimmed).toEpochMilli()
    } catch (_: Exception) {
        // Not an ISO instant — fall through to the pattern list.
    }
    for (pattern in EVENT_DATE_TIME_FORMATS) {
        try {
            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
            sdf.isLenient = false
            val parsed = sdf.parse(trimmed)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {
            // Try the next pattern.
        }
    }
    return null
}

/** Parses a time-of-day value ("17:00", "5:30 pm", "5 pm") to hour/minute. */
private fun parseEventTimeOfDay(raw: String): Pair<Int, Int>? {
    val clean = raw.trim().lowercase()
    val amPm = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(am|pm)$""").matchEntire(clean)
    if (amPm != null) {
        var hour = amPm.groupValues[1].toInt()
        val minute = amPm.groupValues[2].toIntOrNull() ?: 0
        if (amPm.groupValues[3] == "pm" && hour != 12) hour += 12
        if (amPm.groupValues[3] == "am" && hour == 12) hour = 0
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }
    val h24 = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(clean)
    if (h24 != null) {
        val hour = h24.groupValues[1].toInt()
        val minute = h24.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }
    return null
}

/**
 * Resolves the start instant from separate `date` / `time` params: the date
 * supplies the day, the time-of-day replaces its clock time. A time with no
 * date means today at that time (tomorrow once it has passed). Returns null
 * when a supplied value is unparseable.
 */
private fun resolveDateTimeParams(dateParam: String?, timeParam: String?): Long? {
    if (dateParam == null && timeParam == null) return null
    val calendar = Calendar.getInstance()
    if (dateParam != null) {
        val dateMillis = parseEventInstant(dateParam) ?: return null
        calendar.timeInMillis = dateMillis
    }
    if (timeParam != null) {
        val (hour, minute) = parseEventTimeOfDay(timeParam) ?: return null
        val hadDate = dateParam != null
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (!hadDate && calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    return calendar.timeInMillis
}

/** Finds a writable calendar id (highest access level), or null if none. */
private fun resolveWritableCalendarId(context: Context): Long? {
    return try {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val accessIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            if (idIndex < 0) return null
            var bestId: Long? = null
            var bestAccess = -1
            while (cursor.moveToNext()) {
                val access = if (accessIndex >= 0) cursor.getInt(accessIndex) else 0
                if (access > bestAccess) {
                    bestAccess = access
                    bestId = cursor.getLong(idIndex)
                }
            }
            bestId
        }
    } catch (e: Exception) {
        null
    }
}

@Singleton
class CalendarActions @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val visionEngine: dagger.Lazy<VisionEngine>,
    private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
) {

    fun getActions(): List<Action> = listOf(
        CreateCalendarEventAction(),
        SetAlarmAction(),
        SetTimerAction(),
        AddNoteAction(memoryRepository),
        ListCalendarTodayAction(),
        ListCalendarWeekAction(),
        SetReminderAction(),
        CreateTaskAction(memoryRepository),
        ReadNotesAction(memoryRepository),
        ReadAndRememberScreenAction(visionEngine, memoryRepository),
        RecallMemoryAction(memoryRepository),
        QueryKnowledgeGraphAction(personalGrowthEngine),
        UpdatePreferenceAction(personalGrowthEngine),
        SaveSensitiveInfoAction(personalGrowthEngine)
    )

    private class CreateCalendarEventAction : Action {
        override val name: String = "CREATE_CALENDAR_EVENT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Event"

            // Use the date/time the planner actually supplied — never
            // silently substitute "now". A supplied value that cannot be
            // parsed fails honestly instead of creating a wrong event.
            val combinedStart = params["start"] ?: params["startTime"]
                ?: params["startDateTime"] ?: params["datetime"]
            val startMillis: Long = when {
                combinedStart != null -> parseEventInstant(combinedStart)
                    ?: return ActionResult(false, null, "Couldn't understand the event start time '$combinedStart'.")
                params["date"] != null || params["time"] != null ->
                    resolveDateTimeParams(params["date"], params["time"])
                        ?: return ActionResult(false, null, "Couldn't understand the event date/time provided.")
                else -> Calendar.getInstance().timeInMillis
            }
            val combinedEnd = params["end"] ?: params["endTime"]
            val endMillis: Long = when {
                combinedEnd != null -> parseEventInstant(combinedEnd)
                    ?: return ActionResult(false, null, "Couldn't understand the event end time '$combinedEnd'.")
                params["durationMinutes"] != null || params["duration"] != null -> {
                    val raw = (params["durationMinutes"] ?: params["duration"])!!
                    // `durationMinutes` is a bare minute count; `duration`
                    // is the human string the planner schema declares
                    // (default "1 hour"). A bare number keeps its historical
                    // minutes reading; anything else goes through
                    // DurationParser (the parser SET_TIMER uses) — the old
                    // code ran toLongOrNull() on the raw string, so
                    // "2 hours" silently became the flat 1-hour default.
                    // A supplied duration that parses neither way fails
                    // honestly instead of booking the wrong length.
                    val millis = raw.trim().toLongOrNull()?.times(60_000L)
                        ?: DurationParser.parseToSeconds(raw)?.times(1000L)
                    millis?.let { startMillis + it }
                        ?: return ActionResult(false, null, "Couldn't understand the event duration '$raw'. Try something like '2 hours' or '30 minutes'.")
                }
                else -> startMillis + 60 * 60 * 1000 // 1 hr duration
            }
            if (endMillis <= startMillis) {
                return ActionResult(false, null, "The event end time must be after its start time.")
            }
            val calendarId = resolveWritableCalendarId(context)
                ?: return ActionResult(false, null, "No writable calendar was found on this device.")

            return try {
                val cr = context.contentResolver
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, endMillis)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                
                // Needs calendar permissions. If fails, fallback to calendar UI compose intent
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    ActionResult(true, "Your event '$title' is on the calendar!", null)
                } else {
                    throw IllegalStateException("Insert returned null URI")
                }
            } catch (e: Exception) {
                // Fallback: Open compose intent in system calendar, carrying
                // the requested start/end so the user isn't re-typing them.
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Couldn't add it directly, but I opened the calendar for you to create it.", e.localizedMessage, true)
            }
        }
    }

    private class SetAlarmAction : Action {
        override val name: String = "SET_ALARM"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val timeStr = params["time"]
                ?: return ActionResult(false, null, "Time is required. Use format like '5 am' or '7:30'")

            val label = params["label"]?.trim() ?: "OpenDroid Alarm"

            // Parse time string to hour + minute
            val parsed = parseTimeString(timeStr)
                ?: return ActionResult(false, null,
                    "Could not understand time '$timeStr'. Try formats like '5 am', '7:30', or '14:00'")

            val (hour, minute) = parsed

            // Try Method 1: AlarmClock Intent (most reliable)
            val method1 = tryAlarmClockIntent(hour, minute, label, context)
            if (method1 != null) return method1

            // Try Method 2: Open clock app as fallback
            val method2 = tryOpenClockApp(hour, minute, context)
            if (method2 != null) return method2

            // All methods failed
            val timeFormatted = formatTime(hour, minute)
            return ActionResult(false, null,
                "Could not set alarm for $timeFormatted. Please open Clock app manually.")
        }

        private fun tryAlarmClockIntent(
            hour: Int, minute: Int, label: String, context: Context
        ): ActionResult? {
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Check if any app can handle this intent
                val resolved = context.packageManager
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)

                if (resolved == null) {
                    null // no clock app can handle it
                } else {
                    context.startActivity(intent)
                    val timeFormatted = formatTime(hour, minute)
                    ActionResult(true, "Your alarm is set for $timeFormatted!", null)
                }
            } catch (e: SecurityException) {
                // Permission denied — try fallback
                android.util.Log.e("SetAlarm", "SecurityException: ${e.message}")
                null
            } catch (e: android.content.ActivityNotFoundException) {
                android.util.Log.e("SetAlarm", "No clock app: ${e.message}")
                null
            } catch (e: Exception) {
                android.util.Log.e("SetAlarm", "Intent failed: ${e.message}")
                null
            }
        }

        private fun tryOpenClockApp(
            hour: Int, minute: Int, context: Context
        ): ActionResult? {
            return try {
                // Try Google Clock, then AOSP Clock
                val clockIntent = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.deskclock")
                    ?: context.packageManager
                        .getLaunchIntentForPackage("com.android.deskclock")

                if (clockIntent != null) {
                    clockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(clockIntent)
                    val timeFormatted = formatTime(hour, minute)
                    // Non-success: opening the Clock app sets no alarm — the
                    // user still has to create it there.
                    ActionResult(false,
                        "I opened the Clock app — please set your alarm for $timeFormatted there.", null, true)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Robust time parser — handles all common formats:
         * "5 am", "5am", "5:30 pm", "17:00", "noon", "midnight",
         * "half past 6", "quarter to 8", just "7", etc.
         */
        private fun parseTimeString(input: String): Pair<Int, Int>? {
            val clean = input.lowercase().trim()
                .replace("o'clock", "").replace("hours", "").trim()

            // Natural language times
            val naturalMap = mapOf(
                "midnight" to Pair(0, 0),
                "noon" to Pair(12, 0),
                "midday" to Pair(12, 0),
                "morning" to Pair(8, 0),
                "afternoon" to Pair(14, 0),
                "evening" to Pair(18, 0),
                "night" to Pair(21, 0)
            )
            naturalMap[clean]?.let { return it }

            // "5 am", "5am", "11 pm", "11pm"
            val amPmSimple = Regex("""^(\d{1,2})\s*(am|pm|a\.m\.|p\.m\.)$""")
            amPmSimple.find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt()
                val amPm = match.groupValues[2]
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                if (hour > 23) return null
                return Pair(hour, 0)
            }

            // "5:30 am", "5:30am", "11:45 pm"
            val amPmWithMin = Regex("""^(\d{1,2})[:\.](\d{2})\s*(am|pm|a\.m\.|p\.m\.)$""")
            amPmWithMin.find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                val amPm = match.groupValues[3]
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                if (hour > 23 || minute > 59) return null
                return Pair(hour, minute)
            }

            // "17:30", "05:00", "9:45"
            val military = Regex("""^(\d{1,2})[:\.](\d{2})$""")
            military.find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                if (hour > 23 || minute > 59) return null
                return Pair(hour, minute)
            }

            // Just a number: "5", "7", "22"
            clean.toIntOrNull()?.let { hour ->
                if (hour in 0..23) return Pair(hour, 0)
            }

            // "half past 5" → 5:30
            Regex("""half past (\d{1,2})""").find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                if (hour in 0..23) return Pair(hour, 30)
            }

            // "quarter past 5" → 5:15
            Regex("""quarter past (\d{1,2})""").find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                if (hour in 0..23) return Pair(hour, 15)
            }

            // "quarter to 6" → 5:45
            Regex("""quarter to (\d{1,2})""").find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt() - 1
                if (hour < 0) hour = 23
                return Pair(hour, 45)
            }

            return null
        }

        private fun formatTime(hour: Int, minute: Int): String {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
        }
    }

    private class SetTimerAction : Action {
        override val name: String = "SET_TIMER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // A supplied-but-unparseable duration must fail, not silently
            // become a 60-second timer.
            val durationSecs = when (val raw = params["duration"]) {
                null -> 60
                else -> DurationParser.parseToSeconds(raw)
                    ?: return ActionResult(false, null, "Couldn't understand the timer duration '$raw'. Try '10 minutes' or '90 seconds'.")
            }
            val label = params["label"] ?: "OpenDroid Timer"
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, durationSecs)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Timer's running! $durationSecs seconds.", null)
            } catch (e: Exception) {
                Log.e("SetTimer", "Timer failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't start the timer.")
            }
        }
    }

    private class AddNoteAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "ADD_NOTE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: params["name"] ?: "Quick Note"
            val content = params["content"] ?: params["text"] ?: params["body"] ?: ""
            if (content.isBlank() && title == "Quick Note") {
                return ActionResult(false, null, "Note content is empty.")
            }

            val timestamp = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val dateStr = dateFormat.format(java.util.Date(timestamp))
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
            val key = "note_${sanitizedTitle}_$timestamp"
            val memoryValue = "Title: $title\nCreated: $dateStr\n$content".trim()

            return try {
                memoryRepository.saveMemory(
                    Memory(
                        key = key,
                        value = memoryValue,
                        type = MemoryType.SEMANTIC,
                        timestamp = timestamp
                    )
                )
                ActionResult(true, "Got it! Note '$title' saved.", null)
            } catch (e: Exception) {
                Log.e("AddNote", "Note failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't save that note.")
            }
        }
    }

    private class ListCalendarTodayAction : Action {
        override val name: String = "LIST_CALENDAR_TODAY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val builder = CalendarContract.CONTENT_URI.buildUpon()
                builder.appendPath("time")
                ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = builder.build()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's your calendar for today.", null)
            } catch (e: Exception) {
                Log.e("ListCalendarToday", "Calendar failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your calendar.")
            }
        }
    }

    private class ListCalendarWeekAction : Action {
        override val name: String = "LIST_CALENDAR_WEEK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val builder = CalendarContract.CONTENT_URI.buildUpon()
                builder.appendPath("time")
                ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = builder.build()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's your week at a glance.", null)
            } catch (e: Exception) {
                Log.e("ListCalendarWeek", "Calendar failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your calendar.")
            }
        }
    }

    private class SetReminderAction : Action {
        override val name: String = "SET_REMINDER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Reminder"
            // Parse the supplied time/date — previously both branches of
            // this computation assigned "now", so every reminder was set
            // for the current instant regardless of what was asked.
            val combined = params["datetime"] ?: params["start"] ?: params["startTime"]
            val startMillis: Long? = when {
                combined != null -> parseEventInstant(combined)
                    ?: return ActionResult(false, null, "Couldn't understand the reminder time '$combined'.")
                params["date"] != null || params["time"] != null ->
                    resolveDateTimeParams(params["date"], params["time"])
                        ?: return ActionResult(false, null, "Couldn't understand the reminder time provided.")
                else -> null
            }

            if (startMillis == null) {
                // No time supplied: nothing can be inserted, so hand the
                // compose UI to the user as a non-success result.
                return try {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(
                        false,
                        "I opened the calendar so you can set up your reminder '$title' — no time was given, so nothing is set yet.",
                        "Reminder time missing; compose UI opened for the user",
                        true
                    )
                } catch (e: Exception) {
                    Log.e("SetReminder", "Reminder failed: ${e.localizedMessage}")
                    ActionResult(false, null, "Couldn't set up that reminder.")
                }
            }

            // An explicit past date+time can never fire:
            // resolveDateTimeParams rolls only TIME-ONLY values forward to
            // tomorrow. Refuse a past instant honestly instead of inserting
            // a dead event and claiming it set — the same stale-occurrence
            // rejection SCHEDULE_AGENT_TASK applies to one-shot schedules.
            if (startMillis <= System.currentTimeMillis()) {
                return ActionResult(false, null, "That reminder time is in the past, so it could never alert you. Give me a future time.")
            }

            // Insert the reminder event directly; success is claimed only
            // once the row actually exists.
            val calendarId = resolveWritableCalendarId(context)
                ?: return ActionResult(false, null, "No writable calendar was found on this device.")
            return try {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, startMillis + 15 * 60 * 1000) // 15 min reminder block
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    // Attach the alert that makes it a reminder, not just an
                    // event: HAS_ALARM alone produces no notification, so
                    // without this row the "reminder" never fires. Its
                    // failure (an exception OR a null URI — the old code
                    // checked neither and claimed success regardless) must
                    // not be swallowed: roll the event back and let the
                    // outer catch hand the user the pre-filled compose UI
                    // as a non-success result.
                    val reminderValues = ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                        put(CalendarContract.Reminders.MINUTES, 0)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    val reminderUri = try {
                        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                    } catch (e: Exception) {
                        Log.w("SetReminder", "Reminder alert insert failed: ${e.localizedMessage}")
                        null
                    }
                    if (reminderUri == null) {
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            Log.w("SetReminder", "Failed to roll back alert-less reminder event: ${e.localizedMessage}")
                        }
                        throw IllegalStateException("Reminder alert could not be attached")
                    }
                    ActionResult(true, "Reminder '$title' is set.", null)
                } else {
                    throw IllegalStateException("Insert returned null URI")
                }
            } catch (e: Exception) {
                Log.e("SetReminder", "Reminder insert failed: ${e.localizedMessage}")
                // Fallback: compose UI pre-filled with the parsed time.
                try {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                        putExtra(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(false, "Couldn't save the reminder directly, but I opened the calendar with its time filled in.", e.localizedMessage, true)
                } catch (fallbackError: Exception) {
                    ActionResult(false, null, "Couldn't set up that reminder.")
                }
            }
        }
    }

    private class CreateTaskAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "CREATE_TASK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Task"
            val description = params["description"] ?: ""
            // Persist the task in the same memory store notes use — the
            // previous body stored nothing anywhere and still told the user
            // the task was created.
            val timestamp = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val dateStr = dateFormat.format(java.util.Date(timestamp))
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
            val key = "task_${sanitizedTitle}_$timestamp"
            val value = buildString {
                append("Task: $title\nCreated: $dateStr\nStatus: pending")
                if (description.isNotBlank()) append("\n$description")
            }
            return try {
                memoryRepository.saveMemory(
                    Memory(
                        key = key,
                        value = value,
                        type = MemoryType.SEMANTIC,
                        timestamp = timestamp
                    )
                )
                ActionResult(true, "Done! Task '$title' is created.", null)
            } catch (e: Exception) {
                Log.e("CreateTask", "Task save failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't save that task.")
            }
        }
    }

    private class ReadNotesAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "READ_NOTES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: params["topic"] ?: ""
            return try {
                val allSemantic = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
                val noteMemories = allSemantic.filter {
                    it.key.startsWith("note_") || it.key.startsWith("screen_note_") ||
                    it.value.contains("Title:", ignoreCase = true) || it.value.contains("Topic:", ignoreCase = true)
                }

                val matchingNotes = if (query.isNotBlank()) {
                    noteMemories.filter {
                        it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true)
                    }
                } else {
                    noteMemories
                }

                if (matchingNotes.isEmpty()) {
                    val msg = if (query.isNotBlank()) "No notes found matching '$query'." else "You don't have any saved notes yet."
                    return ActionResult(true, msg, null)
                }

                val formattedNotes = matchingNotes.sortedByDescending { it.timestamp }.take(10).joinToString("\n\n---\n\n") { memory ->
                    memory.value
                }
                ActionResult(true, "Here are your saved notes:\n\n$formattedNotes", null)
            } catch (e: Exception) {
                Log.e("ReadNotes", "Failed to read notes: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't read your notes right now.")
            }
        }
    }

    private class ReadAndRememberScreenAction(
        private val visionEngine: dagger.Lazy<VisionEngine>,
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "READ_AND_REMEMBER_SCREEN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val topic = params["topic"] ?: params["query"] ?: "important information"

            return try {
                val extractedInfo = visionEngine.get().extractAndStructureScreenInfo(topic)
                // Loop-2 Area2 #8: guard on ALL of VisionEngine's failure
                // strings, not just the capture ones — the extraction
                // failures ("...couldn't extract the details: ...") used to
                // pass this check and be saved into memory as if they were
                // the extracted notes.
                if (extractedInfo.isBlank() || VisionEngine.isFailureResult(extractedInfo)) {
                    return ActionResult(false, null, extractedInfo)
                }

                val timestamp = System.currentTimeMillis()
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                val dateStr = dateFormat.format(java.util.Date(timestamp))
                val sanitizedTopic = topic.replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
                val key = "screen_note_${sanitizedTopic}_$timestamp"
                val memoryValue = "Topic: $topic\nDate Recorded: $dateStr\n\n$extractedInfo"

                memoryRepository.saveMemory(
                    Memory(
                        key = key,
                        value = memoryValue,
                        type = MemoryType.SEMANTIC,
                        timestamp = timestamp
                    )
                )

                val confirmation = "I've read the screen and saved this to your notes:\n\n$extractedInfo"
                ActionResult.Success(
                    dataMap = mapOf(
                        "message" to confirmation,
                        "key" to key,
                        "topic" to topic,
                        "summary" to extractedInfo,
                        "saved" to "true"
                    )
                )
            } catch (e: Exception) {
                Log.e("ReadAndRemember", "Failed to read and remember screen: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't read and save the screen info right now: ${e.localizedMessage}")
            }
        }
    }

    private class RecallMemoryAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "RECALL_MEMORY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: params["topic"]
                ?: return ActionResult(false, null, "Please specify what you would like to recall.")

            return try {
                val allMemories = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
                val matches = allMemories.filter {
                    it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true)
                }

                if (matches.isEmpty()) {
                    return ActionResult(true, "I don't have any saved information or notes about '$query'.", null)
                }

                val formatted = matches.sortedByDescending { it.timestamp }.take(5).joinToString("\n\n---\n\n") { memory ->
                    memory.value
                }
                ActionResult(true, "Here is what I found about '$query':\n\n$formatted", null)
            } catch (e: Exception) {
                Log.e("RecallMemory", "Failed to recall memory: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't retrieve memory right now.")
            }
        }
    }

    private class QueryKnowledgeGraphAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "QUERY_KNOWLEDGE_GRAPH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"]?.trim().orEmpty()
            val categoryFilter = params["category"]?.uppercase()?.trim() ?: "ALL"
            val tierFilter = params["tier"]?.uppercase()?.trim() ?: "ALL"

            return try {
                val snapshot = personalGrowthEngine.get().getSnapshot()
                var matched = snapshot.findNodes(query)
                if (categoryFilter != "ALL") {
                    matched = matched.filter { it.category.name.equals(categoryFilter, ignoreCase = true) }
                }
                if (tierFilter != "ALL") {
                    matched = matched.filter { it.tier.name.equals(tierFilter, ignoreCase = true) }
                }

                if (matched.isEmpty()) {
                    return ActionResult(true, "No knowledge graph entries found matching your query.", null)
                }

                val sb = StringBuilder("Here is what I found in your Personal Knowledge Graph:\n\n")
                val grouped = matched.groupBy { it.tier }
                for ((tier, nodes) in grouped) {
                    sb.append("== Level: ${tier.name} ==\n")
                    for (node in nodes) {
                        val conf = if (node.tier == MemoryTier.LEARNED_PATTERN) " [${(node.confidence * 100).toInt()}% confidence]" else ""
                        sb.append("• [${node.category.name}] ${node.label}: ${node.summary}$conf\n")
                    }
                    sb.append("\n")
                }
                ActionResult(true, sb.toString().trim(), null)
            } catch (e: Exception) {
                Log.e("QueryKnowledgeGraph", "Failed to query knowledge graph: ${e.message}")
                ActionResult(false, null, "Couldn't query knowledge graph right now.")
            }
        }
    }

    private class UpdatePreferenceAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "UPDATE_PREFERENCE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val key = params["key"] ?: return ActionResult(false, null, "Preference key is required.")
            val value = params["value"] ?: return ActionResult(false, null, "Preference value is required.")
            val categoryStr = params["category"]?.uppercase() ?: "USER_PREFERENCE"
            val category = try {
                KnowledgeCategory.valueOf(categoryStr)
            } catch (e: Exception) {
                KnowledgeCategory.USER_PREFERENCE
            }

            return try {
                val node = personalGrowthEngine.get().recordExplicitMemory(
                    label = key,
                    summary = value,
                    category = category
                )
                ActionResult(true, "Saved preference '${node.label}' to your Long-Term Knowledge Graph: ${node.summary}", null)
            } catch (e: Exception) {
                Log.e("UpdatePreference", "Failed to update preference: ${e.message}")
                ActionResult(false, null, "Failed to save preference.")
            }
        }
    }

    private class SaveSensitiveInfoAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "SAVE_SENSITIVE_INFO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val key = params["key"] ?: return ActionResult(false, null, "Sensitive key is required.")
            val secret = params["secret"] ?: return ActionResult(false, null, "Sensitive secret value is required.")
            val label = params["label"]?.ifBlank { key } ?: key

            return try {
                val success = personalGrowthEngine.get().recordSensitiveSecret(key, secret, label)
                if (success) {
                    ActionResult(true, "Securely saved '$label' in Level 4 hardware-encrypted storage.", null)
                } else {
                    ActionResult(false, null, "Failed to write to encrypted Keystore storage.")
                }
            } catch (e: Exception) {
                Log.e("SaveSensitiveInfo", "Failed to save sensitive info: ${e.message}")
                ActionResult(false, null, "Couldn't encrypt and store sensitive data.")
            }
        }
    }
}
