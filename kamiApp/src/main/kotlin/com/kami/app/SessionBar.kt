package com.kami.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One chip in the shared session selector row. */
internal data class SessionChip(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Session selector shared by the chat home and the terminal so both screens
 * manage sessions the same way: one collapsed chip with a fold indicator
 * (▸/▼), expanding into a horizontal row of equal chips.
 */
@Composable
internal fun SessionBar(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    chips: List<SessionChip>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(
            onClick = onToggle,
            label = { Text((if (open) "▾ " else "▸ ") + title, fontSize = 12.sp) },
        )
        if (open) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { c ->
                    AssistChip(
                        onClick = c.onClick,
                        label = {
                            Text(
                                (if (c.selected) "• " else "") + c.label,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }
        }
    }
}
