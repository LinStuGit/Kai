package com.kami.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Minimal outlined paperclip (material attach_file path) — no emoji, matches the app's flat icon style. */
private val PaperclipIcon: ImageVector = ImageVector.Builder(
    name = "Paperclip",
    defaultWidth = 22.dp,
    defaultHeight = 22.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        pathData = addPathNodes(
            "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 " +
                "2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5" +
                "c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 " +
                "7,5v12.5C7,20.54 9.46,23 12.5,23s5.5,-2.46 5.5,-5.5V6h-1.5z",
        ),
        fill = SolidColor(Color.Black),
    )
}.build()

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
    val scope = rememberCoroutineScope()
    val sessions by SessionStore.sessions
    val activeId by SessionStore.activeId
    val session = sessions.firstOrNull { it.id == activeId } ?: sessions.first()

    // NOTE: sensitive-gate (biometric) resolving moved to MainActivity's root
    // composables — it must stay alive on every screen, not just the home.

    // rememberSaveable: pager disposes offscreen pages, draft must survive the swipe.
    var input by rememberSaveable(session.id) { mutableStateOf("") }
    // Pending chat attachments (picked via SAF; resolved to payloads on send).
    val pendingAtts = remember { mutableStateListOf<Pair<Uri, String>>() }
    DisposableEffect(Unit) {
        MainActivity.filePickCallback = { uris ->
            for (u in uris) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                pendingAtts.add(u to Attachments.nameOf(context, u))
            }
        }
        onDispose { if (MainActivity.filePickCallback != null) MainActivity.filePickCallback = null }
    }
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
            TextButton(onClick = onTerminal) { Text(L10n.s("终端", "Terminal")) }
            TextButton(onClick = onSettings) { Text(L10n.s("设置", "Settings")) }
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
                add(SessionChip(L10n.s("＋ 新会话", "＋ New chat")) { SessionStore.newSession() })
                if (sessions.any { it.busy }) {
                    add(SessionChip(L10n.s("■ 终止", "■ Stop")) { AgentOverlayState.cancelAll() })
                }
                add(SessionChip(L10n.s("历史", "History")) { onArchive() })
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

        if (pendingAtts.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pendingAtts.forEach { att ->
                    TextButton(
                        onClick = { pendingAtts.remove(att) },
                        enabled = !session.busy,
                    ) { Text(att.second + " ✕", fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { MainActivity.startFilePick() },
                enabled = !session.busy,
            ) { Icon(PaperclipIcon, contentDescription = "附件", tint = LocalContentColor.current) }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(L10n.s("让 agent 做点什么…", "Ask the agent…"), fontSize = 13.sp) },
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
                    if (session.busy || (text.isEmpty() && pendingAtts.isEmpty())) return@Button
                    val attUris = pendingAtts.map { it.first }
                    val attNames = pendingAtts.map { it.second }
                    pendingAtts.clear()
                    input = ""
                    val sid = session.id
                    val appCtx = context.applicationContext
                    SessionStore.update(sid) {
                        it.copy(busy = true, lines = it.lines + ChatLine("user", text + Attachments.summary(attNames)))
                    }
                    SessionStore.retitlePlaceholder(sid, text.ifBlank { "📎 " + attNames.joinToString("、") })
                    val job = scope.launch(Dispatchers.IO) {
                        try {
                            val atts = attUris.mapNotNull { Attachments.load(appCtx, it) }
                            val reply = AgentClient.turn(
                                sid,
                                appCtx,
                                SessionStore.history(sid),
                                text,
                                atts,
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
                                L10n.s("已强制终止", "Force stopped")
                            } else {
                                L10n.s("错误：", "Error: ") + t.message
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
                enabled = !session.busy && (input.isNotBlank() || pendingAtts.isNotEmpty()),
            ) { Text(L10n.s("发送", "Send")) }
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
                if (open) "▼ " + L10n.s("思考过程", "Reasoning") else "▸ " + L10n.s("思考过程", "Reasoning"),
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
