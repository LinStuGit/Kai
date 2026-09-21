package com.kami.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DrawerApp(val label: String, val pkg: String, val icon: ImageBitmap)

/** Rasterize any drawable (incl. adaptive icons with no intrinsic size). */
private fun drawableToBitmap(d: Drawable): ImageBitmap {
    val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 48
    val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 48
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    return bmp.asImageBitmap()
}

/**
 * Full-screen app drawer (launcher companion): every launchable app in a
 * 4-column grid, tap to open. Kami itself is filtered out.
 */
@Composable
internal fun AppDrawerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    // Query + icon decode off the main thread — with 200+ apps this is slow.
    var apps by remember { mutableStateOf(emptyList<DrawerApp>()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map {
                    DrawerApp(
                        it.loadLabel(pm).toString(),
                        it.activityInfo.packageName,
                        drawableToBitmap(it.activityInfo.loadIcon(pm)),
                    )
                }
        }
        loaded = true
    }
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(
                if (loaded) "应用（" + apps.size + "）" else "应用加载中…",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (apps.isEmpty()) {
            Text("未找到可启动应用（包可见性受限？）", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(apps) { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable {
                            pm.getLaunchIntentForPackage(app.pkg)?.let { launch ->
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launch)
                            }
                        }
                        .padding(4.dp),
                ) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        app.label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
