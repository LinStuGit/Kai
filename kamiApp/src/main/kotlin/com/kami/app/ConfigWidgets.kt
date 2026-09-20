package com.kami.app

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Renders a config widget list — shared by custom sub pages and the home
 * screen's editable area. No scrolling here; the caller's container scrolls.
 */
@Composable
internal fun ConfigWidgets(
    widgets: List<UiConfigStore.Widget>,
    onTarget: (String) -> Unit,
) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (w in widgets) {
            when (w.type) {
                "header" -> Text(
                    w.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                "text" -> Text(w.text, style = MaterialTheme.typography.bodyMedium)

                "link" -> Text(
                    w.text.ifEmpty { w.url },
                    modifier = Modifier.clickable {
                        if (w.url.isNotBlank()) WebViewer.open?.invoke(w.url)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )

                "button" -> Button(onClick = {
                    if (w.target.isNotBlank()) onTarget(w.target)
                    if (w.toast.isNotBlank()) {
                        Toast.makeText(ctx, w.toast, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(w.text.ifEmpty { "按钮" }) }

                "switch" -> {
                    var on by remember(w.key) { mutableStateOf(UiConfigStore.switchOn(w.key, w.default)) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w.text, style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = on, onCheckedChange = {
                            on = it
                            UiConfigStore.setSwitch(w.key, it)
                        })
                    }
                }
            }
        }
    }
}
