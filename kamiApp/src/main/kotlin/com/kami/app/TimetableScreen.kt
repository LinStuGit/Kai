package com.kami.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One weekly course slot (config-driven; imported by the agent timetable tool). */
data class CourseSlot(
    val name: String,
    val teacher: String,
    val day: Int, // 1=周一 … 7=周日
    val start: Int, // 起始节
    val end: Int, // 结束节
    val where: String,
    val weeks: String, // 如 "1-16" 或 "1,3,5"
)

/** One holiday span for the academic calendar (校历). */
data class HolidaySpan(val name: String, val start: String, val end: String)

/**
 * 负二屏课程表配置，存 filesDir/timetable.json：agent 用 timetable 工具读写
 * （用户口述/粘贴课表时由 agent 解析导入），假期默认带国庆/元旦可覆盖。
 */
object TimetableStore {

    data class Data(
        val semesterName: String = "2026 秋季学期",
        val semesterStart: String = "2026-09-14", // 学期第一周的周一
        val courses: List<CourseSlot> = emptyList(),
        val holidays: List<HolidaySpan> = emptyList(),
    )

    private const val FILE = "timetable.json"

    private val DEFAULT = """{
        "semesterName": "2026 秋季学期",
        "semesterStart": "2026-09-14",
        "courses": [],
        "holidays": [
            {"name": "国庆节", "start": "2026-10-01", "end": "2026-10-07"},
            {"name": "元旦", "start": "2027-01-01", "end": "2027-01-03"}
        ]
    }"""

    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun file(): File = File(appContext.filesDir, FILE)

    fun mtime(): Long = if (::appContext.isInitialized && file().exists()) file().lastModified() else 0L

    fun raw(): String = if (!::appContext.isInitialized || !file().exists()) {
        DEFAULT.trimIndent()
    } else {
        file().readText()
    }

    fun get(): Data = parse(raw())

    fun setJson(text: String): String = try {
        val d = parse(text)
        file().writeText(text)
        "已保存课程表配置（" + d.courses.size + " 门课，" + d.holidays.size + " 段假期）"
    } catch (t: Throwable) {
        "保存失败：" + (t.message ?: t.javaClass.simpleName)
    }

    fun reset(): String {
        runCatching { file().delete() }
        return "已恢复默认课程表配置"
    }

    /** 开学周 = 第 1 周；学期开始前按第 0 周及负数计。 */
    fun weekOf(startIso: String, date: LocalDate): Int = try {
        ChronoUnit.WEEKS.between(LocalDate.parse(startIso), date).toInt() + 1
    } catch (t: Throwable) {
        1
    }

    private fun parse(text: String): Data {
        val o = JSONObject(text)
        val courses = mutableListOf<CourseSlot>()
        val cArr = o.optJSONArray("courses")
        for (i in 0 until (cArr?.length() ?: 0)) {
            val c = cArr!!.getJSONObject(i)
            courses.add(
                CourseSlot(
                    name = c.optString("name"),
                    teacher = c.optString("teacher"),
                    day = c.optInt("day", 1).coerceIn(1, 7),
                    start = c.optInt("start", 1).coerceAtLeast(1),
                    end = c.optInt("end", c.optInt("start", 1)).coerceAtLeast(c.optInt("start", 1)),
                    where = c.optString("where"),
                    weeks = c.optString("weeks", "1-20"),
                ),
            )
        }
        val holidays = mutableListOf<HolidaySpan>()
        val hArr = o.optJSONArray("holidays")
        for (i in 0 until (hArr?.length() ?: 0)) {
            val h = hArr!!.getJSONObject(i)
            holidays.add(HolidaySpan(h.optString("name"), h.optString("start"), h.optString("end")))
        }
        return Data(
            semesterName = o.optString("semesterName").ifBlank { "2026 秋季学期" },
            semesterStart = o.optString("semesterStart").ifBlank { "2026-09-14" },
            courses = courses,
            holidays = holidays,
        )
    }
}

/** "1-16" / "1,3,5" / "1-8,11-16" → 第 n 周是否上课。 */
private fun weekInRange(spec: String, n: Int): Boolean {
    if (spec.isBlank()) return true
    return spec.split(",").any { part ->
        val p = part.trim()
        if (p.contains("-")) {
            val ab = p.split("-")
            val a = ab.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
            val b = ab.getOrNull(1)?.trim()?.toIntOrNull() ?: 20
            n in a..b
        } else {
            p.toIntOrNull() == n
        }
    }
}

