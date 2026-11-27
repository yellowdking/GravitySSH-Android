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

internal data class SearchMatch(val row: Int, val col: Int, val len: Int)

internal class TerminalCanvasDrawState {
    var runBuf: CharArray = CharArray(256)
    val teardropPath: Path = Path()
    val searchSb: StringBuilder = StringBuilder(256)
    var searchColMap: IntArray = IntArray(256)

    private var lastPaint: Paint? = null
    private var lastBoldPaint: Paint? = null
    private var cachedColor: Int = 0
    private var cachedBoldColor: Int = 0
    private var cachedCellW: Float = -1f
    private val unicodeBatchSafeCache = HashMap<Int, Boolean>(64)

    fun ensureRunBuf(minLen: Int): CharArray {
        if (runBuf.size < minLen) {
            runBuf = CharArray(maxOf(minLen, runBuf.size * 2))
        }
        return runBuf
    }

    fun ensureSearchColMap(minLen: Int): IntArray {
        if (searchColMap.size < minLen) {
            searchColMap = IntArray(maxOf(minLen, searchColMap.size * 2))
        }
        return searchColMap
    }

    fun beginFrame(paint: Paint, boldPaint: Paint, cellW: Float) {
        if (paint !== lastPaint) {
            lastPaint = paint
            cachedColor = Int.MIN_VALUE
        }
        if (boldPaint !== lastBoldPaint) {
            lastBoldPaint = boldPaint
            cachedBoldColor = Int.MIN_VALUE
        }
        if (cachedCellW != cellW) {
            cachedCellW = cellW
            unicodeBatchSafeCache.clear()
        }
    }

    fun applyTextColor(paint: Paint, boldPaint: Paint, isBold: Boolean, fg: Int, dim: Boolean): Paint {
        val effective = if (dim) (fg and 0x00FFFFFF) or (150 shl 24) else fg
        return if (isBold) {
            if (cachedBoldColor != effective) {
                boldPaint.color = effective
                cachedBoldColor = effective
            }
            boldPaint
        } else {
            if (cachedColor != effective) {
                paint.color = effective
                cachedColor = effective
            }
            paint
        }
    }

    fun isBatchSafeSingleWidth(ch: Char, isBold: Boolean, p: Paint, cellW: Float): Boolean {
        if (ch.code <= 0x7E) return true
        val key = (ch.code shl 1) or (if (isBold) 1 else 0)
        unicodeBatchSafeCache[key]?.let { return it }
        runBuf[0] = ch
        val safe = abs(p.measureText(runBuf, 0, 1) - cellW) <= cellW * 0.08f
        if (unicodeBatchSafeCache.size >= 256) unicodeBatchSafeCache.clear()
        unicodeBatchSafeCache[key] = safe
        return safe
    }
}

internal fun computeSearchMatches(
    snapshot: TerminalSnapshot,
    qLower: String,
    drawState: TerminalCanvasDrawState
): List<SearchMatch> {
    if (qLower.isEmpty()) return emptyList()
    val matches = ArrayList<SearchMatch>()
    val qLen = qLower.length
    val sb = drawState.searchSb
    for (r in snapshot.lines.indices) {
        val row = snapshot.lines[r]
        if (row.isEmpty()) continue
        sb.setLength(0)
        val colMap = drawState.ensureSearchColMap(row.size)
        var mappedLen = 0
        for (c in row.indices) {
            val cell = row[c]
            if (cell.isContinuation) continue
            sb.append(cell.ch.lowercaseChar())
            colMap[mappedLen++] = c
        }
        if (mappedLen < qLen) continue
        val rowLow = sb.toString()
        var idx = rowLow.indexOf(qLower)
        while (idx >= 0) {
            val startCol = colMap[idx]
            val endIdx = (idx + qLen - 1).coerceAtMost(mappedLen - 1)
            val endCellCol = colMap[endIdx]
            val endCol = endCellCol + if (row[endCellCol].isWide) 2 else 1
            matches.add(SearchMatch(r, startCol, (endCol - startCol).coerceAtLeast(1)))
            idx = rowLow.indexOf(qLower, idx + qLen)
        }
    }
    return matches
}

