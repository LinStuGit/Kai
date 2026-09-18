package com.kami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal markdown renderer for chat output — headings, lists, quotes,
 * rules, fenced code blocks and inline bold/italic/code/strike/links.
 * Hand-rolled on purpose: no extra dependency, covers what the model emits.
 */
@Composable
internal fun MarkdownText(
    md: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val baseColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    Column(modifier = modifier) {
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    i++ // closing fence
                    Text(
                        code.toString().trimEnd('\n'),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = baseColor,
                    )
                }

                line.trimStart().startsWith("#") -> {
                    val level = line.trimStart().takeWhile { it == '#' }.length
                    val text = line.trimStart().dropWhile { it == '#' }.trim()
                    Text(
                        inline(text, baseColor),
                        modifier = Modifier.padding(top = if (level <= 2) 6.dp else 4.dp, bottom = 2.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = when (level) {
                            1 -> 18.sp
                            2 -> 16.sp
                            else -> 14.sp
                        },
                        color = baseColor,
                    )
                }

                Regex("^\\s*([-*_])\\1{2,}\\s*$").matches(line) -> {
                    Box(
                        Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(baseColor.copy(alpha = 0.25f)),
                    )
                }

                line.trimStart().startsWith(">") -> {
                    Text(
                        inline(line.trimStart().removePrefix(">").trim(), baseColor),
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        color = baseColor.copy(alpha = 0.85f),
                    )
                }

                Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(line) -> {
                    val m = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").find(line)!!
                    val bullet = if (m.groupValues[1].matches(Regex("[-*+]"))) "•" else ""
                    Row(Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
                        if (bullet.isNotEmpty()) {
                            Text("$bullet ", fontSize = 13.sp, color = baseColor)
                        }
                        Text(
                            inline(m.groupValues[2], baseColor),
                            modifier = Modifier.padding(start = if (bullet.isEmpty()) 4.dp else 0.dp),
                            fontSize = 13.sp,
                            color = baseColor,
                        )
                    }
                }

                else -> {
                    if (line.isBlank()) {
                        Box(Modifier.height(4.dp))
                    } else {
                        Text(
                            inline(line, baseColor),
                            fontSize = 13.sp,
                            color = baseColor,
                        )
                    }
                }
            }
            i++
        }
    }
}

/** Inline markdown → AnnotatedString: **bold** *italic* `code` ~~strike~~ [t](url). */
internal fun inline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    val re = Regex(
        "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|~~(.+?)~~|\\*([^*\\s][^*]*?)\\*|" +
            "\\[([^\\]]+)]\\(([^)\\s]+)\\)",
    )
    var pos = 0
    for (m in re.findAll(text)) {
        append(text.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> {
                val t = g[1].ifEmpty { g[2] }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) }
            }

            g[3].isNotEmpty() -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = baseColor.copy(alpha = 0.12f)),
            ) { append(g[3]) }

            g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(g[4])
            }

            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }

            else -> withStyle(
                SpanStyle(color = Color(0xFF9BD1FF), textDecoration = TextDecoration.Underline),
            ) { append(g[6]) }
        }
        pos = m.range.last + 1
    }
    append(text.substring(pos))
}
