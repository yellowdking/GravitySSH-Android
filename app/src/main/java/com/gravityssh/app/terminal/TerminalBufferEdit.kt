package com.gravityssh.app.terminal

internal object TerminalBufferEdit {
    fun clearWideAt(row: Array<TerminalCell>, c: Int, cols: Int, s: TerminalStyle) {
        if (c !in 0 until cols || c !in row.indices) return
        val existing = row[c]
        if (existing.isWide && c + 1 < cols && c + 1 in row.indices && row[c + 1].isContinuation) {
            row[c + 1] = TerminalCell.blank(s)
        } else if (existing.isContinuation && c > 0 && row[c - 1].isWide) {
            row[c - 1] = TerminalCell.blank(s)
        }
    }

    fun writeCell(grid: Array<Array<TerminalCell>>, r: Int, c: Int, rows: Int, cols: Int, ch: Char, s: TerminalStyle) {
        if (r in 0 until rows && c in 0 until cols) {
            val row = grid[r]
            clearWideAt(row, c, cols, s)
            row[c] = TerminalCell(ch, s.fg, s.bg, s.bold, s.dim, s.underline, s.inverse)
        }
    }

    fun writeWideCell(grid: Array<Array<TerminalCell>>, r: Int, c: Int, rows: Int, cols: Int, ch: Char, s: TerminalStyle) {
        if (r in 0 until rows && c in 0 until cols - 1) {
            val row = grid[r]
            clearWideAt(row, c, cols, s)
            clearWideAt(row, c + 1, cols, s)
            row[c] = TerminalCell(ch, s.fg, s.bg, s.bold, s.dim, s.underline, s.inverse, isWide = true)
            row[c + 1] = TerminalCell.continuation(s)
        }
    }

