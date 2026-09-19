package com.kami.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * A scheduled task. Four shapes — "once" (a date+time), "daily" (HH:mm),
 * "weekly" (weekday+HH:mm) and "interval" (every N minutes). Everything is
 * alarm-driven: nothing runs in the background, the alarm wakes the app to
 * post a notification (important ones buzz and full-screen into the app),
 * then the next occurrence is armed.
 */
data class Reminder(
    val id: String,
    val title: String,
    val text: String,
    val important: Boolean,
    val enabled: Boolean,
    val type: String = "daily", // once | daily | weekly | interval
    val hour: Int = 8,
    val minute: Int = 0,
    val year: Int = 0,
    val month: Int = 0, // 1-12
    val day: Int = 0, // 1-31
    val weekday: Int = 0, // java.util.Calendar.DAY_OF_WEEK (1=Sun .. 7=Sat)
    val intervalMin: Int = 0,
)

/** Store persisted to filesDir/reminders.json. */
object ReminderStore {

    // Snapshot-backed so settings UI recomposes the moment a row changes.
    private val items = mutableStateListOf<Reminder>()
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "reminders.json")
        runCatching {
            val arr = JSONArray(file!!.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    Reminder(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        text = o.getString("text"),
                        important = o.optBoolean("important"),
                        enabled = o.optBoolean("enabled", true),
                        type = o.optString("type", "daily"),
                        hour = o.optInt("hour", 8),
                        minute = o.optInt("minute", 0),
                        year = o.optInt("year"),
                        month = o.optInt("month"),
                        day = o.optInt("day"),
                        weekday = o.optInt("weekday"),
                        intervalMin = o.optInt("intervalMin"),
                    ),
                )
            }
        }
    }

    private fun requireFile(): File = file ?: throw IllegalStateException("ReminderStore.init not called")

    fun all(): List<Reminder> = items.toList()

    fun get(id: String): Reminder? = items.firstOrNull { it.id == id }

    fun add(context: Context, r: Reminder): Reminder {
        init(context)
        items.add(r)
        persist()
        ReminderScheduler.scheduleAll(context)
        return r
    }

    fun remove(context: Context, id: String): Boolean {
        val removed = items.removeAll { it.id == id || it.title == id }
        if (removed) {
            persist()
            ReminderScheduler.scheduleAll(context)
        }
        return removed
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(enabled = enabled)
            persist()
            ReminderScheduler.scheduleAll(context)
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("text", it.text)
                        .put("important", it.important)
                        .put("enabled", it.enabled)
                        .put("type", it.type)
                        .put("hour", it.hour)
                        .put("minute", it.minute)
                        .put("year", it.year)
                        .put("month", it.month)
                        .put("day", it.day)
                        .put("weekday", it.weekday)
                        .put("intervalMin", it.intervalMin),
                )
            }
            requireFile().writeText(arr.toString())
        }
    }
}

/** AlarmManager wiring: exact when allowed, inexact fallback otherwise. */
object ReminderScheduler {

    const val ACTION_FIRE = "com.kami.app.REMINDER_FIRE"

    private const val WEEKDAYS = "日一二三四五六"

    fun describe(r: Reminder): String = when (r.type) {
        "once" -> "单次 %04d-%02d-%02d %02d:%02d".format(r.year, r.month, r.day, r.hour, r.minute)
        "weekly" -> "每周周${WEEKDAYS[(r.weekday - 1).mod(7)]} %02d:%02d".format(r.hour, r.minute)
        "interval" -> "每 ${r.intervalMin} 分钟"
        else -> "每日 %02d:%02d".format(r.hour, r.minute)
    }

    fun pendingIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Next occurrence, or null when nothing is coming (past one-shot). */
    fun nextAt(r: Reminder): Calendar? {
        val now = System.currentTimeMillis()
        return when (r.type) {
            "once" -> Calendar.getInstance().apply {
                set(r.year, r.month - 1, r.day, r.hour, r.minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.takeIf { it.timeInMillis > now }

            "weekly" -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, r.hour)
                set(Calendar.MINUTE, r.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.DAY_OF_WEEK, r.weekday)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 7)
            }

            "interval" -> Calendar.getInstance().apply {
                timeInMillis = now + r.intervalMin * 60_000L
                set(Calendar.SECOND, 0)
            }

            else -> Calendar.getInstance().apply {
                // daily
                set(Calendar.HOUR_OF_DAY, r.hour)
                set(Calendar.MINUTE, r.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    /** Re-arm every reminder from scratch (idempotent). */
    fun scheduleAll(context: Context) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Tapping the alarm icon lands back in the app.
        val showPi = PendingIntent.getActivity(
            appCtx,
            0,
            Intent(appCtx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ReminderStore.all().forEach { r ->
            val pi = pendingIntent(appCtx, r.id)
            am.cancel(pi)
            if (!r.enabled) return@forEach
            val at = nextAt(r)?.timeInMillis ?: return@forEach
            // setAlarmClock = clock-app grade: fires in Doze, exempt from
            // standby buckets, no SCHEDULE_EXACT_ALARM grant needed. The
            // status bar shows an alarm icon while anything is armed.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi), pi)
        }
    }
}
