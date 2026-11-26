package com.gravityssh.app.ui.terminal

import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import com.gravityssh.app.terminal.TerminalColors
import com.gravityssh.app.terminal.TerminalSnapshot
import com.gravityssh.app.ui.theme.AgAccentLight
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgWarning

internal fun DrawScope.drawTerminalSnapshot(
    snapshot: TerminalSnapshot,
    paint: Paint,
    boldPaint: Paint,
    cellW: Float,
    cellH: Float,
    baselineOffset: Float,
    scrollOffset: Int,
    qLower: String,
    tapIndicatorCell: Pair<Int, Int>? = null
) {
    val padX = TERM_PAD_X
    val padY = TERM_PAD_Y
    val highlightColor = AgWarning.copy(alpha = 0.35f)

    clipRect {
        drawRect(AgBg)
        val nc = drawContext.canvas.nativeCanvas

        for (rowIdx in snapshot.lines.indices) {
            val top = padY + rowIdx * cellH
            if (top >= size.height) break
            val row = snapshot.lines[rowIdx]
            val baseline = top + baselineOffset
            val rowTextLower = if (qLower.isNotEmpty()) String(CharArray(row.size) { row[it].ch }).lowercase() else ""

            for (colIdx in row.indices) {
                val cell = row[colIdx]
                val left = padX + colIdx * cellW
                if (left >= size.width) break
                val fgInt = if (cell.inverse) cell.bg else cell.fg
                val bgInt = if (cell.inverse) cell.fg else cell.bg

                if (bgInt != TerminalColors.DEFAULT_BG) {
                    drawRect(Color(bgInt), Offset(left, top), Size(cellW + 0.6f, cellH + 0.6f))
                }
                if (cell.ch != ' ' && cell.ch != '\u0000') {
                    val p = if (cell.bold) boldPaint else paint
                    p.color = fgInt
                    p.alpha = if (cell.dim) 150 else 255
                    nc.drawText(cell.ch.toString(), left, baseline, p)
                }
                if (cell.underline) {
                    drawLine(Color(fgInt), Offset(left, top + cellH - 2f), Offset(left + cellW, top + cellH - 2f), 1.5f)
                }
            }

            if (qLower.isNotEmpty()) {
                var idx = rowTextLower.indexOf(qLower)
                while (idx >= 0) {
                    drawRect(highlightColor, Offset(padX + idx * cellW, top), Size(qLower.length * cellW, cellH))
                    idx = rowTextLower.indexOf(qLower, idx + qLower.length)
                }
            }
        }

        tapIndicatorCell?.let { (tRow, tCol) ->
            if (tRow in 0 until snapshot.rows && tCol in 0 until snapshot.cols) {
                val tl = Offset(padX + tCol * cellW - 2f, padY + tRow * cellH - 1f)
                val sz = Size(cellW + 4f, cellH + 2f)
                drawRoundRect(AgCyan.copy(alpha = 0.24f), tl, sz, CornerRadius(4f, 4f))
                drawRoundRect(AgCyan.copy(alpha = 0.9f), tl, sz, CornerRadius(4f, 4f), style = Stroke(width = 2f))
            }
        }

        if (snapshot.cursorVisible && scrollOffset == 0 &&
            snapshot.cursorRow in 0 until snapshot.rows &&
            snapshot.cursorCol in 0 until snapshot.cols
        ) {
            val cursorTop = padY + snapshot.cursorRow * cellH + 1f
            if (cursorTop < size.height) {
                drawRect(
                    color = AgAccentLight.copy(alpha = 0.85f),
                    topLeft = Offset(padX + snapshot.cursorCol * cellW, cursorTop),
                    size = Size(2.5f.coerceAtLeast(cellW * 0.22f), cellH - 2f)
                )
            }
        }
    }
}
