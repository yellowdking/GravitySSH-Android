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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gravityssh.app.model.CompletionItem
import com.gravityssh.app.ui.theme.*
import kotlin.math.roundToInt

data class FloatingAutocompleteState(
  val visible: Boolean,
  val suggestions: List<CompletionItem>,
  val onPick: (CompletionItem) -> Unit,
  val onDismiss: () -> Unit
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
  val gap = 6f * density
  val margin = 8f * density

  val curTop = TERM_PAD_Y + cursorRow * cellH
  val curLeft = TERM_PAD_X + cursorCol * cellW
  val above = cursorRow > 2 && (curTop - popH - gap) >= margin
  val rawY = if (above) curTop - popH - gap else curTop + cellH + gap
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
      items.forEachIndexed { idx, item ->
        if (idx > 0) HorizontalDivider(color = AgBorder.copy(alpha = 0.65f))
        val (pillLabel, pillColor) = when (item.source) {
          "dir" -> "目录" to AgCyan
          "file" -> "文件" to AgSuccess
          "subcmd" -> item.category.ifBlank { "子命令" } to AgAccentLight
          "history" -> "历史" to AgMuted
          else -> item.category.ifBlank { "命令" } to AgViolet
        }
        val showDesc = item.source != "dir" && item.source != "file" && item.source != "history" &&
          item.description.isNotBlank() && item.description != pillLabel
        Row(
          Modifier.fillMaxWidth().height(34.dp).clickable { state.onPick(item) }.padding(horizontal = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            item.command,
            color = if (item.source == "dir") AgCyan else AgText,
            fontFamily = MonoFontFamily, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
          )
          if (showDesc) {
            Text(
              item.description, color = AgMuted, fontSize = 10.sp,
              maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 76.dp)
            )
          }
          Surface(
            shape = RoundedCornerShape(5.dp),
            color = pillColor.copy(alpha = 0.16f),
            border = BorderStroke(0.5.dp, pillColor.copy(alpha = 0.45f))
          ) {
            Text(
              pillLabel, color = pillColor, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
          }
        }
      }
    }
  }
}
