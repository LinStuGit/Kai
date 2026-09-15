package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.tools.ToolApprovalController
import com.inspiredandroid.kai.tools.ToolApprovalRequest
import com.inspiredandroid.kai.ui.handCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Chat composer add-on: switches between Auto mode (tools run on their own)
 * and Manual mode (every tool call waits for an explicit user decision), and
 * renders the pending approval cards while the agent is waiting.
 */
@Composable
fun ToolApprovalBar(modifier: Modifier = Modifier) {
    val appSettings = koinInject<AppSettings>()
    val manual by appSettings.toolApprovalManualFlow.collectAsState()
    val pending by ToolApprovalController.pending.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                modifier = Modifier.handCursor(),
                selected = manual,
                onClick = { appSettings.setToolApprovalManual(!manual) },
                label = {
                    Text(text = if (manual) "手动审批" else "自动执行")
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (manual) "每条工具调用都需要批准" else "工具自动执行，无需确认",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(visible = pending.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.size(2.dp))
                for (request in pending) {
                    ToolApprovalCard(
                        request = request,
                        onDecide = { approved ->
                            scope.launch { ToolApprovalController.decide(request.callId, approved) }
                        },
                    )
                }
            }
        }
        if (pending.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
        }
    }
}

@Composable
private fun ToolApprovalCard(
    request: ToolApprovalRequest,
    onDecide: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Agent 请求执行工具，等待批准",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = request.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (request.argsJson.isNotBlank()) {
                Text(
                    text = request.argsJson.take(600),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onDecide(false) }) {
                    Text(
                        text = "拒绝",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onDecide(true) }) {
                    Text(text = "批准")
                }
            }
        }
    }
}