private val FMT_MD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val FMT_D: DateTimeFormatter = DateTimeFormatter.ofPattern("d")
private val WEEKDAY_CN = listOf("一", "二", "三", "四", "五", "六", "日")

private fun holidayAt(data: TimetableStore.Data, date: LocalDate): HolidaySpan? = data.holidays.firstOrNull { h ->
    runCatching {
        val s = LocalDate.parse(h.start)
        val e = LocalDate.parse(h.end)
        !date.isBefore(s) && !date.isAfter(e)
    }.getOrDefault(false)
}

/** 负二屏：周课表（配置驱动）+ 月/年日历（系统日程 + 校历假期标记）。 */
@Composable
internal fun TimetableScreen() {
    val data = remember(TimetableStore.mtime()) { TimetableStore.get() }
    val today = remember { LocalDate.now() }
    var view by rememberSaveable { mutableStateOf("week") }
    var weekOffset by remember { mutableStateOf(0) }
    var monthOffset by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                data.semesterName + " · 第 " + TimetableStore.weekOf(data.semesterStart, today) + " 周",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = view == "week", onClick = { view = "week" }, label = { Text("课表") })
            FilterChip(selected = view == "month", onClick = { view = "month" }, label = { Text("月") })
            FilterChip(selected = view == "year", onClick = { view = "year" }, label = { Text("年") })
        }
        when (view) {
            "month" -> MonthView(data, today, monthOffset, onOffset = { monthOffset = it })

            "year" -> YearView(data, today, onMonth = {
                monthOffset = it
                view = "month"
            })

            else -> WeekView(data, today, weekOffset, onOffset = { weekOffset = it })
        }
    }
}

