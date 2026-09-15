package com.kami.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

/** One rendered chat line; role is "user", "assistant" or "event". */
private data class ChatLine(val role: String, val text: String)

private const val WELCOME =
    "我是 Kami agent——可以直接操作这台手机，也能把命令固化为快捷指令。" +
        "试试：「看看设备信息」或「加一个一键截屏功能」"

/** In-app agent conversation: tool activity shows as dim mono event lines. */
@Composable
internal fun ChatScreen(
    history: JSONArray,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val config = remember { AgentClient.loadConfig(context) }
    var lines by remember { mutableStateOf(listOf(ChatLine("assistant", WELCOME))) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("Agent 对话", style = MaterialTheme.typography.titleLarge)
        }

        if (config.model.isBlank()) {
            Text(
                "⚠️ 未配置模型 — 请到「设置」填写 OpenAI 兼容端点" +
                    "（Base URL / API Key / 模型名），远端 API 或局域网 llama-server 均可",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lines.size) { i ->
                val line = lines[i]
                when (line.role) {
                    "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(line.text, modifier = Modifier.padding(10.dp))
                        }
                    }

                    "event" -> Text(
                        line.text,
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Row(Modifier.fillMaxWidth()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(line.text, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("让 agent 做点什么…", fontSize = 13.sp) },
                singleLine = true,
                enabled = !busy && config.model.isNotBlank(),
                trailingIcon = if (busy) {
                    { CircularProgressIndicator(Modifier.padding(6.dp)) }
                } else {
                    null
                },
            )
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty() || busy) return@Button
                    input = ""
                    lines = lines + ChatLine("user", text)
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val reply = AgentClient.turn(config, history, text) { ev ->
                                lines = lines + ChatLine("event", ev)
                            }
                            lines = lines + ChatLine("assistant", reply)
                        } catch (t: Throwable) {
                            lines = lines + ChatLine("assistant", "❌ ${t.message}")
                        }
                        busy = false
                    }
                },
                enabled = !busy && config.model.isNotBlank() && input.isNotBlank(),
            ) { Text("发送") }
        }
    }
}
