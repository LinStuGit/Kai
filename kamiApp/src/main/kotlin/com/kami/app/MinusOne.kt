package com.kami.app

import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

/** Rounded card with a small title, the dashboard's basic unit. */
@Composable
private fun DashCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** open-meteo (free, no key) pinned to the Tsinghua campus coordinates. */
@Composable
private fun WeatherCard() {
    var line by remember { mutableStateOf("天气加载中…") }
    LaunchedEffect(Unit) {
        line = withContext(Dispatchers.IO) {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=40.0035&longitude=116.3264" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=2"
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val cur = json.getJSONObject("current")
                    val daily = json.getJSONObject("daily")
                    val tMin = daily.getJSONArray("temperature_2m_min")
                    val tMax = daily.getJSONArray("temperature_2m_max")
                    "北京 · 清华  " + weatherDesc(cur.optInt("weather_code", -1)) +
                        "  " + cur.optDouble("temperature_2m", 0.0) + "°C" +
                        "｜今天 " + tMin.optDouble(0, 0.0) + "～" + tMax.optDouble(0, 0.0) + "°C" +
                        " 降水 " + daily.getJSONArray("precipitation_probability_max").optInt(0, 0) + "%" +
                        "｜明天 " + tMin.optDouble(1, 0.0) + "～" + tMax.optDouble(1, 0.0) + "°C"
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                "天气获取失败：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }
    DashCard("天气") { Text(line, style = MaterialTheme.typography.bodySmall) }
}

/** Next 3 days from the calendar provider (needs READ_CALENDAR). */
@Composable
private fun AgendaCard() {
    val context = LocalContext.current
    var line by remember { mutableStateOf("日程加载中…") }
    LaunchedEffect(Unit) {
        line = if (!CalendarTools.granted(context)) {
            "未授权日历权限——设置 → 权限管理 授权后显示近 3 天日程"
        } else {
            withContext(Dispatchers.IO) {
                CalendarTools.listEvents(context, 3).ifBlank { "近 3 天无日程" }
            }
        }
    }
    DashCard("日程") { Text(line, style = MaterialTheme.typography.bodySmall) }
}

/** Upcoming reminders, soonest first. */
@Composable
private fun TodoCard() {
    val items = ReminderStore.all()
        .sortedBy { ReminderScheduler.nextAt(it)?.timeInMillis ?: Long.MAX_VALUE }
        .take(6)
    DashCard("待办 · 定时提醒") {
        if (items.isEmpty()) {
            Text("暂无待办——可以让 agent 创建提醒", style = MaterialTheme.typography.bodySmall)
        } else {
            items.forEach { r ->
                Text(r.title + "｜" + ReminderScheduler.describe(r), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Launcher minus-one screen (swipe left from home): glanceable info. */
@Composable
internal fun MinusOneScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("负一屏", style = MaterialTheme.typography.titleLarge)
        WeatherCard()
        AgendaCard()
        TodoCard()
    }
}
