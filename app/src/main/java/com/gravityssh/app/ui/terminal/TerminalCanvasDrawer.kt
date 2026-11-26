package com.gravityssh.app.ui.terminal

import android.graphics.Paint
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.nativeCanvas
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.theme.*
import kotlin.math.*

internal fun DrawScope.drawTerminalSnapshot(
  snapshot: TerminalSnapshot,
  paint: Paint,
  boldPaint: Paint,
  cellW: Float,
  cellH: Float,
  baselineOffset: Float,
  scrollOffset: Int,
  qLower: String,
  tapIndicatorCell: Pair<Int, Int>? = null,
  selection: TextSelection? = null
) {
  val px = TERM_PAD_X; val py = TERM_PAD_Y
  val qBg = AgWarning.copy(alpha = 0.35f)
  clipRect {
    drawRect(AgBg)
    val nc = drawContext.canvas.nativeCanvas
    for (r in snapshot.lines.indices) {
      val top = py + r * cellH
      if (top >= size.height) break
      val row = snapshot.lines[r]
      val base = top + baselineOffset
      val rowLow = if (qLower.isNotEmpty()) String(CharArray(row.size) { row[it].ch }).lowercase() else ""
      for (c in row.indices) {
        val cell = row[c]
        val left = px + c * cellW
        if (left >= size.width) break
        val fg = if (cell.inverse) cell.bg else cell.fg
        val bg = if (cell.inverse) cell.fg else cell.bg
        if (bg != TerminalColors.DEFAULT_BG) drawRect(Color(bg), Offset(left, top), Size(cellW + 0.6f, cellH + 0.6f))
        if (cell.ch != ' ' && cell.ch != '\u0000') {
          val p = if (cell.bold) boldPaint else paint
          p.color = fg; p.alpha = if (cell.dim) 150 else 255
          nc.drawText(cell.ch.toString(), left, base, p)
        }
        if (cell.underline) drawLine(Color(fg), Offset(left, top + cellH - 2f), Offset(left + cellW, top + cellH - 2f), 1.5f)
      }
      if (qLower.isNotEmpty()) {
        var i = rowLow.indexOf(qLower)
        while (i >= 0) {
          drawRect(qBg, Offset(px + i * cellW, top), Size(qLower.length * cellW, cellH))
          i = rowLow.indexOf(qLower, i + qLower.length)
        }
      }
    }
    tapIndicatorCell?.let { (tr, tc) ->
      if (tr in 0 until snapshot.rows && tc in 0 until snapshot.cols) {
        val tl = Offset(px + tc * cellW - 2f, py + tr * cellH - 1f)
        val sz = Size(cellW + 4f, cellH + 2f); val cr = CornerRadius(4f, 4f)
        drawRoundRect(AgCyan.copy(alpha = 0.24f), tl, sz, cr)
        drawRoundRect(AgCyan.copy(alpha = 0.9f), tl, sz, cr, style = Stroke(2f))
      }
    }
    if (snapshot.cursorVisible && scrollOffset == 0 &&
      snapshot.cursorRow in 0 until snapshot.rows && snapshot.cursorCol in 0 until snapshot.cols
    ) {
      val cTop = py + snapshot.cursorRow * cellH + 1f
      if (cTop < size.height) {
        drawRect(AgAccentLight.copy(alpha = 0.85f), Offset(px + snapshot.cursorCol * cellW, cTop), Size(2.5f.coerceAtLeast(cellW * 0.22f), cellH - 2f))
      }
    }
    if (selection != null) drawSelectionOverlay(snapshot, selection, cellW, cellH, px, py)
  }
}

private fun DrawScope.drawSelectionOverlay(
  snap: TerminalSnapshot, sel: TextSelection, cellW: Float, cellH: Float, px: Float, py: Float
) {
  if (snap.lines.isEmpty() || snap.cols <= 0) return
  val s = sel.start; val e = sel.end
  val fill = AgCyan.copy(alpha = 0.30f); val cr = CornerRadius(3f, 3f)
  for (r in s.row.coerceAtLeast(0)..e.row.coerceAtMost(snap.lines.lastIndex)) {
    val top = py + r * cellH
    if (top >= size.height) break
    val range = sel.colRangeForRow(r, snap.cols) ?: continue
    val row = snap.lines[r]
    val endC = if (s.row == e.row || r == e.row) range.last else {
      var last = -1
      for (c in minOf(range.last, row.lastIndex) downTo range.first) {
        if (row[c].ch != ' ' && row[c].ch != '\u0000') { last = c; break }
      }
      if (last >= range.first) last else range.first
    }
    drawRoundRect(fill, Offset(px + range.first * cellW, top), Size(((endC - range.first + 1) * cellW).coerceAtLeast(cellW), cellH), cr)
  }
  val rad = 11f * (cellH / 34f).coerceIn(0.8f, 1.35f)
  if (s.row in snap.lines.indices) {
    drawTeardrop(px + s.col.coerceIn(0, snap.cols - 1) * cellW, py + s.row * cellH, py + (s.row + 1) * cellH, rad, true)
  }
  if (e.row in snap.lines.indices) {
    drawTeardrop(px + (e.col.coerceIn(0, snap.cols - 1) + 1) * cellW, py + e.row * cellH, py + (e.row + 1) * cellH, rad, false)
  }
}

private fun DrawScope.drawTeardrop(ax: Float, top: Float, ay: Float, r: Float, isStart: Boolean) {
  if (top >= size.height) return
  drawLine(AgCyan, Offset(ax, top), Offset(ax, ay), strokeWidth = 2.5f)
  val bx = if (isStart) (ax - r * 0.55f).coerceAtLeast(r + 2f)
  else (ax + r * 0.55f).coerceAtMost((size.width - r - 2f).coerceAtLeast(r + 2f))
  val by = (ay + r * 1.15f).coerceAtMost((size.height - r - 2f).coerceAtLeast(r + 2f))
  val dx = ax - bx; val dy = ay - by; val d = hypot(dx, dy)
  val outline = Stroke(1.8f)
  if (d > r * 1.05f) {
    val beta = acos((r / d).coerceIn(-0.99f, 0.99f))
    val a1 = atan2(dy, dx) + beta
    val deg = (180.0 / PI).toFloat()
    val path = Path().apply {
      moveTo(ax, ay); lineTo(bx + r * cos(a1), by + r * sin(a1))
      arcTo(Rect(bx - r, by - r, bx + r, by + r), a1 * deg, 360f - 2f * beta * deg, false); close()
    }
    drawPath(path, AgCyan); drawPath(path, AgBg, style = outline)
  } else {
    drawCircle(AgCyan, r, Offset(bx, by)); drawCircle(AgBg, r, Offset(bx, by), style = outline)
  }
}
