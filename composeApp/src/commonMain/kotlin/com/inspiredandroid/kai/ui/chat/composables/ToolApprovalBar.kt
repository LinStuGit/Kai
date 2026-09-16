package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.tools.ToolApprovalController
import com.inspiredandroid.kai.tools.ToolApprovalRequest
import com.inspiredandroid.kai.ui.handCursor
import com.inspiredandroid.kai.ui.kaiAdaptiveCardBorder
import com.inspiredandroid.kai.ui.kaiAdaptiveCardColors
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.ic_bolt
import kai.composeapp.generated.resources.ic_pan_tool
import kai.composeapp.generated.resources.tool_approval_approve
import kai.composeapp.generated.resources.tool_approval_auto
import kai.composeapp.generated.resources.tool_approval_deny
import kai.composeapp.generated.resources.tool_approval_manual
import kai.composeapp.generated.resources.tool_approval_wait
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * Composer-embedded switch between Auto mode (tools run on their own) and
 * Manual mode (every tool call waits for an explicit user decision). Styled
 * like [ServiceSelector]'s round entry so it sits next to the model switch
 * inside the text field. Parameterized state keeps it preview/test-friendly.
 */
@Composable
fun ToolApprovalToggle(
    manual: Boolean,
    onToggleManual: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                color = if (manual) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = CircleShape,
            )
            .clickable { onToggleManual(!manual) }
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vectorResource(if (manual) Res.drawable.ic_pan_tool else Res.drawable.ic_bolt),
            contentDescription = stringResource(if (manual) Res.string.tool_approval_manual else Res.string.tool_approval_auto),
            modifier = Modifier.size(18.dp),
            tint = if (manual) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Pending manual-approval cards, rendered above the composer while the agent
 * waits for decisions. Hidden entirely when nothing is pending.
 */
@Composable
fun PendingToolApprovalCards(modifier: Modifier = Modifier) {
    val pending by ToolApprovalController.pending.collectAsState()
    val scope = rememberCoroutineScope()

    AnimatedVisibility(visible = pending.isNotEmpty(), modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
}

@Composable
private fun ToolApprovalCard(
    request: ToolApprovalRequest,
    onDecide: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.tool_approval_wait),
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
                        text = stringResource(Res.string.tool_approval_deny),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onDecide(true) }) {
                    Text(text = stringResource(Res.string.tool_approval_approve))
                }
            }
        }
    }
}
