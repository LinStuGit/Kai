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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Campus account plane (设置 → 校园账号): two WebView direct logins — the Web
 * Learning Platform (learn.tsinghua.edu.cn, feeding [LearnClient]) and 荷塘
 * 雨课堂 (pro.yuketang.cn, feeding [YuketangClient]). The real pages run their
 * own CAS / dynamic-code JS chains; one tap exports the session cookies.
 */
@Composable
internal fun CampusSection(onLogin: () -> Unit, onLoginYk: () -> Unit, onLoginWebvpn: () -> Unit) {
    val ctx = LocalContext.current
    val s = CampusStore.get()
    val ykCookie = YuketangClient.cookie()
    val webvpnSavedAt = MadModelAuth.savedAt()
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
        HorizontalDivider()
        Text(
            "登录荷塘雨课堂（pro.yuketang.cn）：在浏览器页完成统一身份登录后点「完成登录」导出会话，agent 可查课程/作业/考试（只读，不代答题不代提交）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (ykCookie.isBlank()) {
                "状态：未登录"
            } else {
                "状态：会话已保存 · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(YuketangClient.savedAt()))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onLoginYk) { Text(if (ykCookie.isBlank()) "登录雨课堂" else "重新登录") }
            if (ykCookie.isNotBlank()) {
                TextButton(onClick = {
                    Toast.makeText(ctx, YuketangClient.clear(), Toast.LENGTH_SHORT).show()
                }) { Text("登出") }
            }
        }
        HorizontalDivider()
        Text(
            "默认模型（madmodel）key：2026-09 起网关只收 CAS ticket（一账号同时只有一个存活 key，新取顶掉旧 key）。凭据直连统一认证签发（PC madmodel_router 同款链路，不经 webvpn、不受校园网 IP 变化影响）；账号密码仅存本机应用私有目录。受信设备指纹可免二次认证——首次如要求二次认证，填 TOTP 密钥或复用 PC 端已信任的指纹。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var idUser by remember { mutableStateOf(MadModelAuth.idUser()) }
        var idPass by remember { mutableStateOf(MadModelAuth.idPass()) }
        var idTotp by remember { mutableStateOf(MadModelAuth.totpSecret()) }
        var idFp by remember { mutableStateOf(if (MadModelAuth.idUser().isBlank()) "" else MadModelAuth.fingerprint()) }
        OutlinedTextField(
            value = idUser,
            onValueChange = { idUser = it },
            label = { Text("统一身份账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = idPass,
            onValueChange = { idPass = it },
            label = { Text("统一身份密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = idTotp,
            onValueChange = { idTotp = it },
            label = { Text("TOTP 密钥（可选，Base32）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = idFp,
            onValueChange = { idFp = it },
            label = { Text("设备指纹（可选；PC router 已信任的可复用）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (webvpnSavedAt == 0L) {
                "状态：未配置（默认模型无法取 key）"
            } else {
                "状态：凭据已保存 · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(webvpnSavedAt))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                Thread {
                    val msg: String = try {
                        MadModelAuth.saveIdp(idUser, idPass, idTotp, idFp)
                        JwtKeyPool.dropAll()
                        JwtKeyPool.acquire("settings")
                        "已取到模型 key"
                    } catch (t: Throwable) {
                        "取 key 失败：${t.message}"
                    }
                    (ctx as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }) { Text("保存并获取 key") }
            if (webvpnSavedAt != 0L) {
                TextButton(onClick = {
                    Toast.makeText(ctx, MadModelAuth.clear(), Toast.LENGTH_SHORT).show()
                }) { Text("清除") }
            }
        }
        TextButton(onClick = onLoginWebvpn) {
            Text("网页登录（webvpn 门户漫游备用路线）")
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
                // 移动端适配：整页适配屏宽 + 允许双指缩放（CAS/登录页是桌面排版）
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                loadUrl("https://learn.tsinghua.edu.cn/f/wlxt/index/course/student/")
            }
        },
    )
}

/** Same shell as [CampusLoginScreen], pointed at 荷塘雨课堂. */
@Composable
internal fun YuketangLoginScreen(onBack: () -> Unit) {
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
                "雨课堂登录",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = {
                Thread {
                    val msg: String = try {
                        YuketangClient.exportFromWebview()
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
        YuketangWebView()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YuketangWebView() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 移动端适配：整页适配屏宽 + 允许双指缩放（CAS/登录页是桌面排版）
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                loadUrl("https://pro.yuketang.cn/web")
            }
        },
    )
}

/** Same shell, pointed at webvpn.tsinghua.edu.cn — feeds [MadModelAuth]. */
@Composable
internal fun WebvpnLoginScreen(onBack: () -> Unit) {
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
                "信息门户登录",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = {
                Thread {
                    val msg: String = try {
                        MadModelAuth.exportFromWebview()
                    } catch (t: Throwable) {
                        t.message ?: "导出失败"
                    }
                    (ctx as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (!msg.contains("失败") && !msg.contains("未检测")) onBack()
                    }
                }.start()
            }) { Text("✓ 完成登录") }
        }
        WebvpnWebView()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebvpnWebView() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                // 直达个人信息页（需门户登录）：门户首页匿名可见，落那里只种
                // 得到 webvpn 外层会话；此页在内层匿名时会自动带出统一身份
                // 登录页，外层+内层两段登录从同一入口串起来
                loadUrl(MadModelAuth.USER_DATA_URL)
            }
        },
    )
}