    fun scrollUp(
        grid: Array<Array<TerminalCell>>, scrollback: ArrayDeque<Array<TerminalCell>>,
        topMargin: Int, bottomMargin: Int, cols: Int, rows: Int,
        isAltScreen: Boolean, maxScrollback: Int, style: TerminalStyle, count: Int
    ) {
        repeat(count.coerceIn(1, bottomMargin - topMargin + 1)) {
            if (!isAltScreen && topMargin == 0 && bottomMargin == rows - 1) {
                scrollback.addLast(grid[0])
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (r in topMargin until bottomMargin) grid[r] = grid[r + 1]
            grid[bottomMargin] = blankRow(cols, style)
        }
    }

    fun scrollDown(
        grid: Array<Array<TerminalCell>>, topMargin: Int, bottomMargin: Int,
        cols: Int, style: TerminalStyle, count: Int
    ) {
        repeat(count.coerceIn(1, bottomMargin - topMargin + 1)) {
            for (r in bottomMargin downTo topMargin + 1) grid[r] = grid[r - 1]
            grid[topMargin] = blankRow(cols, style)
        }
    }

    fun eraseDisplay(
        grid: Array<Array<TerminalCell>>, cursorRow: Int, cursorCol: Int, cols: Int, rows: Int,
        style: TerminalStyle, mode: Int, clearScrollback: () -> Unit
    ) {
        when (mode) {
            0 -> { eraseLine(grid[cursorRow], cursorCol, cols, style, 0); for (r in cursorRow + 1 until rows) grid[r] = blankRow(cols, style) }
            1 -> { for (r in 0 until cursorRow) grid[r] = blankRow(cols, style); eraseLine(grid[cursorRow], cursorCol, cols, style, 1) }
            2, 3 -> { for (r in 0 until rows) grid[r] = blankRow(cols, style); if (mode == 3) clearScrollback() }
        }
    }

    fun eraseLine(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, mode: Int) {
        val blank = TerminalCell.blank(style)
        when (mode) {
            0 -> {
                if (cursorCol in 0 until cols) clearWideAt(row, cursorCol, cols, style)
                for (c in cursorCol until cols) row[c] = blank
            }
            1 -> {
                val end = cursorCol.coerceAtMost(cols - 1)
                if (end in 0 until cols) clearWideAt(row, end, cols, style)
                for (c in 0..end) row[c] = blank
            }
            2 -> for (c in 0 until cols) row[c] = blank
        }
    }

    fun eraseChars(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, count: Int) {
        val end = (cursorCol + count.coerceAtLeast(1)).coerceAtMost(cols)
        if (cursorCol in 0 until cols) clearWideAt(row, cursorCol, cols, style)
        if (end - 1 in 0 until cols) clearWideAt(row, end - 1, cols, style)
        val blank = TerminalCell.blank(style)
        for (c in cursorCol until end) row[c] = blank
    }

    fun deleteChars(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        clearWideAt(row, cursorCol, cols, style)
        for (c in cursorCol until cols - n) row[c] = row[c + n]
        val blank = TerminalCell.blank(style)
        for (c in cols - n until cols) row[c] = blank
        if (cursorCol in 0 until cols && row[cursorCol].isContinuation) row[cursorCol] = blank
    }

    fun insertChars(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        clearWideAt(row, cursorCol, cols, style)
        for (c in cols - 1 downTo cursorCol + n) row[c] = row[c - n]
        val blank = TerminalCell.blank(style)
        for (c in cursorCol until cursorCol + n) row[c] = blank
        if (cols > 0 && row[cols - 1].isWide) row[cols - 1] = blank
    }

    fun buildSnapshot(
        cols: Int, rows: Int, cursorCol: Int, cursorRow: Int, cursorVisible: Boolean,
        grid: Array<Array<TerminalCell>>, scrollback: ArrayDeque<Array<TerminalCell>>,
        isAltScreen: Boolean, bracketedPaste: Boolean, version: Long, scrollOffset: Int
    ): TerminalSnapshot {
        val clamped = if (isAltScreen) 0 else scrollOffset.coerceIn(0, scrollback.size)
        val result = ArrayList<Array<TerminalCell>>(rows)
        if (clamped == 0) {
            for (r in 0 until rows) result.add(grid[r].copyOf())
        } else {
            val sbStart = (scrollback.size - clamped).coerceAtLeast(0)
            for (i in 0 until rows) {
                val idx = sbStart + i
                result.add(
                    if (idx < scrollback.size) resizeRow(scrollback[idx], cols)
                    else grid.getOrNull(idx - scrollback.size)?.copyOf() ?: blankRow(cols)
                )
            }
        }
        return TerminalSnapshot(
            cols, rows, cursorCol, cursorRow, cursorVisible && clamped == 0, result,
            if (isAltScreen) 0 else scrollback.size, clamped, isAltScreen, bracketedPaste, version
        )
    }

    fun extractText(snap: TerminalSnapshot): String =
        snap.lines.joinToString("\n") { row ->
            val sb = StringBuilder(row.size)
            for (cell in row) {
                if (!cell.isContinuation) sb.append(if (cell.ch == '\u0000') ' ' else cell.ch)
            }
            var end = sb.length
            while (end > 0 && sb[end - 1] == ' ') end--
            sb.substring(0, end)
        }.trimEnd()

    fun makeGrid(r: Int, c: Int): Array<Array<TerminalCell>> = Array(r) { blankRow(c) }
    fun blankRow(c: Int, style: TerminalStyle = TerminalStyle()): Array<TerminalCell> {
        val blank = TerminalCell.blank(style)
        return Array(c) { blank }
    }

    fun resizeGrid(old: Array<Array<TerminalCell>>, newR: Int, newC: Int): Array<Array<TerminalCell>> {
        val next = makeGrid(newR, newC)
        for (r in 0 until minOf(old.size, newR)) next[r] = resizeRow(old[r], newC)
        return next
    }

    fun resizeRow(old: Array<TerminalCell>, newC: Int): Array<TerminalCell> {
        val next = blankRow(newC)
        val limit = minOf(old.size, newC)
        for (c in 0 until limit) next[c] = old[c]
        if (newC > 0 && next[newC - 1].isWide) next[newC - 1] = TerminalCell.EMPTY
        return next
    }
}
