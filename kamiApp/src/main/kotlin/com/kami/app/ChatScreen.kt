package com.kami.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A render item: a plain chat line, or consecutive tool events as one group. */
private sealed interface ChatEntry {
    data class One(val line: ChatLine) : ChatEntry

    data class Tools(val start: Int, val events: List<String>) : ChatEntry
}

private fun buildEntries(lines: List<ChatLine>): List<ChatEntry> = buildList {
    var start = -1
    val events = mutableListOf<String>()
    lines.forEachIndexed { i, line ->
        if (line.role == "event") {
            if (start < 0) start = i
            events.add(line.text)
        } else {
            if (start >= 0) {
                add(ChatEntry.Tools(start, events.toList()))
                start = -1
                events.clear()
            }
            add(ChatEntry.One(line))
        }
    }
    if (start >= 0) add(ChatEntry.Tools(start, events.toList()))
}

/**
 * The app home: agent conversations running in parallel (one JWT each),
 * switchable via the chip row. Reasoning renders as a collapsed "思考过程"
 * block and tool activity as a collapsed "工具调用" block, tap either to
 * expand. While a turn runs and the user leaves the app,
 * [AgentOverlayService] shows live output with a force-stop.
 */
@Composable
internal fun ChatScreen(
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onArchive: () -> Unit,
    homeWidgets: List<UiConfigStore.Widget> = emptyList(),
    onTarget: (String) -> Unit = {},
) {
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
    val entries = remember(lines) { buildEntries(lines) }

    // Expanded tool groups, keyed by each group's start line so state
    // survives recomposition while events stream in.
    val openGroups = remember { mutableStateListOf<Int>() }

    LaunchedEffect(session.id, lines.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Kami",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = onTerminal) { Text("终端") }
            TextButton(onClick = onSettings) { Text("设置") }
        }

        // Parallel sessions: the shared selector bar (same as the terminal).
        var sessionsOpen by remember { mutableStateOf(false) }
        SessionBar(
            title = "会话(${sessions.size}) · " + session.title + if (session.busy) " …" else "",
            open = sessionsOpen,
            onToggle = { sessionsOpen = !sessionsOpen },
            chips = buildList {
                sessions.forEach { s ->
                    add(
                        SessionChip(
                            label = s.title + if (s.busy) " …" else "",
                            selected = s.id == session.id,
                            onClick = { SessionStore.activeId.value = s.id },
                        ),
                    )
                }
                add(SessionChip("＋ 新会话") { SessionStore.newSession() })
                if (sessions.any { it.busy }) {
                    add(SessionChip("■ 终止") { AgentOverlayState.cancelAll() })
                }
                add(SessionChip("历史") { onArchive() })
            },
        )

        // Config-defined home widgets (kami_ui.json "home") — the editable
        // slice of the home screen.
        if (homeWidgets.isNotEmpty()) {
            ConfigWidgets(widgets = homeWidgets, onTarget = onTarget)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries.size) { i ->
                when (val e = entries[i]) {
                    is ChatEntry.Tools -> ToolBlock(
                        count = e.events.size,
                        open = e.start in openGroups,
                        onToggle = {
                            if (e.start in openGroups) openGroups.remove(e.start) else openGroups.add(e.start)
                        },
                        events = e.events,
                    )

                    is ChatEntry.One -> when (e.line.role) {
                        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(e.line.text, modifier = Modifier.padding(10.dp))
                            }
                        }

                        else -> Column(Modifier.fillMaxWidth()) {
                            e.line.thinking?.let { ThinkingBlock(it) }
                            Row(Modifier.fillMaxWidth()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    MarkdownText(
                                        e.line.text,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }
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
                    val appCtx = context.applicationContext
                    SessionStore.update(sid) {
                        it.copy(busy = true, lines = it.lines + ChatLine("user", text))
                    }
                    SessionStore.retitlePlaceholder(sid, text)
                    val job = scope.launch(Dispatchers.IO) {
                        try {
                            val reply = AgentClient.turn(
                                sid,
                                appCtx,
                                SessionStore.history(sid),
                                text,
                            ) { ev ->
                                AgentOverlayState.status.value = ev
                                SessionStore.update(sid) { s ->
                                    s.copy(lines = s.lines + ChatLine("event", ev))
                                }
                            }
                            SessionStore.update(sid) { s ->
                                s.copy(
                                    busy = false,
                                    lines = s.lines + ChatLine("assistant", reply.text, reply.thinking),
                                )
                            }
                        } catch (t: Throwable) {
                            val msg = if (t is CancellationException || !AgentOverlayState.running.value) {
                                "已强制终止"
                            } else {
                                "错误：${t.message}"
                            }
                            SessionStore.update(sid) { s ->
                                s.copy(busy = false, lines = s.lines + ChatLine("assistant", msg))
                            }
                        } finally {
                            AgentOverlayState.refresh()
                            if (!AgentOverlayState.running.value) {
                                Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
                            }
                            // The overlay service owns its own lifecycle: its
                            // 500ms tick sees running=false within half a
                            // second and either shows the completion panel
                            // (screen runs) or stops itself. Stopping it here
                            // would kill it before that branch can run.
                        }
                    }
                    AgentOverlayState.status.value = ""
                    AgentOverlayState.add(job)
                    // Overlay window only works with the special-access grant.
                    if (Settings.canDrawOverlays(appCtx)) {
                        runCatching {
                            ContextCompat.startForegroundService(
                                appCtx,
                                Intent(appCtx, AgentOverlayService::class.java),
                            )
                        }
                    }
                },
                enabled = !session.busy && input.isNotBlank(),
            ) { Text("发送") }
        }
    }
}

/** Model reasoning, collapsed by default; tap the header to expand. */
@Composable
private fun ThinkingBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                if (open) "▼ 思考过程" else "▸ 思考过程",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                Text(
                    text,
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One turn's tool activity, collapsed by default; tap the header to expand. */
@Composable
private fun ToolBlock(
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    events: List<String>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                if (open) "▼ 工具调用 ×$count" else "▸ 工具调用 ×$count",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                events.forEach { ev ->
                    Text(
                        ev,
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
