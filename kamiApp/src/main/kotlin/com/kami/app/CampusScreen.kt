package com.kami.app

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Campus account plane (设置 → 校园账号): WebView direct login to the Web
 * Learning Platform (the learnX route — the real page runs the CAS / dynamic
 * code JS chain itself), then one tap exports the session cookies for
 * [LearnClient]. info portal (campus card / dormitory / grade system) is not
 * ported in this batch.
 */
@Composable
internal fun CampusSection(onLogin: () -> Unit) {
    val ctx = LocalContext.current
    val s = CampusStore.get()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "登录清华网络学堂（learn.tsinghua.edu.cn）：在浏览器页完成 CAS / 动态码登录后点「完成登录」导出会话，之后 agent 可查课程/作业/公告/文件。需校园网或校内直连环境；校园卡/电费等 info 平面暂未支持。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (s == null) {
                "状态：未登录"
            } else {
                "状态：会话已保存" +
                    (if (s.username.isNotBlank()) " · ${s.username}" else "") +
                    " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.savedAt)) +
                    "（有效性由工具调用时实测）"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onLogin) { Text(if (s == null) "登录网络学堂" else "重新登录") }
            if (s != null) {
                TextButton(onClick = {
                    CampusStore.clear()
                    Toast.makeText(ctx, "已清除会话", Toast.LENGTH_SHORT).show()
                }) { Text("登出") }
            }
        }
    }
}

/** In-app browser that performs the CAS login, plus the export button. */
@Composable
internal fun CampusLoginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 取消") }
            Text(
                "网络学堂登录",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = {
                Thread {
                    val msg: String = try {
                        val s = LearnClient.exportFromWebview()
                        "已登录" + if (s.username.isNotBlank()) "（${s.username}）" else ""
                    } catch (t: Throwable) {
                        t.message ?: "导出失败"
                    }
                    (ctx as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (msg.startsWith("已登录")) onBack()
                    }
                }.start()
            }) { Text("✓ 完成登录") }
        }
        WebViewWithCookies()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewWithCookies() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                loadUrl("https://learn.tsinghua.edu.cn/f/wlxt/index/course/student/")
            }
        },
    )
}
