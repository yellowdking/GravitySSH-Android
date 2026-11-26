package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    val popupHeightPx = (24f + items.size * 34f + 8f) * density
    val popupWidthPx = (268f * density).coerceAtMost((canvasSize.width - 16f * density).coerceAtLeast(160f * density))
    val gapPx = 6f * density
    val marginPx = 8f * density

    val cursorTopPx = TERM_PAD_Y + cursorRow * cellH
    val cursorLeftPx = TERM_PAD_X + cursorCol * cellW
    val placeAbove = cursorRow > 2 && (cursorTopPx - popupHeightPx - gapPx) >= marginPx
    val rawY = if (placeAbove) cursorTopPx - popupHeightPx - gapPx else cursorTopPx + cellH + gapPx
    val clampedY = rawY.coerceIn(marginPx, (canvasSize.height - popupHeightPx - marginPx).coerceAtLeast(marginPx))
    val clampedX = (cursorLeftPx - 12f * density).coerceIn(marginPx, (canvasSize.width - popupWidthPx - marginPx).coerceAtLeast(marginPx))

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AgPanel.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, AgBorderLight),
        shadowElevation = 6.dp,
        modifier = modifier
            .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
            .widthIn(min = 180.dp, max = 276.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(24.dp).padding(start = 9.dp, end = 2.dp),
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
                val tagColor = when (item.source) {
                    "shortcut" -> AgAccentLight
                    "history" -> AgCyan
                    else -> AgViolet
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(34.dp).clickable { state.onPick(item) }.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(item.command, color = AgText, fontFamily = MonoFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (item.description.isNotBlank()) {
                        Text(item.description, color = tagColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 84.dp))
                    }
                }
            }
        }
    }
}
