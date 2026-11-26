package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.terminal.TextSelection
import com.gravityssh.app.ui.theme.AgBorderLight
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgText
import kotlin.math.roundToInt

@Composable
internal fun SelectionActionToolbar(
    selection: TextSelection,
    cellW: Float,
    cellH: Float,
    canvasSize: IntSize,
    density: Float,
    onCopy: () -> Unit,
    onSelectLine: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return

    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val barW = if (barSize.width > 0) barSize.width.toFloat() else 216f * density
    val barH = if (barSize.height > 0) barSize.height.toFloat() else 38f * density
    val gapPx = 8f * density
    val marginPx = 8f * density
    val handleH = 26f * (cellH / 34f).coerceIn(0.8f, 1.35f) + 6f * density

    val s = selection.start
    val e = selection.end
    val topPx = TERM_PAD_Y + s.row * cellH
    val bottomPx = TERM_PAD_Y + (e.row + 1) * cellH

    val rawY = if (topPx >= barH + gapPx + marginPx) {
        topPx - barH - gapPx
    } else {
        bottomPx + handleH
    }
    val clampedY = rawY.coerceIn(marginPx, (canvasSize.height - barH - marginPx).coerceAtLeast(marginPx))

    val midX = TERM_PAD_X + (s.col + e.col + 1) * 0.5f * cellW
    val clampedX = (midX - barW * 0.5f).coerceIn(marginPx, (canvasSize.width - barW - marginPx).coerceAtLeast(marginPx))

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AgElevated,
        border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.55f)),
        shadowElevation = 8.dp,
        modifier = Modifier
            .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
            .onSizeChanged { barSize = it }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(38.dp)
        ) {
            SelectionToolbarButton(label = "复制", color = AgCyan, bold = true, onClick = onCopy)
            SelectionToolbarDivider()
            SelectionToolbarButton(label = "整行", color = AgText, onClick = onSelectLine)
            SelectionToolbarDivider()
            SelectionToolbarButton(label = "全选", color = AgText, onClick = onSelectAll)
            SelectionToolbarDivider()
            SelectionToolbarButton(label = "✕", color = AgMuted, onClick = onDismiss)
        }
    }
}

@Composable
private fun SelectionToolbarButton(
    label: String,
    color: Color,
    bold: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun SelectionToolbarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(18.dp)
            .background(AgBorderLight.copy(alpha = 0.7f))
    )
}
