package com.kami.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * System-calendar access for the agent's schedule tools (the app requests
 * READ/WRITE_CALENDAR as an essential permission on first launch).
 */
object CalendarTools {

    fun granted(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    private fun firstCalendarId(context: Context): Long? {
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    fun addEvent(
        context: Context,
        title: String,
        startMs: Long,
        durationMin: Int,
        desc: String,
    ): String {
        if (!granted(context)) return "错误：缺少日历权限，请用户在「设置 → 权限管理」中授权"
        val calId = firstCalendarId(context) ?: return "错误：设备上没有可用日历账户"
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, title)
            if (desc.isNotBlank()) put(Events.DESCRIPTION, desc)
            put(Events.DTSTART, startMs)
            put(Events.DTEND, startMs + durationMin * 60_000L)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, v)
            ?: return "错误：日历写入失败"
        return "已创建日程：$title（id=${uri.lastPathSegment}）"
    }

    fun listEvents(context: Context, days: Int): String {
        if (!granted(context)) return "错误：缺少日历权限，请用户在「设置 → 权限管理」中授权"
        val now = System.currentTimeMillis()
        val end = now + days.coerceIn(1, 60) * 86_400_000L
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val out = StringBuilder()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.DESCRIPTION),
            "${Events.DTSTART} >= ? AND ${Events.DTSTART} <= ?",
            arrayOf(now.toString(), end.toString()),
            "${Events.DTSTART} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.length < 3000) {
                val title = c.getString(0) ?: "(无标题)"
                val start = c.getLong(1)
                val endMs = c.getLong(2)
                out.append(fmt.format(Date(start)))
                    .append(" ~ ")
                    .append(fmt.format(Date(endMs)))
                    .append("  ")
                    .append(title)
                    .append('\n')
            }
        }
        return out.toString().ifBlank { "（未来 $days 天没有日程）" }
    }

    /** Events within an arbitrary inclusive range (month/year views). */
    fun listEventRowsRange(context: Context, startMs: Long, endMs: Long): List<EventRow> {
        if (!granted(context)) return emptyList()
        val rows = mutableListOf<EventRow>()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_LOCATION, Events.DESCRIPTION),
            Events.DTSTART + " >= ? AND " + Events.DTSTART + " <= ?",
            arrayOf(startMs.toString(), endMs.toString()),
            Events.DTSTART + " ASC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < 200) {
                rows.add(
                    EventRow(
                        c.getLong(1),
                        c.getLong(2),
                        c.getString(0) ?: "(无标题)",
                        c.getString(3) ?: "",
                        c.getString(4) ?: "",
                    ),
                )
            }
        }
        return rows
    }

    /** Structured agenda rows for the dashboard table (empty when not granted). */
    fun listEventRows(context: Context, days: Int): List<EventRow> {
        if (!granted(context)) return emptyList()
        val now = System.currentTimeMillis()
        val end = now + days.coerceIn(1, 60) * 86_400_000L
        val rows = mutableListOf<EventRow>()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_LOCATION, Events.DESCRIPTION),
            "${Events.DTSTART} >= ? AND ${Events.DTSTART} <= ?",
            arrayOf(now.toString(), end.toString()),
            "${Events.DTSTART} ASC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < 50) {
                rows.add(
                    EventRow(
                        c.getLong(1),
                        c.getLong(2),
                        c.getString(0) ?: "(无标题)",
                        c.getString(3) ?: "",
                        c.getString(4) ?: "",
                    ),
                )
            }
        }
        return rows
    }
}

/** One agenda row for the minus-one table (structured, unlike [CalendarTools.listEvents]). */
data class EventRow(
    val start: Long,
    val end: Long,
    val title: String,
    val location: String,
    val desc: String,
)
