package com.kami.app

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Detail-page payload: free text and/or a tappable table. */
private class DetailData(
    val text: String? = null,
    val headers: List<String> = emptyList(),
    val weights: List<Float> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val tags: List<String> = emptyList(),
    val hint: String = "",
)

/** One level of the detail sheet; onTap may push the next level. */
private class Detail(
    val title: String,
    val onTap: (suspend (String) -> Detail)? = null,
    val load: suspend () -> DetailData,
)

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

/** Table used by both cards and detail pages. Rows get tappable when tags+onTag are given. */
@Composable
private fun DashTable(
    headers: List<String>,
    weights: List<Float>,
    rows: List<List<String>>,
    tags: List<String> = emptyList(),
    onTag: ((String) -> Unit)? = null,
    dense: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        headers.forEachIndexed { i, h ->
            Text(
                h,
                Modifier.weight(weights.getOrElse(i) { 1f }),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    rows.forEachIndexed { ri, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onTag != null && ri < tags.size) {
                        Modifier.clickable { onTag(tags[ri]) }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = if (dense) 3.dp else 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEachIndexed { ci, cell ->
                Text(
                    cell,
                    Modifier.weight(weights.getOrElse(ci) { 1f }),
                    fontSize = 11.sp,
                    maxLines = if (dense) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

// ---- module caches: swiping back and forth must not re-hit the network ----

private const val FEED_TTL_MS = 3 * 60_000L
private const val WEATHER_TTL_MS = 15 * 60_000L
private const val AGENDA_TTL_MS = 2 * 60_000L

private var weatherDays: List<WeatherDay> = emptyList()
private var weatherCur = ""
private var weatherAt = 0L

private fun fetchWeather(): Boolean = try {
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
        weatherDays = (0 until times.length()).map { i ->
            WeatherDay(
                times.optString(i),
                codes.optInt(i, -1),
                tMin.optDouble(i, 0.0),
                tMax.optDouble(i, 0.0),
                pop.optInt(i, 0),
            )
        }
        weatherCur = json.getJSONObject("current").optDouble("temperature_2m", 0.0).toString()
        weatherAt = System.currentTimeMillis()
        true
    } finally {
        conn.disconnect()
    }
} catch (t: Throwable) {
    false
}

private val dayFmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val dayFmtOut = SimpleDateFormat("MM-dd E", Locale.CHINA)
private val fullFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun dayLabel(date: String, i: Int): String {
    if (i == 0) return "今天"
    if (i == 1) return "明天"
    return try {
        dayFmtOut.format(dayFmtIn.parse(date)!!)
    } catch (t: Throwable) {
        date
    }
}

private var agendaRows: List<EventRow> = emptyList()
private var agendaAt = 0L

private var hwCache: List<HomeworkItem>? = null
private var hwCacheAt = 0L

private var ykCache: List<YuketangClient.YkItem>? = null
private var ykCacheAt = 0L

private var noticeCache: List<LearnClient.NotifItem>? = null
private var noticeCacheAt = 0L

private var courseCache: List<LearnClient.CourseRow>? = null
private var courseCacheAt = 0L

/** 手动「↻ 刷新」：清空全部模块缓存，key(rev) 换帧后各卡重新拉取。 */
private fun clearCaches() {
    weatherAt = 0
    agendaAt = 0
    hwCache = null
    hwCacheAt = 0
    ykCache = null
    ykCacheAt = 0
    noticeCache = null
    noticeCacheAt = 0
    courseCache = null
    courseCacheAt = 0
}

/** "MM-dd HH:mm" → 本年 epoch（早于现在超过 180 天视为明年）；解析不了给 MAX（排最后）。 */
private fun urgencyOf(deadline: String): Long {
    val m = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2})$").find(deadline.trim()) ?: return Long.MAX_VALUE
    return try {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.MONTH, m.groupValues[2].toInt() - 1)
        cal.set(java.util.Calendar.DAY_OF_MONTH, m.groupValues[1].toInt())
        cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[3].toInt())
        cal.set(java.util.Calendar.MINUTE, m.groupValues[4].toInt())
        cal.set(java.util.Calendar.SECOND, 0)
        var t = cal.timeInMillis
        if (t < System.currentTimeMillis() - 180L * 86_400_000L) {
            cal.add(java.util.Calendar.YEAR, 1)
            t = cal.timeInMillis
        }
        t
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }
}

/** One aggregated todo row carrying its urgency timestamp for sorting. */
private data class TodoRow(val at: Long, val row: List<String>, val tag: String)

// ---- weather ----

@Composable
private fun WeatherCard(onOpen: () -> Unit) {
    var stale by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - weatherAt > WEATHER_TTL_MS || weatherDays.isEmpty()) {
            withContext(Dispatchers.IO) { fetchWeather() }
        }
        stale = false
    }
    DashCard("天气（清华 · 当前 " + (if (weatherCur.isEmpty()) "…" else weatherCur + "°C") + "）", onClick = onOpen) {
        if (stale && weatherDays.isEmpty()) {
            Text("天气加载中…", style = MaterialTheme.typography.bodySmall)
        } else if (weatherDays.isEmpty()) {
            Text("天气获取失败（网络？）", style = MaterialTheme.typography.bodySmall)
        } else {
            DashTable(
                listOf("日期", "天气", "气温", "降水"),
                listOf(1.3f, 1.2f, 2.1f, 1.2f),
                weatherDays.take(3).mapIndexed { i, d ->
                    listOf(
                        dayLabel(d.date, i),
                        weatherDesc(d.code),
                        "" + d.min + "～" + d.max + "°C",
                        d.pop.toString() + "%",
                    )
                },
            )
            Text(
                "…共 " + weatherDays.size + " 天，点击查看全部",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun weatherDetail(): Detail = Detail("天气 · 未来 7 天") {
    DetailData(
        headers = listOf("日期", "天气", "气温", "降水"),
        weights = listOf(1.6f, 1.4f, 2.2f, 1.3f),
        rows = weatherDays.mapIndexed { i, d ->
            listOf(
                dayLabel(d.date, i),
                weatherDesc(d.code),
                "" + d.min + "～" + d.max + "°C",
                d.pop.toString() + "%",
            )
        },
        hint = "open-meteo 数据，每 30 分钟刷新一次",
    )
}

// ---- agenda (calendar) ----

private fun agendaTableRows(rows: List<EventRow>): List<List<String>> = rows.map { r ->
    listOf(fmtDate(r.start) + " " + fmtTime(r.start, r.end), r.location.ifBlank { "—" }, r.title)
}

private fun eventDetail(idx: Int): Detail {
    val r = agendaRows.getOrNull(idx) ?: return Detail("日程") { DetailData(text = "（日程已刷新，请重开）") }
    val text = r.title +
        "\n时间：" + fullFmt.format(Date(r.start)) + " ~ " + fullFmt.format(Date(r.end)) +
        (if (r.location.isNotBlank()) "\n地点：" + r.location else "") +
        (if (r.desc.isNotBlank()) "\n备注：" + r.desc else "")
    return Detail("日程详情") { DetailData(text = text) }
}

@Composable
private fun AgendaCard(onOpen: (Detail) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        granted = CalendarTools.granted(context)
        if (granted &&
            (agendaRows.isEmpty() || System.currentTimeMillis() - agendaAt > AGENDA_TTL_MS)
        ) {
            val rows = withContext(Dispatchers.IO) { CalendarTools.listEventRows(context, 30) }
            agendaRows = rows
            agendaAt = System.currentTimeMillis()
        }
    }
    DashCard(L10n.s("日程（近 3 天）", "Agenda (3 days)"), onClick = { onOpen(agendaDetail()) }) {
        if (!granted) {
            Text("未授权日历权限——设置 → 权限管理 授权后显示", style = MaterialTheme.typography.bodySmall)
        } else if (agendaRows.isEmpty()) {
            Text("近 30 天无日程", style = MaterialTheme.typography.bodySmall)
        } else {
            DashTable(
                listOf("时间", "地点", "事件"),
                listOf(2.7f, 2.0f, 3.1f),
                agendaTableRows(agendaRows.take(3)),
                tags = agendaRows.indices.take(3).map { "ev:$it" },
                onTag = { tag -> onOpen(eventDetail(tag.removePrefix("ev:").toInt())) },
            )
            Text(
                "…共 " + agendaRows.size + " 条，点击查看全部",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun agendaDetail(): Detail = Detail(
    "日程 · 近 30 天",
    onTap = { tag -> eventDetail(tag.removePrefix("ev:").toInt()) },
) {
    if (agendaRows.isEmpty()) {
        DetailData(text = "近 30 天无日程")
    } else {
        DetailData(
            headers = listOf("时间", "地点", "事件"),
            weights = listOf(2.7f, 2.0f, 3.1f),
            rows = agendaTableRows(agendaRows),
            tags = agendaRows.indices.map { "ev:$it" },
            hint = L10n.s("点任意一行查看时间/地点/备注", "Tap a row for time/location/notes"),
        )
    }
}

private fun fmtDate(ms: Long): String = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))

/** "HH:mm~HH:mm"; marks a trailing "+" when the event ends on another day. */
private fun fmtTime(start: Long, end: Long): String {
    val sameDay = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(start)) ==
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(end))
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    return hm.format(Date(start)) + "~" + hm.format(Date(end)) + if (sameDay) "" else "+"
}

// ---- todo: reminders + learn homework + yuketang assignments ----

private fun <T> fresh(cache: T?, at: Long, ttl: Long = FEED_TTL_MS): Boolean = cache != null && System.currentTimeMillis() - at <= ttl

@Composable
private fun TodoCard(onOpen: (Detail) -> Unit) {
    val context = LocalContext.current
    val reminders = ReminderStore.all()
        .sortedBy { ReminderScheduler.nextAt(it)?.timeInMillis ?: Long.MAX_VALUE }
    var hwRev by remember { mutableStateOf(0) }
    var ykRev by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (CampusStore.get() != null && !fresh(hwCache, hwCacheAt)) {
            val freshHw = withContext(Dispatchers.IO) {
                try {
                    LearnClient.homeworkPending()
                } catch (t: Throwable) {
                    null
                }
            }
            if (freshHw != null) {
                hwCache = freshHw
                hwCacheAt = System.currentTimeMillis()
            }
            hwRev++
        }
        if (YuketangClient.cookie().isNotBlank() && !fresh(ykCache, ykCacheAt)) {
            val freshYk = withContext(Dispatchers.IO) {
                try {
                    YuketangClient.assignments()
                } catch (t: Throwable) {
                    null
                }
            }
            if (freshYk != null) {
                ykCache = freshYk
                ykCacheAt = System.currentTimeMillis()
            }
            ykRev++
        }
    }
    val hw = hwCache
    val yk = ykCache
    DashCard(L10n.s("待办", "To-dos"), onClick = { onOpen(todoDetail(reminders)) }) {
        Text("定时提醒", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (reminders.isEmpty()) {
            Text("暂无——可以让 agent 创建提醒", style = MaterialTheme.typography.bodySmall)
        } else {
            DashTable(
                listOf("内容", "时间"),
                listOf(3f, 2f),
                reminders.take(2).map { listOf(it.title, ReminderScheduler.describe(it)) },
                tags = reminders.indices.take(2).map { "rem:$it" },
                onTag = { tag ->
                    val i = tag.removePrefix("rem:").toInt()
                    ReminderStore.all().getOrNull(i)?.let { r ->
                        onOpen(
                            Detail(r.title) {
                                DetailData(text = r.title + "\n" + ReminderScheduler.describe(r))
                            },
                        )
                    }
                },
            )
            if (reminders.size > 2) {
                Text(
                    "…共 " + reminders.size + " 条",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("网络学堂 · 未提交作业", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            CampusStore.get() == null -> Text("未登录网络学堂（设置 → 校园账号）", style = MaterialTheme.typography.bodySmall)

            hw == null -> Text(if (hwRev == 0) "加载中…" else "获取失败（需校园网；会话失效时重登）", style = MaterialTheme.typography.bodySmall)

            hw.isEmpty() -> Text("暂无未提交作业", style = MaterialTheme.typography.bodySmall)

            else -> {
                val hwTop = hw.withIndex().sortedBy { urgencyOf(it.value.deadline) }.take(2)
                DashTable(
                    listOf("课程", "作业", "截止"),
                    listOf(2.2f, 3f, 2.2f),
                    hwTop.map { listOf(it.value.course, it.value.title, it.value.deadline) },
                    tags = hwTop.map { "hw:" + it.index },
                    onTag = { tag ->
                        val h = hw.getOrNull(tag.removePrefix("hw:").toInt()) ?: return@DashTable
                        onOpen(homeworkDetailPage(h))
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("雨课堂 · 作业/考试", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            YuketangClient.cookie().isBlank() -> Text("未登录雨课堂（设置 → 校园账号）", style = MaterialTheme.typography.bodySmall)

            yk == null -> Text(if (ykRev == 0) "加载中…" else "获取失败（下拉重试或重新登录）", style = MaterialTheme.typography.bodySmall)

            yk.isEmpty() -> Text("暂无雨课堂作业/考试", style = MaterialTheme.typography.bodySmall)

            else -> {
                val ykTop = yk.withIndex().sortedBy { urgencyOf(it.value.deadline) }.take(2)
                DashTable(
                    listOf("课程", "条目", "截止"),
                    listOf(2.2f, 3f, 2.2f),
                    ykTop.map { listOf(it.value.course, it.value.title + "（" + it.value.kind + "）", it.value.deadline.ifBlank { "—" }) },
                    tags = ykTop.map { "yk:" + it.index },
                    onTag = { tag ->
                        val it2 = yk.getOrNull(tag.removePrefix("yk:").toInt()) ?: return@DashTable
                        onOpen(yuketangItemDetail(it2))
                    },
                )
            }
        }
    }
}

private fun homeworkDetailPage(h: HomeworkItem): Detail = Detail(h.title) {
    DetailData(text = LearnClient.homeworkDetail(h.wlkcid, h.zyid), hint = h.course + " · 截止 " + h.deadline)
}

private fun yuketangItemDetail(item: YuketangClient.YkItem): Detail {
    WebViewer.open?.invoke(item.url)
    return Detail("雨课堂 · " + item.title) {
        DetailData(
            text = "课程：" + item.course +
                "\n类型：" + item.kind +
                "\n截止：" + item.deadline.ifBlank { "—" } +
                "\n\n已在内置浏览器打开对应页面（需雨课堂登录态）。",
        )
    }
}

private fun todoDetail(reminders: List<Reminder>): Detail = Detail(
    "待办详情",
    onTap = { tag ->
        when {
            tag.startsWith("rem:") -> {
                val r = reminders.getOrNull(tag.removePrefix("rem:").toInt())
                if (r == null) {
                    Detail("提示") { DetailData(text = "（提醒已删除，请重开）") }
                } else {
                    Detail(r.title) {
                        DetailData(text = r.title + "\n" + ReminderScheduler.describe(r))
                    }
                }
            }

            tag.startsWith("hw:") -> {
                val h = hwCache?.getOrNull(tag.removePrefix("hw:").toInt())
                if (h == null) {
                    Detail("提示") { DetailData(text = "（作业列表已刷新，请重开）") }
                } else {
                    homeworkDetailPage(h)
                }
            }

            tag.startsWith("yk:") -> {
                val y = ykCache?.getOrNull(tag.removePrefix("yk:").toInt())
                if (y == null) {
                    Detail("提示") { DetailData(text = "（条目已刷新，请重开）") }
                } else {
                    yuketangItemDetail(y)
                }
            }

            else -> Detail("提示") { DetailData(text = "无法识别的操作") }
        }
    },
) {
    // 缓存优先：卡片已拉过的直接秒开，只在从未拉过时才联网
    val hw = hwCache ?: withContext(Dispatchers.IO) {
        try {
            LearnClient.homeworkPending().also {
                hwCache = it
                hwCacheAt = System.currentTimeMillis()
            }
        } catch (t: Throwable) {
            null
        }
    }
    val yk = ykCache ?: if (YuketangClient.cookie().isNotBlank()) {
        withContext(Dispatchers.IO) {
            try {
                YuketangClient.assignments().also {
                    ykCache = it
                    ykCacheAt = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                null
            }
        }
    } else {
        null
    }
    val agg = mutableListOf<TodoRow>()
    reminders.forEachIndexed { i, r ->
        agg.add(TodoRow(ReminderScheduler.nextAt(r)?.timeInMillis ?: Long.MAX_VALUE, listOf("提醒", r.title, ReminderScheduler.describe(r)), "rem:$i"))
    }
    hw?.forEachIndexed { i, h ->
        agg.add(TodoRow(urgencyOf(h.deadline), listOf("学堂作业", h.course + " · " + h.title, "截止 " + h.deadline), "hw:$i"))
    }
    yk?.forEachIndexed { i, y ->
        agg.add(TodoRow(urgencyOf(y.deadline), listOf("雨课堂", y.course + " · " + y.title + "（" + y.kind + "）", y.deadline.ifBlank { "—" }), "yk:$i"))
    }
    val sorted = agg.sortedBy { it.at }
    if (sorted.isEmpty()) {
        DetailData(text = "暂无提醒 / 作业 / 考试（未登录或全部为空）")
    } else {
        DetailData(
            headers = listOf("类别", "内容", "时间"),
            weights = listOf(1.4f, 4.4f, 2.4f),
            rows = sorted.map { it.row },
            tags = sorted.map { it.tag },
            hint = L10n.s(
                "按紧急程度从上到下排列；点任意行查看详情/下载，附件可存到 Download/Kami/",
                "Sorted by urgency; tap a row for details/downloads, attachments land in Download/Kami/",
            ),
        )
    }
}

// ---- campus courses + files ----

@Composable
private fun CoursesCard(onOpen: (Detail) -> Unit) {
    var rev by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (CampusStore.get() != null && !fresh(courseCache, courseCacheAt)) {
            val freshCourses = withContext(Dispatchers.IO) {
                try {
                    LearnClient.courseList()
                } catch (t: Throwable) {
                    null
                }
            }
            if (freshCourses != null) {
                courseCache = freshCourses
                courseCacheAt = System.currentTimeMillis()
            }
            rev++
        }
    }
    val courses = courseCache
    DashCard("本学期课程", onClick = if (CampusStore.get() == null) null else ({ onOpen(coursesDetail()) })) {
        if (CampusStore.get() == null) {
            Text("未登录网络学堂（设置 → 校园账号）", style = MaterialTheme.typography.bodySmall)
        } else {
            when {
                courses == null -> Text(if (rev == 0) "加载中…" else "获取失败（需校园网；会话失效时重登）", style = MaterialTheme.typography.bodySmall)

                courses.isEmpty() -> Text("（本学期无课程）", style = MaterialTheme.typography.bodySmall)

                else -> {
                    DashTable(
                        listOf("课程", "教师", "课号"),
                        listOf(3f, 1.8f, 1.4f),
                        courses.take(3).map { listOf(it.name, it.teacher, it.kch) },
                        tags = courses.take(3).map { it.wlkcid },
                        onTag = { wlkcid ->
                            courses.firstOrNull { it.wlkcid == wlkcid }?.let { onOpen(courseFilesDetail(it)) }
                        },
                    )
                    Text(
                        "…共 " + courses.size + " 门，点击课程查看文件",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun coursesDetail(): Detail = Detail(
    "本学期课程",
    onTap = { wlkcid ->
        courseCache?.firstOrNull { it.wlkcid == wlkcid }?.let { courseFilesDetail(it) }
            ?: Detail("提示") { DetailData(text = "（课程列表已刷新，请重开）") }
    },
) {
    val courses = withContext(Dispatchers.IO) {
        courseCache ?: LearnClient.courseList()
    }
    if (courses.isEmpty()) {
        DetailData(text = "（本学期无课程）")
    } else {
        DetailData(
            headers = listOf("课程", "教师", "课号"),
            weights = listOf(3f, 1.8f, 1.4f),
            rows = courses.map { listOf(it.name, it.teacher, it.kch) },
            tags = courses.map { it.wlkcid },
            hint = "点课程行查看/下载课程文件",
        )
    }
}

private fun courseFilesDetail(c: LearnClient.CourseRow): Detail = Detail(
    c.name + " · 文件",
    onTap = { tag ->
        fileDownloadDetail(tag) ?: Detail("提示") { DetailData(text = "无法识别的下载项") }
    },
) {
    val files = withContext(Dispatchers.IO) { LearnClient.courseFiles(c.wlkcid) }
    if (files.isEmpty()) {
        DetailData(text = "（该课程无文件）")
    } else {
        DetailData(
            headers = listOf("文件", "大小", "上传"),
            weights = listOf(3.6f, 1.3f, 1.9f),
            rows = files.map { listOf(it.title, it.sizeKb.toString() + "KB", it.uploaded) },
            tags = files.map { "f:" + it.wjid + ":" + it.title },
            hint = "点文件行下载到 Download/Kami/",
        )
    }
}

// ---- campus notices ----

@Composable
private fun NoticesCard(onOpen: (Detail) -> Unit) {
    var rev by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (CampusStore.get() != null && !fresh(noticeCache, noticeCacheAt)) {
            val freshNotices = withContext(Dispatchers.IO) {
                try {
                    LearnClient.notificationRows()
                } catch (t: Throwable) {
                    null
                }
            }
            if (freshNotices != null) {
                noticeCache = freshNotices
                noticeCacheAt = System.currentTimeMillis()
            }
            rev++
        }
    }
    val notices = noticeCache
    DashCard(L10n.s("课程公告", "Announcements"), onClick = if (CampusStore.get() == null) null else ({ onOpen(noticesDetail()) })) {
        if (CampusStore.get() == null) {
            Text("未登录网络学堂（设置 → 校园账号）", style = MaterialTheme.typography.bodySmall)
        } else {
            when {
                notices == null -> Text(if (rev == 0) "加载中…" else "获取失败（需校园网；会话失效时重登）", style = MaterialTheme.typography.bodySmall)

                notices.isEmpty() -> Text("（无课程公告）", style = MaterialTheme.typography.bodySmall)

                else -> {
                    DashTable(
                        listOf("课程", "公告", "日期"),
                        listOf(2.2f, 3.6f, 1.7f),
                        notices.take(3).map { listOf(it.course, (if (it.important) "★" else "") + it.title, it.whenStr) },
                        tags = notices.indices.take(3).map { "gg:$it" },
                        onTag = { tag -> onOpen(noticeDetail(tag.removePrefix("gg:").toInt())) },
                    )
                    Text(
                        "…共 " + notices.size + " 条，点击查看正文/附件",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun noticeDetail(idx: Int): Detail {
    val n = noticeCache?.getOrNull(idx) ?: return Detail("公告") { DetailData(text = "（公告已刷新，请重开）") }
    val attText = if (n.attName.isNullOrBlank()) "（无附件）" else ""
    return Detail(
        (if (n.important) "★" else "") + n.title,
        load = {
            DetailData(
                text = n.course + " · " + n.whenStr + "\n\n" + n.contentText.ifBlank { attText.ifBlank { "（无正文）" } },
                headers = if (n.attName.isNullOrBlank()) emptyList() else listOf("附件", "操作"),
                weights = listOf(3.5f, 1.4f),
                rows = if (n.attName.isNullOrBlank()) emptyList() else listOf(listOf(n.attName!!, "下载 ↓")),
                tags = if (n.attName.isNullOrBlank()) emptyList() else listOf("dl:$idx"),
                hint = if (n.attName.isNullOrBlank()) "" else "点附件行下载到 Download/Kami/",
            )
        },
        onTap = { tag ->
            if (tag.startsWith("dl:")) {
                Detail("下载附件") {
                    val url = LearnClient.notificationAttachmentUrl(n.wlkcid, n.ggid)
                        ?: return@Detail DetailData(text = "未在公告页找到附件下载链接")
                    DetailData(text = LearnClient.downloadToFile(AppContextHolder.get(), url, n.attName ?: "learn-附件"))
                }
            } else {
                Detail("提示") { DetailData(text = "无法识别的操作") }
            }
        },
    )
}

private fun noticesDetail(): Detail = Detail(
    "课程公告",
    onTap = { tag -> noticeDetail(tag.removePrefix("gg:").toInt()) },
) {
    val notices = noticeCache ?: emptyList()
    if (notices.isEmpty()) {
        DetailData(text = "（无课程公告）")
    } else {
        DetailData(
            headers = listOf("课程", "公告", "日期"),
            weights = listOf(2.2f, 3.6f, 1.7f),
            rows = notices.map { listOf(it.course, (if (it.important) "★" else "") + it.title, it.whenStr) },
            tags = notices.indices.map { "gg:$it" },
            hint = "点公告行查看正文，可下载附件",
        )
    }
}

/** Shared download tag handler for course files ("f:<wjid>:<name>"). */
private fun fileDownloadDetail(tag: String): Detail? {
    if (!tag.startsWith("f:")) return null
    val rest = tag.removePrefix("f:")
    val wjid = rest.substringBefore(':')
    val name = rest.substringAfter(':')
    val url = "https://learn.tsinghua.edu.cn/b/wlxt/kj/wlkc_kjxxb/student/downloadFile?sfgk=0&wjid=" +
        java.net.URLEncoder.encode(wjid, "UTF-8")
    return Detail("下载文件") {
        DetailData(text = LearnClient.downloadToFile(AppContextHolder.get(), url, name.ifBlank { "learn-$wjid" }))
    }
}

// ---- detail overlay with a back stack ----

@Composable
private fun DetailOverlay(
    d: Detail,
    canBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onTag: (String) -> Unit,
) {
    var data by remember(d) { mutableStateOf<DetailData?>(null) }
    var err by remember(d) { mutableStateOf("") }
    LaunchedEffect(d) {
        try {
            data = withContext(Dispatchers.IO) { d.load() }
        } catch (t: Throwable) {
            err = "获取失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canBack) {
                        TextButton(onClick = onBack) { Text("← 返回") }
                    }
                    TextButton(onClick = onClose) { Text("✕") }
                    Text(
                        d.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (err.isNotEmpty()) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    val dd = data
                    if (dd == null) {
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        ) {
                            if (!dd.text.isNullOrBlank()) {
                                Text(dd.text, style = MaterialTheme.typography.bodySmall)
                                if (dd.headers.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            if (dd.headers.isNotEmpty()) {
                                DashTable(dd.headers, dd.weights, dd.rows, dd.tags, onTag = onTag, dense = false)
                            }
                            if (dd.hint.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    dd.hint,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Launcher minus-one screen: table cards, tap for detail sheets, sheets nest. */
@Composable
internal fun MinusOneScreen() {
    val stack = remember { mutableStateListOf<Detail>() }
    val scope = rememberCoroutineScope()
    val open: (Detail) -> Unit = { stack.add(it) }
    var rev by remember { mutableStateOf(0) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            // Minute-aligned wake: the clock only shows HH:mm, so ticking
            // every 30s just doubled wakeups for nothing.
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    val clock = remember(nowMs) {
        SimpleDateFormat("MM-dd E HH:mm", Locale.CHINA).format(Date(nowMs))
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                clearCaches()
                rev++
            }) { Text(L10n.s("↻ 刷新", "↻ Refresh")) }
        }
        key(rev) {
            WeatherCard(onOpen = { open(weatherDetail()) })
            AgendaCard(onOpen = open)
            TodoCard(onOpen = open)
            CoursesCard(onOpen = open)
            NoticesCard(onOpen = open)
        }
    }
    if (stack.isNotEmpty()) {
        val top = stack.last()
        DetailOverlay(
            d = top,
            canBack = stack.size > 1,
            onBack = { stack.removeAt(stack.lastIndex) },
            onClose = { stack.clear() },
            onTag = { tag ->
                scope.launch {
                    val next = top.onTap?.invoke(tag) ?: return@launch
                    open(next)
                }
            },
        )
    }
}
