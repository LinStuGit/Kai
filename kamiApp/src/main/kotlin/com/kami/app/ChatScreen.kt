package com.kami.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * In-app agent conversations: sessions run in parallel, switch via the chip
 * row, and each session holds its own JWT (see [JwtKeyPool]). Tool activity
 * shows as dim mono event lines; agent-initiated destructive shell commands
 * suspend in [SensitiveGate] until the biometric prompt below resolves.
 */
@Composable
internal fun ChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val sessions by SessionStore.sessions
    val activeId by SessionStore.activeId
    val session = sessions.firstOrNull { it.id == activeId } ?: sessions.first()

    // Resolve pending sensitive-command requests one by one (sequential
    // prompts; fail-open when the device has no biometrics or it's off).
    LaunchedEffect(Unit) {
        snapshotFlow { SensitiveGate.pending.value }.collect {
            val req = SensitiveGate.claimFirst() ?: return@collect
            val ok = if (activity != null && BioGate.available(context) && BioGate.enabled(context)) {
                BioGate.authenticate(activity, req.title, req.detail)
            } else {
                true
            }
            SensitiveGate.decide(req.id, ok)
        }
    }

    var input by remember(session.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lines = session.lines

    LaunchedEffect(session.id, lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                "Agent 对话",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = { SessionStore.newSession() }) { Text("＋ 新会话") }
        }

        // Parallel sessions: tap to switch (spinner = a turn is running),
        // ✕ on the active chip closes it.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sessions.forEach { s ->
                val selected = s.id == session.id
                AssistChip(
                    onClick = { SessionStore.activeId.value = s.id },
                    label = {
                        Text(
                            (if (selected) "▶" else "") +
                                (if (s.busy) "◌ " else "") +
                                s.title,
                            fontSize = 12.sp,
                        )
                    },
                )
            }
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
                enabled = !session.busy,
                trailingIcon = if (session.busy) {
                    { CircularProgressIndicator(Modifier.padding(6.dp)) }
                } else {
                    null
                },
            )
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty() || session.busy) return@Button
                    input = ""
                    val sid = session.id
                    SessionStore.update(sid) {
                        it.copy(busy = true, lines = it.lines + ChatLine("user", text))
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            val reply = AgentClient.turn(
                                sid,
                                context.applicationContext,
                                SessionStore.history(sid),
                                text,
                            ) { ev ->
                                SessionStore.update(sid) { s ->
                                    s.copy(lines = s.lines + ChatLine("event", ev))
                                }
                            }
                            SessionStore.update(sid) { s ->
                                s.copy(busy = false, lines = s.lines + ChatLine("assistant", reply))
                            }
                        } catch (t: Throwable) {
                            SessionStore.update(sid) { s ->
                                s.copy(
                                    busy = false,
                                    lines = s.lines + ChatLine("assistant", "❌ ${t.message}"),
                                )
                            }
                        }
                    }
                },
                enabled = !session.busy && input.isNotBlank(),
            ) { Text("发送") }
        }
    }
}
