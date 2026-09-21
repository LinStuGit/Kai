package com.kami.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.icu.text.Collator
import android.icu.text.Transliterator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class DrawerApp(
    val label: String,
    val pkg: String,
    val icon: ImageBitmap,
    val letter: String,
    val pinyin: String,
    val initials: String,
)

/** Decode icons at this fixed size: full intrinsic adaptive icons (400px+)
 *  x hundreds of apps used to mean huge bitmaps and janky grid scrolling. */
private const val ICON_PX = 144

/** Rail bucket order: A-Z then #. */
private const val RANKS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"

/** Session cache: pager disposes the drawer page, don't re-query on every swipe. */
private var appCache: List<DrawerApp>? = null

/** Rasterize any drawable (incl. adaptive icons with no intrinsic size). */
private fun drawableToBitmap(d: Drawable): ImageBitmap {
    val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, ICON_PX, ICON_PX)
    d.draw(Canvas(bmp))
    return bmp.asImageBitmap()
}

/** First-letter bucket for the A-Z# rail; Chinese goes through ICU Han-Latin. */
private fun letterOf(label: String, translit: Transliterator): String {
    val first = label.trim().firstOrNull() ?: return "#"
    if (first in 'A'..'Z') return first.toString()
    if (first in 'a'..'z') return first.uppercase(Locale.getDefault())
    val latin = translit.transliterate(label.trim())
    val c = latin.firstOrNull { it.isLetter() } ?: return "#"
    val up = c.uppercaseChar()
    return if (up in 'A'..'Z') up.toString() else "#"
}

/**
 * Full-screen app drawer (launcher companion): searchable, pinyin-sorted
 * (ICU zh collator) 4-column grid with an A-Z# fast-scroll rail. Kami itself
 * is filtered out.
 */
@Composable
internal fun AppDrawerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf(emptyList<DrawerApp>()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val hit = appCache
        if (hit != null) {
            apps = hit
            loaded = true
        } else {
            val list = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val collator = Collator.getInstance(Locale.CHINA)
                val translit = Transliterator.getInstance("Any-Latin; Latin-ASCII")
                pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != context.packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map {
                        val label = it.loadLabel(pm).toString()
                        val t = label.trim()
                        DrawerApp(
                            label = label,
                            pkg = it.activityInfo.packageName,
                            icon = drawableToBitmap(it.activityInfo.loadIcon(pm)),
                            letter = letterOf(t, translit),
                            pinyin = translit.transliterate(t).lowercase(Locale.getDefault()),
                            initials = t.mapNotNull { ch ->
                                val l = letterOf(ch.toString(), translit)
                                if (l.length == 1 && l[0] in 'A'..'Z') l else null
                            }.joinToString("").lowercase(Locale.getDefault()),
                        )
                    }
                    // 字母桶为主序（A-Z，# 殿后），桶内 zh Collator——zh 拼音序与音译
                    // 首字母可能不一致，纯 Collator 排序会把同字母段切碎、侧栏乱序
                    .sortedWith(
                        Comparator { a, b ->
                            val ra = RANKS.indexOf(a.letter)
                            val rb = RANKS.indexOf(b.letter)
                            if (ra != rb) ra - rb else collator.compare(a.label, b.label)
                        },
                    )
            }
            appCache = list
            apps = list
            loaded = true
        }
    }
    BackHandler { onBack() }

    var query by rememberSaveable { mutableStateOf("") }
    val displayApps = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            apps
        } else {
            val ql = q.lowercase(Locale.getDefault())
            // 强匹配：名称包含 / 包名包含 / 全拼或拼音首字母前缀，杜绝子串误伤
            apps.filter {
                it.label.contains(q, true) ||
                    it.pkg.lowercase(Locale.getDefault()).contains(ql) ||
                    it.pinyin.startsWith(ql) ||
                    it.initials.startsWith(ql)
            }
        }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val letters = remember(displayApps) { displayApps.map { it.letter }.distinct().sortedBy { RANKS.indexOf(it) } }
    val letterIndex = remember(displayApps) {
        val m = mutableMapOf<String, Int>()
        displayApps.forEachIndexed { i, a -> if (!m.containsKey(a.letter)) m[a.letter] = i }
        m
    }
    var activeLetter by remember { mutableStateOf<String?>(null) }

    fun jump(letter: String) {
        letterIndex[letter]?.let { idx ->
            activeLetter = letter
            scope.launch { gridState.scrollToItem(idx) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(
                if (loaded) "应用（" + displayApps.size + "）" else "应用加载中…",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(L10n.s("搜索应用（名称 / 拼音 / 首字母）", "Search apps (name / pinyin / initials)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (displayApps.isEmpty()) {
            Text(
                if (query.isBlank()) "未找到可启动应用（包可见性受限？）" else "没有匹配「" + query + "」的应用",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayApps) { app ->
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
                if (query.isBlank() && letters.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(22.dp)
                            .pointerInput(letters) {
                                fun letterAt(y: Float): String? {
                                    if (letters.isEmpty()) return null
                                    val i = ((y / size.height.toFloat()) * letters.size).toInt()
                                        .coerceIn(0, letters.size - 1)
                                    return letters[i]
                                }
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, _ ->
                                        change.consume()
                                        letterAt(change.position.y)?.let { jump(it) }
                                    },
                                    onDragStart = { off ->
                                        letterAt(off.y)?.let { jump(it) }
                                    },
                                    onDragEnd = { activeLetter = null },
                                    onDragCancel = { activeLetter = null },
                                )
                            },
                    ) {
                        Column(Modifier.fillMaxHeight()) {
                            letters.forEach { l ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clickable { jump(l) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        l,
                                        fontSize = 8.sp,
                                        color = if (l == activeLetter) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            activeLetter?.let { l ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            l,
                            Modifier.padding(20.dp),
                            fontSize = 30.sp,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
    }
}
