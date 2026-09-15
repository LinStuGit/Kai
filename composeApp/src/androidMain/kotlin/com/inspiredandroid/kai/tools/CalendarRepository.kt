package com.inspiredandroid.kai.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TimeZone

private const val TAG = "CalendarRepository"

/** Upper bound for [CalendarRepository.listEvents] — keeps tool results small. */
private const val MAX_LISTED_EVENTS = 50

sealed class CalendarResult {
    data class Success(val eventId: Long, val title: String, val startTime: String) : CalendarResult()
    data class Error(val message: String) : CalendarResult()
}

/** One occurrence of an event as returned by [CalendarRepository.listEvents] (recurrences expanded). */
data class CalendarEventInfo(
    val eventId: Long,
    val title: String,
    val startIso: String,
    val endIso: String,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val reminderMinutes: Int?,
)

class CalendarRepository(
    private val context: Context,
    private val permissionController: PermissionController,
) {

    fun hasCalendarPermission(): Boolean {
        val hasRead = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        val hasWrite = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        return hasRead && hasWrite
    }

    fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                if (idIndex >= 0) {
                    return cursor.getLong(idIndex)
                }
            }
        }
        return null
    }

    suspend fun createEvent(
        title: String,
        startTimeIso: String,
        endTimeIso: String?,
        description: String?,
        location: String?,
        allDay: Boolean,
        reminderMinutes: Int,
    ): CalendarResult {
        Log.d(TAG, "createEvent called: title=$title, startTime=$startTimeIso")

        // Request permission if not already granted
        if (!hasCalendarPermission()) {
            Log.d(TAG, "Permission not granted, requesting...")
            val granted = permissionController.requestPermission()
            Log.d(TAG, "Permission request result: $granted")
            if (!granted) {
                return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings to create events.")
            }
        } else {
            Log.d(TAG, "Permission already granted")
        }

        val calendarId = getPrimaryCalendarId()
            ?: return CalendarResult.Error("No writable calendar found. Please set up a calendar account on your device.")

        val startMillis: Long
        val endMillis: Long
        val timeZone = TimeZone.getDefault().id

        try {
            startMillis = parseIsoDateTimeToEpochMs(startTimeIso)
            endMillis = if (endTimeIso != null) {
                parseIsoDateTimeToEpochMs(endTimeIso)
            } else {
                // Default to 1 hour after start
                startMillis + 60 * 60 * 1000
            }
        } catch (e: DateTimeParseException) {
            return CalendarResult.Error("Invalid date format. Please use ISO 8601 format (e.g., 2024-03-15T14:30:00)")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId != null) {
                // Add reminder if specified
                if (reminderMinutes > 0) {
                    addReminder(eventId, reminderMinutes)
                }

                val formattedStart = formatForDisplay(startMillis)
                CalendarResult.Success(eventId, title, formattedStart)
            } else {
                CalendarResult.Error("Failed to create calendar event")
            }
        } catch (e: Exception) {
            CalendarResult.Error("Error creating event: ${e.message}")
        }
    }

    private fun addReminder(eventId: Long, minutesBefore: Int) {
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
    }

    /**
     * Events starting within [startMs, endMs) via the Instances table, so
     * recurring events are expanded into occurrences. [query] filters on
     * title/description substring (LIKE %query%).
     */
    suspend fun listEvents(startMs: Long, endMs: Long, query: String?): List<CalendarEventInfo> {
        if (!hasCalendarPermission()) {
            if (!permissionController.requestPermission()) return emptyList()
        }

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.Instances.BEGIN, startMs.toString())
            .appendQueryParameter(CalendarContract.Instances.END, endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
        )
        val selection = query?.let {
            "(" + CalendarContract.Instances.TITLE + " LIKE ? OR " +
                CalendarContract.Instances.DESCRIPTION + " LIKE ?)"
        }
        val selectionArgs = query?.let { arrayOf("%$it", "%$it") }

        val events = mutableListOf<CalendarEventInfo>()
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { cursor ->
                    while (cursor.moveToNext() && events.size < MAX_LISTED_EVENTS) {
                        val eventId = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID))
                        events.add(
                            CalendarEventInfo(
                                eventId = eventId,
                                title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)) ?: "",
                                startIso = formatIso(cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN))),
                                endIso = formatIso(cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.END))),
                                allDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)) == 1,
                                location = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)),
                                description = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)),
                                reminderMinutes = getReminderMinutes(eventId),
                            ),
                        )
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "listEvents failed", e)
        }
        return events
    }

    /** First alert reminder in minutes before the event, or null if none/unreadable. */
    private fun getReminderMinutes(eventId: Long): Int? = try {
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Updates an existing event. Only the provided fields change; passing ""
     * clears a text field. Recurring events: the change applies to the whole
     * series. Returns the event's (possibly new) title.
     */
    suspend fun updateEvent(
        eventId: Long,
        title: String?,
        startTimeIso: String?,
        endTimeIso: String?,
        description: String?,
        location: String?,
        reminderMinutes: Int?,
    ): CalendarResult {
        if (!hasCalendarPermission()) {
            if (!permissionController.requestPermission()) {
                return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings to update events.")
            }
        }

        val values = ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            try {
                startTimeIso?.let { put(CalendarContract.Events.DTSTART, parseIsoDateTimeToEpochMs(it)) }
                endTimeIso?.let { put(CalendarContract.Events.DTEND, parseIsoDateTimeToEpochMs(it)) }
            } catch (_: DateTimeParseException) {
                return CalendarResult.Error("Invalid date format. Please use ISO 8601 format (e.g., 2024-03-15T14:30:00)")
            }
        }
        if (values.size() == 0 && reminderMinutes == null) {
            return CalendarResult.Error("Nothing to update: provide at least one field")
        }

        return try {
            if (values.size() > 0) {
                val updated = context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI,
                    values,
                    "${CalendarContract.Events._ID} = ?",
                    arrayOf(eventId.toString()),
                )
                if (updated == 0) {
                    return CalendarResult.Error("Event $eventId not found")
                }
            }
            if (reminderMinutes != null) {
                context.contentResolver.delete(
                    CalendarContract.Reminders.CONTENT_URI,
                    "${CalendarContract.Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString()),
                )
                addReminder(eventId, reminderMinutes)
            }
            CalendarResult.Success(eventId, getEventTitle(eventId) ?: "", "")
        } catch (e: Exception) {
            CalendarResult.Error("Error updating event: ${e.message}")
        }
    }

    /** Deletes an event (the whole series for recurring events). Returns the deleted event's title. */
    suspend fun deleteEvent(eventId: Long): CalendarResult {
        if (!hasCalendarPermission()) {
            if (!permissionController.requestPermission()) {
                return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings to delete events.")
            }
        }
        val title = getEventTitle(eventId)
        return try {
            val deleted = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
            )
            if (deleted > 0) {
                CalendarResult.Success(eventId, title ?: "", "")
            } else {
                CalendarResult.Error("Event $eventId not found")
            }
        } catch (e: Exception) {
            CalendarResult.Error("Error deleting event: ${e.message}")
        }
    }

    private fun getEventTitle(eventId: Long): String? = try {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE),
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseIsoDateTimeToEpochMs(isoString: String): Long {
        val trimmed = isoString.trim()

        // Offset-qualified inputs (e.g. "2024-03-15T14:30:00+02:00" or "...Z") must
        // be converted directly to an instant. Going through LocalDateTime.parse with
        // a relaxed formatter silently drops the offset and re-anchors to system zone,
        // which surfaces as an off-by-N-hours bug whenever the AI sends an offset that
        // differs from the device's local offset.
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: DateTimeParseException) {
        }

        // Naive inputs: interpret in the device's current zone.
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        )
        for (formatter in formatters) {
            try {
                val ldt = LocalDateTime.parse(trimmed, formatter)
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }

        throw DateTimeParseException("Unable to parse date: $isoString", isoString, 0)
    }

    /** Millis → ISO 8601 with zone offset, in the device's timezone. */
    private fun formatIso(millis: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
    )

    private fun formatForDisplay(millis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return dateTime.format(formatter)
    }
}
