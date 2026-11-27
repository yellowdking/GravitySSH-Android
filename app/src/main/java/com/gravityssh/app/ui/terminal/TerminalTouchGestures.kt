package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.gravityssh.app.terminal.*
import com.gravityssh.app.terminal.SelectionHandle.*
import com.gravityssh.app.terminal.TouchCursorMode.*
import kotlin.math.*

internal const val TERM_PAD_X = 12f
internal const val TERM_PAD_Y = 4f

private suspend fun AwaitPointerEventScope.nextPos(id: PointerId) =
    awaitPointerEvent().also { it.changes.forEach { c -> c.consume() } }.changes.firstOrNull { it.id == id && it.pressed }?.position

internal suspend fun PointerInputScope.detectTerminalTouchGestures(
    density: Float, getMode: () -> TouchCursorMode, getIsAltScreen: () -> Boolean,
    getCellW: () -> Float, getCellH: () -> Float, getFontSizeSp: () -> Float,
    getScrollOffset: () -> Int, getScrollbackSize: () -> Int,
    onCancelFling: () -> Unit, onTapCell: (row: Int, col: Int) -> Unit,
    getSelection: () -> TextSelection?, onStartSelectionAt: (row: Int, col: Int) -> Unit,
    onUpdateSelection: (TextSelection?) -> Unit, onSelectionHaptic: () -> Unit,
    onFontSizeChange: (Float) -> Unit, onFontSizeCommit: (Float) -> Unit,
    onScrollOffsetChange: (Int) -> Unit,
    onCursorDragStart: () -> Unit,
    onCursorDragDelta: (dxDp: Float, dyDp: Float, dtSec: Float) -> Unit,
    onCursorDragEnd: (vxDpPerSec: Float, vyDpPerSec: Float) -> Unit
) {
    val vc = viewConfiguration; val slop = vc.touchSlop; val lpMs = vc.longPressTimeoutMillis
    val px = TERM_PAD_X; val py = TERM_PAD_Y
    fun cw() = getCellW().coerceAtLeast(6f)
    fun ch() = getCellH().coerceAtLeast(10f)
    fun lim(s: Int, p: Float, u: Float) = if (s > 0) (floor((s - p * 2f) / u).toInt() - 1).coerceAtLeast(0) else Int.MAX_VALUE
    fun cellAt(x: Float, y: Float): CellPos {
        val w = cw(); val h = ch()
        return CellPos(floor((y - py) / h).toInt().coerceIn(0, lim(size.height, py, h)), floor((x - px) / w).toInt().coerceIn(0, lim(size.width, px, w)))
    }
    awaitEachGesture {
        val down = awaitFirstDown(); onCancelFling()
        val p0 = down.position; val sel = getSelection()
        if (sel != null) {
            val w = cw(); val h = ch(); val rad = 30f * density
            val hit = TerminalSelectionHelper.hitTestHandle(p0.x, p0.y, sel, w, h, px, py, rad)
            if (hit != NONE) {
                down.consume()
                val isS = hit == START; val anc = if (isS) sel.end else sel.start; var last = if (isS) sel.start else sel.end
                val dx = (p0.x - (px + (last.col + 0.5f) * w)).coerceIn(-rad, rad)
                val dy = (p0.y - (py + (last.row + 0.5f) * h)).coerceIn(0.6f * h, (rad * 1.4f).coerceAtLeast(0.6f * h))
                while (true) {
                    val p = nextPos(down.id) ?: break
                    val n = cellAt(p.x - dx, p.y - dy)
                    if (n != last) { last = n; onUpdateSelection(TextSelection(anc, n)); onSelectionHaptic() }
                }
                return@awaitEachGesture
            }
        }
        val t0 = down.uptimeMillis; val ns0 = System.nanoTime()
        var pm = t0; var dr = false; var mu = false; var zoomed = false; var lpF = false; var upC = false
        var sAcc = 0f; var cDr = false; var hz = false
        var curFont = getFontSizeSp()
        val tr = VelocityTracker().apply { addPosition(t0, p0) }
        while (true) {
            val ev = if (!dr && !mu && !lpF) {
                val rem = lpMs - (System.nanoTime() - ns0) / 1_000_000L
                if (rem <= 0L) null else withTimeoutOrNull(rem) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (ev == null) { lpF = true; break }
            val pr = ev.changes.filter { it.pressed }
            if (pr.isEmpty()) { upC = ev.changes.any { it.isConsumed }; break }
            if (pr.size >= 2) {
                if (!mu) {
                    mu = true
                    curFont = getFontSizeSp()
                    if (cDr) { cDr = false; onCursorDragEnd(0f, 0f) }
                }
                val z = ev.calculateZoom()
                if (abs(z - 1f) > 0.001f) {
                    val nextFont = (curFont * z).coerceIn(10f, 22f)
                    if (nextFont != curFont) {
                        curFont = nextFont
                        zoomed = true
                        onFontSizeChange(curFont)
                    }
                }
                ev.changes.forEach { it.consume() }; continue
            }
            if (mu) continue
            val chg = pr.first(); val pos = chg.position; val d = chg.positionChange()
            val now = chg.uptimeMillis; val dt = (now - pm).coerceAtLeast(4L) / 1000f
            pm = now; tr.addPosition(now, pos)
            if (!dr) {
                val rx = pos.x - p0.x; val ry = pos.y - p0.y
                if (hypot(rx, ry) >= slop) {
                    dr = true; if (sel != null) onUpdateSelection(null)
                    val m = getMode(); val alt = getIsAltScreen()
                    if (m == CURSOR || (m == AUTO && (alt || abs(rx) > abs(ry) * 1.75f))) {
                        cDr = true; hz = !alt && m == AUTO; onCursorDragStart()
                    }
                } else if (!lpF && (now - t0) >= lpMs) { lpF = true; break }
            }
            if (dr && (d.x != 0f || d.y != 0f)) {
                chg.consume()
                if (cDr) onCursorDragDelta(d.x / density, if (hz) 0f else d.y / density, dt)
                else {
                    sAcc += d.y; val h = ch(); val st = (sAcc / h).toInt()
                    if (st != 0) { sAcc -= st * h; onScrollOffsetChange((getScrollOffset() + st).coerceIn(0, getScrollbackSize())) }
                }
            }
        }
        if (zoomed) {
            onFontSizeCommit(curFont)
        }
        if (lpF) {
            val c0 = cellAt(p0.x, p0.y); onStartSelectionAt(c0.row, c0.col)
            var mv = false; var anc: CellPos? = null; var last: CellPos? = null
            while (true) {
                val p = nextPos(down.id) ?: break
                if (!mv) { if (hypot(p.x - p0.x, p.y - p0.y) < slop) continue; mv = true }
                val cur = cellAt(p.x, p.y)
                val a = anc ?: (getSelection()?.takeIf { it != sel }?.let { if (cur < c0) it.end else it.start } ?: c0).also { anc = it }
                if (cur != last) { last = cur; onUpdateSelection(TextSelection(a, cur)); onSelectionHaptic() }
            }
        } else if (cDr) {
            val v = tr.calculateVelocity(); onCursorDragEnd(v.x / density, if (hz) 0f else v.y / density)
        } else if (!dr && !mu && !upC) {
            if (sel != null) onUpdateSelection(null) else cellAt(p0.x, p0.y).let { onTapCell(it.row, it.col) }
        }
    }
}
