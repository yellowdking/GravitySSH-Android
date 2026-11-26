package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gravityssh.app.model.CompletionItem
import com.gravityssh.app.terminal.AutocompleteParser
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
  state: FloatingAutocompleteState,
  cursorRow: Int,
  cursorCol: Int,
  cellW: Float,
  cellH: Float,
  canvasSize: IntSize,
  density: Float,
  modifier: Modifier = Modifier
) {
  if (!state.visible) return
  val items = state.suggestions.take(3)
  if (items.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return

  val popH = (24f + items.size * 34f + 8f) * density
  val popW = (276f * density).coerceAtMost((canvasSize.width - 16f * density).coerceAtLeast(160f * density))
  val gap = 6f * density; val margin = 8f * density
  val curTop = TERM_PAD_Y + cursorRow * cellH; val curLeft = TERM_PAD_X + cursorCol * cellW
  val rawY = if (cursorRow > 2 && curTop - popH - gap >= margin) curTop - popH - gap else curTop + cellH + gap
  val clampedY = rawY.coerceIn(margin, (canvasSize.height - popH - margin).coerceAtLeast(margin))
  val clampedX = (curLeft - 12f * density).coerceIn(margin, (canvasSize.width - popW - margin).coerceAtLeast(margin))

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = AgPanel.copy(alpha = 0.95f),
    border = BorderStroke(1.dp, AgBorderLight),
    shadowElevation = 6.dp,
    modifier = modifier.offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }.widthIn(min = 190.dp, max = 284.dp)
  ) {
    Column(Modifier.fillMaxWidth()) {
      Row(
        Modifier.fillMaxWidth().height(24.dp).padding(start = 9.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text("智能补全", color = AgMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        IconButton(onClick = state.onDismiss, modifier = Modifier.size(22.dp)) {
          Icon(Icons.Rounded.Close, "关闭补全", tint = AgMuted, modifier = Modifier.size(13.dp))
        }
      }
      val fullIn = state.inputLine.trimStart()
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
        val showDesc = item.source !in HIDE_DESC && item.description.isNotBlank() && item.description != lbl
        val pfx = when {
          state.inputLine.isBlank() -> state.activePrefix
          (item.source == "history" || item.source == "history_local") && fullIn.isNotEmpty() && item.command.startsWith(fullIn, true) -> fullIn
          else -> AutocompleteParser.activeTokenPrefix(state.inputLine, item.source)
        }
        val cmdText = buildAnnotatedString {
          if (pfx.isNotEmpty() && item.command.startsWith(pfx, true)) {
            val n = pfx.length.coerceAtMost(item.command.length)
            withStyle(SpanStyle(color = AgCyan, fontWeight = FontWeight.Bold)) { append(item.command.take(n)) }
            withStyle(SpanStyle(color = AgText)) { append(item.command.drop(n)) }
          } else {
            withStyle(SpanStyle(color = AgText)) { append(item.command) }
          }
        }
        Row(
          Modifier.fillMaxWidth().height(34.dp).clickable { state.onPick(item) }.padding(horizontal = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(cmdText, fontFamily = MonoFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          if (showDesc) Text(item.description, color = AgMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 76.dp))
          Surface(shape = RoundedCornerShape(5.dp), color = clr.copy(alpha = 0.16f), border = BorderStroke(0.5.dp, clr.copy(alpha = 0.45f))) {
            Text(lbl, color = clr, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
          }
        }
      }
    }
  }
}
