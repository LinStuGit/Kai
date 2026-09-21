package com.kami.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short Chinese blurb for a WMO weather code (open-meteo). */
private fun weatherDesc(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "基本晴"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55 -> "毛毛雨"
    56, 57 -> "冻毛毛雨"
    61, 63, 65 -> "雨"
    66, 67 -> "冻雨"
    71, 73, 75 -> "雪"
    77 -> "霰"
    80, 81, 82 -> "阵雨"
    85, 86 -> "阵雪"
    95 -> "雷暴"
    96, 99 -> "雷暴伴冰雹"
    else -> "未知"
}

/** One day of the open-meteo daily forecast. */
private data class WeatherDay(val date: String, val code: Int, val min: Double, val max: Double, val pop: Int)

/** A minus-one card's full-screen detail sheet (tap a card to open). */
private class Detail(val title: String, val load: suspend () -> String)

/** Rounded card with a small title; when onClick is set the whole card opens a detail sheet. */
@Composable
private fun DashCard(title: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (onClick != null) {
                    Text("详情 ›", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/** open-meteo (free, no key) pinned to the Tsinghua campus coordinates — 7-day daily. */
private fun fetchWeather(): Triple<String, List<WeatherDay>, String> {
    return try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=40.0035&longitude=116.3264" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val daily = json.getJSONObject("daily")
            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val tMin = daily.getJSONArray("temperature_2m_min")
            val tMax = daily.getJSONArray("temperature_2m_max")
            val pop = daily.getJSONArray("precipitation_probability_max")
            val days = (0 until times.length()).map { i ->
                WeatherDay(
                    times.optString(i),
                    codes.optInt(i, -1),
                    tMin.optDouble(i, 0.0),
                    tMax.optDouble(i, 0.0),
                    pop.optInt(i, 0),
                )
            }
            Triple(json.getJSONObject("current").optDouble("temperature_2m", 0.0).toString(), days, "")
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Triple("", emptyList(), "天气获取失败：" + (t.message ?: t.javaClass.simpleName))
    }
}

private val dayFmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val dayFmtOut = SimpleDateFormat("MM-dd E", Locale.CHINA)

private fun formatWeather(days: List<WeatherDay>): String = days.joinToString("\n") { d ->
    val label = try {
        dayFmtOut.format(dayFmtIn.parse(d.date)!!)
    } catch (t: Throwable) {
        d.date
    }
    label + "  " + weatherDesc(d.code) + "  " + d.min + "～" + d.max + "°C  降水 " + d.pop + "%"
}

/** Current + today/tomorrow on the card; tap for the full week. */
@Composable
private fun WeatherCard(onOpen: (List<WeatherDay>) -> Unit) {
    var cur by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(emptyList<WeatherDay>()) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val (c, d, e) = withContext(Dispatchers.IO) { fetchWeather() }
        cur = c
        days = d
        err = e
    }
    DashCard("天气", onClick = { if (days.isNotEmpty()) onOpen(days) }) {
        when {
            err.isNotEmpty() -> Text(err, style = MaterialTheme.typography.bodySmall)
            days.isEmpty() -> Text("天气加载中…", style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(
                    "北京 · 清华  " + weatherDesc(days[0].code) + "  " + cur + "°C",
                    style = MaterialTheme.typography.bodySmall,
                )
                days.take(2).forEachIndexed { i, d ->
                    Text(
                        (if (i == 0) "今天 " else "明天 ") + weatherDesc(d.code) +
                            " " + d.min + "～" + d.max + "°C 降水 " + d.pop + "%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Calendar rows for the detail sheet (full titles, no truncation). */
private suspend fun agendaDetail(context: Context): String {
    if (!CalendarTools.granted(context)) return "未授权日历权限——设置 → 权限管理 授权后显示"
    val rows = withContext(Dispatchers.IO) { CalendarTools.listEventRows(context, 30) }
    if (rows.isEmpty()) return "近 30 天无日程"
    return rows.joinToString("\n") { fmtDate(it.start) + "  " + fmtTime(it.start, it.end) + "  " + it.title }
}

/** Next 3 days from the calendar provider (needs READ_CALENDAR); tap for 30 days. */
@Composable
private fun AgendaCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<EventRow>()) }
    var granted by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        granted = CalendarTools.granted(context)
        if (granted) {
            rows = withContext(Dispatchers.IO) { CalendarTools.listEventRows(context, 3) }
        }
    }
    DashCard("日程（近 3 天）", onClick = onOpen) {
        if (!granted) {
            Text("未授权日历权限——设置 → 权限管理 授权后显示", style = MaterialTheme.typography.bodySmall)
        } else if (rows.isEmpty()) {
            Text("近 3 天无日程", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("日期", Modifier.weight(1.7f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("时间", Modifier.weight(2.6f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("事件", Modifier.weight(3.2f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(fmtDate(r.start), Modifier.weight(1.7f), fontSize = 11.sp)
                    Text(fmtTime(r.start, r.end), Modifier.weight(2.6f), fontSize = 11.sp)
                    Text(
                        r.title,
                        Modifier.weight(3.2f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("MM-dd", Locale.getDefault())
private val timeFmtOnly = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun fmtDate(ms: Long): String = dateFmt.format(Date(ms))

/** "HH:mm~HH:mm"; marks a trailing "+" when the event ends on another day. */
private fun fmtTime(start: Long, end: Long): String {
    val sameDay = dateFmt.format(Date(start)) == dateFmt.format(Date(end))
    return timeFmtOnly.format(Date(start)) + "~" + timeFmtOnly.format(Date(end)) + if (sameDay) "" else "+"
}

/** Session cache so every swipe back doesn't re-fetch all courses. */
private var hwCache: List<HomeworkItem>? = null
private var hwCacheAt = 0L
private const val HW_TTL_MS = 10 * 60_000L

private const val FEED_TTL_MS = 10 * 60_000L

/** Cached campus feed (courses / notifications) shared across swipes. */
private class FeedCache {
    @Volatile var text: String? = null
    @Volatile var at = 0L
}

private val coursesFeed = FeedCache()
private val notifFeed = FeedCache()

/** Full reminder list + fresh pending homework for the detail sheet. */
private suspend fun todoDetail(): String {
    val sb = StringBuilder()
    val rs = ReminderStore.all()
        .sortedBy { ReminderScheduler.nextAt(it)?.timeInMillis ?: Long.MAX_VALUE }
    sb.append("定时提醒（共 ").append(rs.size).append(" 条）\n")
    rs.forEach { sb.append("• ").append(it.title).append("｜").append(ReminderScheduler.describe(it)).append("\n") }
    sb.append("\n网络学堂 · 未提交作业：")
    if (CampusStore.get() == null) {
        sb.append("未登录（设置 → 校园账号）")
    } else {
        val hw = withContext(Dispatchers.IO) {
            try {
                LearnClient.homeworkPending()
            } catch (t: Throwable) {
                null
            }
        }
        if (hw == null) {
            sb.append("获取失败（需校园网；会话失效时去 设置 → 校园账号 重登）")
        } else if (hw.isEmpty()) {
            sb.append("暂无")
        } else {
            hw.forEach { sb.append("\n• ").append(it.course).append("｜").append(it.title).append("｜截止 ").append(it.deadline) }
        }
    }
    return sb.toString()
}

/** Reminders plus pending campus homework (learn 未提交); tap for the full lists. */
@Composable
private fun TodoCard(onOpen: () -> Unit) {
    val items = ReminderStore.all()
        .sortedBy { ReminderScheduler.nextAt(it)?.timeInMillis ?: Long.MAX_VALUE }
        .take(6)
    var hw by remember { mutableStateOf(hwCache) }
    var hwNote by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (CampusStore.get() != null &&
            (hwCache == null || System.currentTimeMillis() - hwCacheAt > HW_TTL_MS)
        ) {
            val fresh = withContext(Dispatchers.IO) {
                try {
                    LearnClient.homeworkPending()
                } catch (t: Throwable) {
                    null
                }
            }
            if (fresh != null) {
                hwCache = fresh
                hwCacheAt = System.currentTimeMillis()
                hw = fresh
                hwNote = ""
            } else {
                hwNote = "作业获取失败（需校园网；会话失效时去 设置 → 校园账号 重登）"
            }
        }
    }
    DashCard("待办", onClick = onOpen) {
        Text("定时提醒", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) {
            Text("暂无——可以让 agent 创建提醒", style = MaterialTheme.typography.bodySmall)
        } else {
            items.forEach { r ->
                Text(r.title + "｜" + ReminderScheduler.describe(r), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("网络学堂 · 未提交作业", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val hwNow = hw
        when {
            CampusStore.get() == null -> Text("未登录网络学堂（设置 → 校园账号）", style = MaterialTheme.typography.bodySmall)
            hwNote.isNotEmpty() -> Text(hwNote, style = MaterialTheme.typography.bodySmall)
            hwNow.isNullOrEmpty() -> Text("暂无未提交作业", style = MaterialTheme.typography.bodySmall)
            else -> hwNow.forEach { h ->
                Text(
                    h.course + "｜" + h.title + "｜截止 " + h.deadline,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Campus feed card: 3-line summary on the card, full text in the detail sheet. */
@Composable
private fun CampusCard(title: String, feed: FeedCache, fetch: suspend () -> String, onOpen: (FeedCache) -> Unit) {
    var text by remember { mutableStateOf(feed.text) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (CampusStore.get() == null) return@LaunchedEffect
        if (feed.text != null && System.currentTimeMillis() - feed.at <= FEED_TTL_MS) return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) {
            try {
                fetch()
            } catch (t: Throwable) {
                null
            }
        }
        if (fresh != null) {
            feed.text = fresh
            feed.at = System.currentTimeMillis()
            text = fresh
        } else {
            note = "获取失败（需校园网；会话失效时去 设置 → 校园账号 重登）"
        }
    }
    DashCard(title, onClick = { onOpen(feed) }) {
        if (CampusStore.get() == null) {
            Text("未登录网络学堂（设置 → 校园账号）", style = MaterialTheme.typography.bodySmall)
        } else {
            val t = text
            when {
                t != null -> {
                    val lines = t.split("\n")
                    lines.take(3).forEach { l ->
                        Text(l, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (lines.size > 3) {
                        Text(
                            "…共 " + lines.size + " 行，点击查看详情",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                note.isNotEmpty() -> Text(note, style = MaterialTheme.typography.bodySmall)
                else -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Full-screen detail sheet. A Dialog window gets its own back handling,
 *  so dismissing never fights the pager's own BackHandler. */
@Composable
private fun DetailOverlay(d: Detail, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            text = withContext(Dispatchers.IO) { d.load() }
        } catch (t: Throwable) {
            err = "获取失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("← 关闭") }
                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                }
                if (err.isNotEmpty()) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    val t = text
                    if (t == null) {
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        ) {
                            Text(t, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Launcher minus-one screen: glanceable info, every card opens a detail sheet. */
@Composable
internal fun MinusOneScreen() {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<Detail?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("负一屏", style = MaterialTheme.typography.titleLarge)
        WeatherCard(onOpen = { days -> detail = Detail("天气 · 未来 7 天") { formatWeather(days) } })
        AgendaCard(onOpen = { detail = Detail("日程 · 近 30 天") { agendaDetail(context) } })
        TodoCard(onOpen = { detail = Detail("待办详情") { todoDetail() } })
        CampusCard(
            "本学期课程",
            coursesFeed,
            { LearnClient.courses() },
            onOpen = { feed ->
                detail = Detail("本学期课程") {
                    feed.text ?: withContext(Dispatchers.IO) { LearnClient.courses() }
                }
            },
        )
        CampusCard(
            "课程公告",
            notifFeed,
            { LearnClient.notifications() },
            onOpen = { feed ->
                detail = Detail("课程公告") {
                    feed.text ?: withContext(Dispatchers.IO) { LearnClient.notifications() }
                }
            },
        )
    }
    detail?.let { d -> DetailOverlay(d, onDismiss = { detail = null }) }
}
