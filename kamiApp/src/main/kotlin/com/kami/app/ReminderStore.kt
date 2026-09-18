package com.kami.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** A daily reminder (morning briefing, homework/class reminder, …). */
data class Reminder(
    val id: String,
    val title: String,
    val text: String,
    val hour: Int,
    val minute: Int,
    val important: Boolean,
    val enabled: Boolean,
)

/** Compose-state store persisted to filesDir/reminders.json. */
object ReminderStore {

    private val items = mutableListOf<Reminder>()
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
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                        important = o.optBoolean("important"),
                        enabled = o.optBoolean("enabled", true),
                    ),
                )
            }
        }
    }

    private fun requireFile(): File = file ?: throw IllegalStateException("ReminderStore.init not called")

    fun all(): List<Reminder> = items.toList()

    fun get(id: String): Reminder? = items.firstOrNull { it.id == id }

    fun add(context: Context, title: String, text: String, hour: Int, minute: Int, important: Boolean): Reminder {
        init(context)
        val r = Reminder(
            id = "rem-" + System.currentTimeMillis(),
            title = title.trim(),
            text = text.trim(),
            hour = hour,
            minute = minute,
            important = important,
            enabled = true,
        )
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
                        .put("hour", it.hour)
                        .put("minute", it.minute)
                        .put("important", it.important)
                        .put("enabled", it.enabled),
                )
            }
            requireFile().writeText(arr.toString())
        }
    }
}

/** AlarmManager wiring: exact when allowed, inexact fallback otherwise. */
object ReminderScheduler {

    const val ACTION_FIRE = "com.kami.app.REMINDER_FIRE"

    fun pendingIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Next occurrence of a daily reminder (today if still ahead, else tomorrow). */
    fun nextAt(r: Reminder): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, r.hour)
        set(Calendar.MINUTE, r.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }

    /** Re-arm every reminder from scratch (idempotent). */
    fun scheduleAll(context: Context) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val inexact = Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()
        ReminderStore.all().forEach { r ->
            val pi = pendingIntent(appCtx, r.id)
            am.cancel(pi)
            if (!r.enabled) return@forEach
            val at = nextAt(r).timeInMillis
            if (inexact) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }
}
