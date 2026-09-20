package com.kami.app

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Holder for "open this URL in the in-app browser" — assigned by the
 * MainActivity routing, used by markdown links and config-page link widgets.
 */
object WebViewer {
    var open: ((String) -> Unit)? = null
}

/** Minimal in-app browser screen: a toolbar row with the URL plus a WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebViewScreen(url: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                url,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl(url)
                }
            },
        )
    }
}
