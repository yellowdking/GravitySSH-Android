package com.gravityssh.app.terminal

data class CellPos(val row: Int, val col: Int) : Comparable<CellPos> {
    override fun compareTo(other: CellPos): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

data class TextSelection(val anchor: CellPos, val focus: CellPos) {
    val start: CellPos get() = if (anchor <= focus) anchor else focus
    val end: CellPos get() = if (anchor <= focus) focus else anchor

    fun colRangeForRow(row: Int, totalCols: Int): IntRange? {
        if (totalCols <= 0) return null
        val s = start; val e = end
        if (row < s.row || row > e.row) return null
        val maxC = totalCols - 1
        return when {
            s.row == e.row -> s.col.coerceIn(0, maxC)..e.col.coerceIn(0, maxC)
            row == s.row -> s.col.coerceIn(0, maxC)..maxC
            row == e.row -> 0..e.col.coerceIn(0, maxC)
            else -> 0..maxC
        }
    }
}

enum class SelectionHandle { NONE, START, END }

object TerminalSelectionHelper {
    private const val WORD_EXTRA = "_-./:@~+=%?&#"
    private val ZERO = TextSelection(CellPos(0, 0), CellPos(0, 0))

    private fun isBlank(ch: Char) = ch == ' ' || ch == '\u0000'
    private fun isWord(ch: Char) = ch.isLetterOrDigit() || ch in WORD_EXTRA
    private fun chAt(line: Array<TerminalCell>, c: Int) = if (c in line.indices) line[c].ch else ' '
    private fun isContAt(line: Array<TerminalCell>, c: Int) = c in line.indices && line[c].isContinuation
    private fun isWideAt(line: Array<TerminalCell>, c: Int) = c in line.indices && line[c].isWide
    private fun snapToLead(line: Array<TerminalCell>, c: Int): Int =
        if (c > 0 && isContAt(line, c)) c - 1 else c

    private fun expand(line: Array<TerminalCell>, maxC: Int, r: Int, rawC: Int): TextSelection {
        val c = snapToLead(line, rawC)
        val m: (Char) -> Boolean = if (isWord(chAt(line, c))) ::isWord else { ch -> !isBlank(ch) && !isWord(ch) }
        var l = c
        while (l > 0) {
            val prev = snapToLead(line, l - 1)
            if (!m(chAt(line, prev))) break
            l = prev
        }
        var ri = c
        if (isWideAt(line, ri) && ri + 1 <= maxC && isContAt(line, ri + 1)) ri++
        while (ri < maxC) {
            val next = ri + 1
            if (isContAt(line, next)) {
                ri = next
                continue
            }
            if (!m(chAt(line, next))) break
            ri = next
            if (isWideAt(line, ri) && ri + 1 <= maxC && isContAt(line, ri + 1)) ri++
        }
        return TextSelection(CellPos(r, l), CellPos(r, ri))
    }

    fun selectWordAt(snapshot: TerminalSnapshot, row: Int, col: Int): TextSelection {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ZERO
        val r = row.coerceIn(snapshot.lines.indices); val maxC = snapshot.cols - 1
        val c = snapToLead(snapshot.lines[r], col.coerceIn(0, maxC)); val line = snapshot.lines[r]
        if (!isBlank(chAt(line, c))) return expand(line, maxC, r, c)
        for (d in 1..2) {
            if (c - d >= 0) {
                val lc = snapToLead(line, c - d)
                if (!isBlank(chAt(line, lc))) return expand(line, maxC, r, lc)
            }
            if (c + d <= maxC) {
                val rc = snapToLead(line, c + d)
                if (!isBlank(chAt(line, rc))) return expand(line, maxC, r, rc)
            }
        }
        return selectLine(snapshot, r)
    }

    fun selectLine(snapshot: TerminalSnapshot, row: Int): TextSelection {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ZERO
        val r = row.coerceIn(snapshot.lines.indices); val line = snapshot.lines[r]
        val limit = minOf(snapshot.cols, line.size)
        var first = -1; var last = -1
        for (c in 0 until limit) {
            if (line[c].isContinuation) continue
            if (!isBlank(line[c].ch)) {
                if (first < 0) first = c
                last = if (line[c].isWide && c + 1 < limit && line[c + 1].isContinuation) c + 1 else c
            }
        }
        return if (first < 0) TextSelection(CellPos(r, 0), CellPos(r, 0))
        else TextSelection(CellPos(r, first), CellPos(r, last))
    }

    fun selectAllVisible(snapshot: TerminalSnapshot): TextSelection {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ZERO
        var first: CellPos? = null; var last: CellPos? = null
        for (r in snapshot.lines.indices) {
            val line = snapshot.lines[r]
            val limit = minOf(snapshot.cols, line.size)
            for (c in 0 until limit) {
                if (line[c].isContinuation) continue
                if (!isBlank(line[c].ch)) {
                    val pStart = CellPos(r, c)
                    val endCol = if (line[c].isWide && c + 1 < limit && line[c + 1].isContinuation) c + 1 else c
                    if (first == null) first = pStart
                    last = CellPos(r, endCol)
                }
            }
        }
        return if (first == null || last == null) ZERO else TextSelection(first, last)
    }

    fun extractText(snapshot: TerminalSnapshot, selection: TextSelection): String {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ""
        val s = selection.start; val e = selection.end
        if (e.row < 0 || s.row > snapshot.lines.lastIndex) return ""
        val out = StringBuilder(); val buf = StringBuilder(); var first = true
        for (r in s.row.coerceAtLeast(0)..e.row.coerceAtMost(snapshot.lines.lastIndex)) {
            val range = selection.colRangeForRow(r, snapshot.cols) ?: continue
            val line = snapshot.lines[r]; buf.setLength(0)
            for (c in range) {
                if (c in line.indices && line[c].isContinuation) continue
                val ch = chAt(line, c)
                buf.append(if (ch == '\u0000') ' ' else ch)
            }
            var end = buf.length; while (end > 0 && buf[end - 1] == ' ') end--
            if (!first) out.append('\n'); first = false
            out.append(buf, 0, end)
        }
        return out.toString()
    }

    fun hitTestHandle(
        x: Float, y: Float, selection: TextSelection,
        cellW: Float, cellH: Float, padX: Float, padY: Float, radiusPx: Float
    ): SelectionHandle {
        if (radiusPx <= 0f) return SelectionHandle.NONE
        val s = selection.start; val e = selection.end
        val sx = padX + s.col * cellW - radiusPx * 0.25f
        val sy = padY + (s.row + 1) * cellH + radiusPx * 0.45f
        val ex = padX + (e.col + 1) * cellW + radiusPx * 0.25f
        val ey = padY + (e.row + 1) * cellH + radiusPx * 0.45f
        val rSq = radiusPx * radiusPx
        val dS = (x - sx) * (x - sx) + (y - sy) * (y - sy)
        val dE = (x - ex) * (x - ex) + (y - ey) * (y - ey)
        val hitS = dS <= rSq; val hitE = dE <= rSq
        return when {
            hitS && hitE -> if (dS <= dE) SelectionHandle.START else SelectionHandle.END
            hitS -> SelectionHandle.START
            hitE -> SelectionHandle.END
            else -> SelectionHandle.NONE
        }
    }
}