internal fun DrawScope.drawTerminalSnapshot(
    snapshot: TerminalSnapshot,
    paint: Paint,
    boldPaint: Paint,
    cellW: Float,
    cellH: Float,
    baselineOffset: Float,
    scrollOffset: Int,
    searchMatches: List<SearchMatch>,
    drawState: TerminalCanvasDrawState,
    tapIndicatorCell: Pair<Int, Int>? = null,
    selection: TextSelection? = null
) {
    val px = TERM_PAD_X
    val py = TERM_PAD_Y
    val qBg = AgWarning.copy(alpha = 0.35f)
    drawState.beginFrame(paint, boldPaint, cellW)
    clipRect {
        val nc = drawContext.canvas.nativeCanvas
        for (r in snapshot.lines.indices) {
            val top = py + r * cellH
            if (top >= size.height) break
            val row = snapshot.lines[r]
            val base = top + baselineOffset
            val rowSize = row.size
            val buf = drawState.ensureRunBuf(rowSize)

            // Pass 1: Coalesced background rects
            var bgActive = false
            var bgColor = 0
            var bgStartCol = 0
            var bgEndCol = 0
            for (c in 0 until rowSize) {
                val left = px + c * cellW
                if (left >= size.width) break
                val cell = row[c]
                if (cell.isContinuation) continue
                val span = if (cell.isWide) 2 else 1
                val bg = if (cell.inverse) cell.fg else cell.bg
                if (bg != TerminalColors.DEFAULT_BG) {
                    if (bgActive && bg == bgColor && c == bgEndCol) {
                        bgEndCol = c + span
                    } else {
                        if (bgActive) {
                            drawRect(
                                Color(bgColor),
                                Offset(px + bgStartCol * cellW, top),
                                Size((bgEndCol - bgStartCol) * cellW + 0.6f, cellH + 0.6f)
                            )
                        }
                        bgActive = true
                        bgColor = bg
                        bgStartCol = c
                        bgEndCol = c + span
                    }
                } else if (bgActive) {
                    drawRect(
                        Color(bgColor),
                        Offset(px + bgStartCol * cellW, top),
                        Size((bgEndCol - bgStartCol) * cellW + 0.6f, cellH + 0.6f)
                    )
                    bgActive = false
                }
            }
            if (bgActive) {
                drawRect(
                    Color(bgColor),
                    Offset(px + bgStartCol * cellW, top),
                    Size((bgEndCol - bgStartCol) * cellW + 0.6f, cellH + 0.6f)
                )
            }

            // Pass 2: Coalesced text runs (0 String allocations per frame)
            var runLen = 0
            var runStartCol = 0
            var runFg = 0
            var runBold = false
            var runDim = false

            fun flushTextRun() {
                if (runLen <= 0) return
                val p = drawState.applyTextColor(paint, boldPaint, runBold, runFg, runDim)
                nc.drawText(buf, 0, runLen, px + runStartCol * cellW, base, p)
                runLen = 0
            }

            for (c in 0 until rowSize) {
                val left = px + c * cellW
                if (left >= size.width) break
                val cell = row[c]
                if (cell.isContinuation) continue
                val ch = cell.ch
                if (ch == ' ' || ch == '\u0000') {
                    flushTextRun()
                    continue
                }
                val fg = if (cell.inverse) cell.bg else cell.fg
                val targetPaint = if (cell.bold) boldPaint else paint
                val batchSafe = !cell.isWide && (ch.code <= 0x7E || drawState.isBatchSafeSingleWidth(ch, cell.bold, targetPaint, cellW))
                if (!batchSafe) {
                    flushTextRun()
                    val p = drawState.applyTextColor(paint, boldPaint, cell.bold, fg, cell.dim)
                    buf[0] = ch
                    nc.drawText(buf, 0, 1, left, base, p)
                } else if (
                    runLen > 0 &&
                    c == runStartCol + runLen &&
                    fg == runFg &&
                    cell.bold == runBold &&
                    cell.dim == runDim
                ) {
                    buf[runLen++] = ch
                } else {
                    flushTextRun()
                    runStartCol = c
                    runFg = fg
                    runBold = cell.bold
                    runDim = cell.dim
                    buf[0] = ch
                    runLen = 1
                }
            }
            flushTextRun()

            // Pass 3: Coalesced underline segments
            var ulActive = false
            var ulColor = 0
            var ulStartCol = 0
            var ulEndCol = 0
            val ulY = top + cellH - 2f
            for (c in 0 until rowSize) {
                val left = px + c * cellW
                if (left >= size.width) break
                val cell = row[c]
                if (cell.isContinuation) continue
                val span = if (cell.isWide) 2 else 1
                if (cell.underline) {
                    val fg = if (cell.inverse) cell.bg else cell.fg
                    if (ulActive && fg == ulColor && c == ulEndCol) {
                        ulEndCol = c + span
                    } else {
                        if (ulActive) {
                            drawLine(Color(ulColor), Offset(px + ulStartCol * cellW, ulY), Offset(px + ulEndCol * cellW, ulY), 1.5f)
                        }
                        ulActive = true
                        ulColor = fg
                        ulStartCol = c
                        ulEndCol = c + span
                    }
                } else if (ulActive) {
                    drawLine(Color(ulColor), Offset(px + ulStartCol * cellW, ulY), Offset(px + ulEndCol * cellW, ulY), 1.5f)
                    ulActive = false
                }
            }
            if (ulActive) {
                drawLine(Color(ulColor), Offset(px + ulStartCol * cellW, ulY), Offset(px + ulEndCol * cellW, ulY), 1.5f)
            }
        }

        if (searchMatches.isNotEmpty()) {
            for (m in searchMatches) {
                val top = py + m.row * cellH
                if (top >= size.height) break
                drawRect(qBg, Offset(px + m.col * cellW, top), Size(m.len * cellW, cellH))
            }
        }

        tapIndicatorCell?.let { (tr, tc) ->
            if (tr in 0 until snapshot.rows && tc in 0 until snapshot.cols) {
                val tl = Offset(px + tc * cellW - 2f, py + tr * cellH - 1f)
                val sz = Size(cellW + 4f, cellH + 2f)
                val cr = CornerRadius(4f, 4f)
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
        if (selection != null) drawSelectionOverlay(snapshot, selection, cellW, cellH, px, py, drawState.teardropPath)
    }
}

private fun DrawScope.drawSelectionOverlay(
    snap: TerminalSnapshot, sel: TextSelection, cellW: Float, cellH: Float, px: Float, py: Float, teardropPath: Path
) {
    if (snap.lines.isEmpty() || snap.cols <= 0) return
    val s = sel.start
    val e = sel.end
    val fill = AgCyan.copy(alpha = 0.30f)
    val cr = CornerRadius(3f, 3f)
    for (r in s.row.coerceAtLeast(0)..e.row.coerceAtMost(snap.lines.lastIndex)) {
        val top = py + r * cellH
        if (top >= size.height) break
        val range = sel.colRangeForRow(r, snap.cols) ?: continue
        val row = snap.lines[r]
        val endC = if (s.row == e.row || r == e.row) range.last else {
            var last = -1
            for (c in minOf(range.last, row.lastIndex) downTo range.first) {
                if (row[c].isContinuation || (row[c].ch != ' ' && row[c].ch != '\u0000')) { last = c; break }
            }
            if (last >= range.first) last else range.first
        }
        drawRoundRect(fill, Offset(px + range.first * cellW, top), Size(((endC - range.first + 1) * cellW).coerceAtLeast(cellW), cellH), cr)
    }
    val rad = 11f * (cellH / 34f).coerceIn(0.8f, 1.35f)
    if (s.row in snap.lines.indices) {
        drawTeardrop(px + s.col.coerceIn(0, snap.cols - 1) * cellW, py + s.row * cellH, py + (s.row + 1) * cellH, rad, true, teardropPath)
    }
    if (e.row in snap.lines.indices) {
        drawTeardrop(px + (e.col.coerceIn(0, snap.cols - 1) + 1) * cellW, py + e.row * cellH, py + (e.row + 1) * cellH, rad, false, teardropPath)
    }
}

private fun DrawScope.drawTeardrop(ax: Float, top: Float, ay: Float, r: Float, isStart: Boolean, path: Path) {
    if (top >= size.height) return
    drawLine(AgCyan, Offset(ax, top), Offset(ax, ay), strokeWidth = 2.5f)
    val bx = if (isStart) (ax - r * 0.55f).coerceAtLeast(r + 2f)
    else (ax + r * 0.55f).coerceAtMost((size.width - r - 2f).coerceAtLeast(r + 2f))
    val by = (ay + r * 1.15f).coerceAtMost((size.height - r - 2f).coerceAtLeast(r + 2f))
    val dx = ax - bx
    val dy = ay - by
    val d = hypot(dx, dy)
    val outline = Stroke(1.8f)
    if (d > r * 1.05f) {
        val beta = acos((r / d).coerceIn(-0.99f, 0.99f))
        val a1 = atan2(dy, dx) + beta
        val deg = (180.0 / PI).toFloat()
        path.rewind()
        path.moveTo(ax, ay)
        path.lineTo(bx + r * cos(a1), by + r * sin(a1))
        path.arcTo(Rect(bx - r, by - r, bx + r, by + r), a1 * deg, 360f - 2f * beta * deg, false)
        path.close()
        drawPath(path, AgCyan)
        drawPath(path, AgBg, style = outline)
    } else {
        drawCircle(AgCyan, r, Offset(bx, by))
        drawCircle(AgBg, r, Offset(bx, by), style = outline)
    }
}