@Composable
private fun WeekView(data: TimetableStore.Data, today: LocalDate, offset: Int, onOffset: (Int) -> Unit) {
    val weekNum = TimetableStore.weekOf(data.semesterStart, today) + offset
    val weekStart = runCatching {
        LocalDate.parse(data.semesterStart).plusWeeks((weekNum - 1).toLong())
    }.getOrDefault(today.minusDays((today.dayOfWeek.value - 1).toLong()))
    val holiday = (0..6).map { holidayAt(data, weekStart.plusDays(it.toLong())) }.firstOrNull { it != null }
    var selected by remember { mutableStateOf<CourseSlot?>(null) }
    val bands = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 11)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onOffset(offset - 1) }) { Text("‹") }
            Text(
                "第 " + weekNum + " 周 · " + weekStart.format(FMT_MD) + " ~ " + weekStart.plusDays(6).format(FMT_MD),
                Modifier.weight(1f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { onOffset(offset + 1) }) { Text("›") }
        }
        if (holiday != null) {
            Text(
                "假期：" + holiday.name + "（" + holiday.start + " ~ " + holiday.end + "）",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }
        if (data.courses.isEmpty()) {
            Text(
                "课表为空——对 agent 说「把课表导入负二屏」并发送课表文本即可导入（教师/教室/周次选填）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row {
            Text("", Modifier.width(26.dp))
            (0..6).forEach { d ->
                val date = weekStart.plusDays(d.toLong())
                val isToday = date == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        WEEKDAY_CN[d],
                        fontSize = 10.sp,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        date.format(FMT_D),
                        fontSize = 9.sp,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        bands.forEach { (b0, b1) ->
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                Text(
                    "" + b0 + "-" + b1,
                    Modifier.width(26.dp).fillMaxHeight(),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (0..6).forEach { d ->
                    val day = d + 1
                    val slot = data.courses.firstOrNull {
                        it.day == day && it.start <= b1 && it.end >= b0 && weekInRange(it.weeks, weekNum)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(1.dp)
                            .background(
                                if (slot != null) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                },
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { if (slot != null) selected = slot },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (slot != null) {
                            Text(
                                slot.name + (if (slot.where.isBlank()) "" else "\n" + slot.where),
                                fontSize = 8.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                lineHeight = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
    selected?.let { c ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = true)) {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                if (c.teacher.isNotBlank()) Text("教师：" + c.teacher, style = MaterialTheme.typography.bodySmall)
                Text(
                    "时间：周" + WEEKDAY_CN[c.day - 1] + " 第 " + c.start + "-" + c.end + " 节 · " +
                        c.weeks + " 周",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (c.where.isNotBlank()) Text("地点：" + c.where, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun MonthView(data: TimetableStore.Data, today: LocalDate, offset: Int, onOffset: (Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val month = today.plusMonths(offset.toLong()).withDayOfMonth(1)
    val lead = month.dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    var events by remember(month) { mutableStateOf<List<EventRow>>(emptyList()) }
    var sel by remember(month) {
        mutableStateOf(if (month == today.withDayOfMonth(1)) today else month)
    }
    LaunchedEffect(month) {
        events = withContext(Dispatchers.IO) {
            CalendarTools.listEventRowsRange(
                context,
                month.minusDays(3).toEpochDay() * 86_400_000L,
                month.plusMonths(1).plusDays(3).toEpochDay() * 86_400_000L,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onOffset(offset - 1) }) { Text("‹") }
            Text(
                "" + month.year + " 年 " + month.monthValue + " 月",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { onOffset(offset + 1) }) { Text("›") }
        }
        Row {
            WEEKDAY_CN.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val cells: List<LocalDate?> = List(lead) { null } + (1..days).map { month.withDayOfMonth(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.height(36.dp)) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val hol = holidayAt(data, date)
                            val dayEvents = events.count {
                                java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(it.start)) ==
                                    date.toString()
                            }
                            val mark = (if (hol != null) "假" else "") + (if (dayEvents > 0) "•" else "")
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { sel = date }) {
                                Text(
                                    "" + date.dayOfMonth,
                                    fontSize = 11.sp,
                                    color = when {
                                        hol != null -> MaterialTheme.colorScheme.error
                                        date == today -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (mark.isNotEmpty()) {
                                    Text(
                                        mark,
                                        fontSize = 7.sp,
                                        color = if (hol != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        HorizontalDivider()
        // 选中日的详情：假期 + 当天课表（按周几）+ 系统日程
        val hol = holidayAt(data, sel)
        val selEvents = events.filter {
            java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(it.start)) == sel.toString()
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sel.toString() + " 周" + WEEKDAY_CN[sel.dayOfWeek.value - 1], fontSize = 13.sp)
            if (hol != null) {
                Text("假期：" + hol.name, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            val dayCourses = data.courses.filter { it.day == sel.dayOfWeek.value }
            if (dayCourses.isNotEmpty()) {
                Text("课程：" + dayCourses.joinToString("；") { it.name + (if (it.where.isBlank()) "" else "@" + it.where) }, fontSize = 12.sp)
            }
            if (selEvents.isEmpty()) {
                Text("（无日程）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                selEvents.take(8).forEach { e ->
                    Text(
                        java.text.SimpleDateFormat("HH:mm").format(java.util.Date(e.start)) + " " + e.title +
                            (if (e.location.isBlank()) "" else " @" + e.location),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selEvents.size > 8) Text("…共 " + selEvents.size + " 条", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun YearView(data: TimetableStore.Data, today: LocalDate, onMonth: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (0 until 12 step 2).forEach { m0 ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(m0, m0 + 1).forEach { m ->
                    val first = LocalDate.of(today.year, m + 1, 1)
                    val lead = first.dayOfWeek.value - 1
                    val days = first.lengthOfMonth()
                    Column(
                        Modifier.weight(1f).clickable { onMonth(m - (today.monthValue - 1)) },
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            "" + (m + 1) + " 月",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val cells: List<LocalDate?> = List(lead) { null } + (1..days).map { first.withDayOfMonth(it) }
                        cells.chunked(7).forEach { week ->
                            Row {
                                week.forEach { date ->
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        if (date != null) {
                                            val hol = holidayAt(data, date)
                                            Text(
                                                date.format(FMT_D),
                                                fontSize = 7.sp,
                                                color = when {
                                                    hol != null -> MaterialTheme.colorScheme.error
                                                    date == today -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "红色为假期（校历可在 agent 的 timetable 工具里维护）",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
