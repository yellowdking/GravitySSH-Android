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

    private fun expand(line: Array<TerminalCell>, maxC: Int, r: Int, c: Int): TextSelection {
        val m: (Char) -> Boolean = if (isWord(chAt(line, c))) ::isWord else { ch -> !isBlank(ch) && !isWord(ch) }
        var l = c; while (l > 0 && m(chAt(line, l - 1))) l--
        var ri = c; while (ri < maxC && m(chAt(line, ri + 1))) ri++
        return TextSelection(CellPos(r, l), CellPos(r, ri))
    }

    fun selectWordAt(snapshot: TerminalSnapshot, row: Int, col: Int): TextSelection {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ZERO
        val r = row.coerceIn(snapshot.lines.indices); val maxC = snapshot.cols - 1
        val c = col.coerceIn(0, maxC); val line = snapshot.lines[r]
        if (!isBlank(chAt(line, c))) return expand(line, maxC, r, c)
        for (d in 1..2) {
            if (c - d >= 0 && !isBlank(chAt(line, c - d))) return expand(line, maxC, r, c - d)
            if (c + d <= maxC && !isBlank(chAt(line, c + d))) return expand(line, maxC, r, c + d)
        }
        return selectLine(snapshot, r)
    }

    fun selectLine(snapshot: TerminalSnapshot, row: Int): TextSelection {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ZERO
        val r = row.coerceIn(snapshot.lines.indices); val line = snapshot.lines[r]
        var first = -1; var last = -1
        for (c in 0 until minOf(snapshot.cols, line.size)) if (!isBlank(line[c].ch)) {
            if (first < 0) first = c; last = c
        }
        return if (first < 0) TextSelection(CellPos(r, 0), CellPos(r, 0))
        else TextSelection(CellPos(r, first), CellPos(r, last))
    }

    fun selectAllVisible(snapshot: TerminalSnapshot): TextSelection {
        if (snapshot.lines.isEmpty() || snapshot.cols <= 0) return ZERO
        var first: CellPos? = null; var last: CellPos? = null
        for (r in snapshot.lines.indices) {
            val line = snapshot.lines[r]
            for (c in 0 until minOf(snapshot.cols, line.size)) if (!isBlank(line[c].ch)) {
                val p = CellPos(r, c); if (first == null) first = p; last = p
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
            for (c in range) { val ch = chAt(line, c); buf.append(if (ch == '\u0000') ' ' else ch) }
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
