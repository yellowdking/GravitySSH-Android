package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gravityssh.app.model.CompletionItem
import com.gravityssh.app.terminal.AutocompleteParser
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.theme.*
import kotlin.math.roundToInt

private val HIDE_DESC = setOf("dir", "file", "history", "history_local")

data class FloatingAutocompleteState(
    val visible: Boolean,
    val suggestions: List<CompletionItem>,
    val onPick: (CompletionItem) -> Unit,
    val onDismiss: () -> Unit,
    val activePrefix: String = "",
    val inputLine: String = ""
)

@Composable
fun FloatingAutocompletePopup(
    state: FloatingAutocompleteState, cursorRow: Int, cursorCol: Int,
    cellW: Float, cellH: Float, canvasSize: IntSize, density: Float, modifier: Modifier = Modifier
) {
    if (!state.visible) return
    val items = state.suggestions.take(3)
    if (items.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return

    val haptic = LocalHapticFeedback.current
    val popH = (24f + items.size * 42f + 8f) * density
    val popW = (276f * density).coerceAtMost((canvasSize.width - 16f * density).coerceAtLeast(160f * density))
    val gap = 6f * density; val m = 8f * density
    val cTop = TERM_PAD_Y + cursorRow * cellH; val cLeft = TERM_PAD_X + cursorCol * cellW
    val rawY = if (cursorRow > 2 && cTop - popH - gap >= m) cTop - popH - gap else cTop + cellH + gap
    val cY = rawY.coerceIn(m, (canvasSize.height - popH - m).coerceAtLeast(m))
    val cX = (cLeft - 12f * density).coerceIn(m, (canvasSize.width - popW - m).coerceAtLeast(m))

    val parsed = remember(state.inputLine) {
        if (state.inputLine.isBlank()) null else AutocompleteParser.parseLine(state.inputLine, "/")
    }
    val curTokenUnquoted = if (parsed == null || parsed.endsWithSpace) "" else parsed.curTok.unquoted
    val pathTokenPrefix = curTokenUnquoted.substringAfterLast('/')
    val fullIn = state.inputLine.trimStart()

    Surface(
        shape = RoundedCornerShape(10.dp), color = AgPanel.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, AgBorderLight), shadowElevation = 6.dp,
        modifier = modifier.offset { IntOffset(cX.roundToInt(), cY.roundToInt()) }.widthIn(min = 190.dp, max = 284.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 9.dp, end = 2.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("智能补全", color = AgMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = state.onDismiss, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Rounded.Close, "关闭补全", tint = AgMuted, modifier = Modifier.size(13.dp))
                }
            }
            items.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = AgBorder.copy(alpha = 0.65f))
                val (lbl, clr) = when (item.source) {
                    "dir" -> "目录" to AgCyan
                    "file" -> "文件" to AgSuccess
                    "subcmd" -> item.category.ifBlank { "命令" } to AgAccentLight
                    "flag" -> "参数" to AgWarning
                    "history_local" -> "本机历史" to AgMuted
                    "history" -> "历史" to AgMuted
                    else -> item.category.ifBlank { "命令" } to AgAccent
                }
                val showD = item.source !in HIDE_DESC && item.description.isNotBlank() && item.description != lbl
                val pfx = when {
                    parsed == null -> state.activePrefix
                    (item.source == "history" || item.source == "history_local") && fullIn.isNotEmpty() && item.command.startsWith(fullIn, true) -> fullIn
                    item.source == "history" || item.source == "history_local" -> parsed.activeSeg
                    item.source == "dir" || item.source == "file" -> pathTokenPrefix
                    else -> curTokenUnquoted
                }
                val cmd = remember(item.command, pfx) {
                    buildAnnotatedString {
                        if (pfx.isNotEmpty() && item.command.startsWith(pfx, true)) {
                            val n = pfx.length.coerceAtMost(item.command.length)
                            withStyle(SpanStyle(color = AgCyan, fontWeight = FontWeight.Bold)) { append(item.command.take(n)) }
                            withStyle(SpanStyle(color = AgText)) { append(item.command.drop(n)) }
                        } else withStyle(SpanStyle(color = AgText)) { append(item.command) }
                    }
                }
                Surface(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); state.onPick(item) },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                        Text(cmd, fontFamily = MonoFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (showD) Text(item.description, color = AgMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 76.dp))
                        AgBadge(text = lbl, color = clr)
                    }
                }
            }
        }
    }
}
