package com.gravityssh.app.terminal

internal object TerminalBufferEdit {
    fun scrollUp(
        grid: Array<Array<TerminalCell>>, scrollback: ArrayDeque<Array<TerminalCell>>,
        topMargin: Int, bottomMargin: Int, cols: Int, rows: Int,
        isAltScreen: Boolean, maxScrollback: Int, count: Int
    ) {
        repeat(count.coerceIn(1, bottomMargin - topMargin + 1)) {
            if (!isAltScreen && topMargin == 0 && bottomMargin == rows - 1) {
                scrollback.addLast(grid[0].copyOf())
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (r in topMargin until bottomMargin) grid[r] = grid[r + 1]
            grid[bottomMargin] = blankRow(cols)
        }
    }

    fun scrollDown(grid: Array<Array<TerminalCell>>, topMargin: Int, bottomMargin: Int, cols: Int, count: Int) {
        repeat(count.coerceIn(1, bottomMargin - topMargin + 1)) {
            for (r in bottomMargin downTo topMargin + 1) grid[r] = grid[r - 1]
            grid[topMargin] = blankRow(cols)
        }
    }

    fun eraseDisplay(grid: Array<Array<TerminalCell>>, cursorRow: Int, cursorCol: Int, cols: Int, rows: Int, style: TerminalStyle, mode: Int, clearScrollback: () -> Unit) {
        when (mode) {
            0 -> { eraseLine(grid[cursorRow], cursorCol, cols, style, 0); for (r in cursorRow + 1 until rows) grid[r] = blankRow(cols) }
            1 -> { for (r in 0 until cursorRow) grid[r] = blankRow(cols); eraseLine(grid[cursorRow], cursorCol, cols, style, 1) }
            2, 3 -> { for (r in 0 until rows) grid[r] = blankRow(cols); if (mode == 3) clearScrollback() }
        }
    }

    fun eraseLine(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, mode: Int) {
        val blank = TerminalCell.blank(style)
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c] = blank
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) row[c] = blank
            2 -> for (c in 0 until cols) row[c] = blank
        }
    }

    fun eraseChars(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, count: Int) {
        val blank = TerminalCell.blank(style)
        for (c in cursorCol until (cursorCol + count.coerceAtLeast(1)).coerceAtMost(cols)) row[c] = blank
    }

    fun deleteChars(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cursorCol until cols - n) row[c] = row[c + n]
        val blank = TerminalCell.blank(style)
        for (c in cols - n until cols) row[c] = blank
    }

    fun insertChars(row: Array<TerminalCell>, cursorCol: Int, cols: Int, style: TerminalStyle, count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) row[c] = row[c - n]
        val blank = TerminalCell.blank(style)
        for (c in cursorCol until cursorCol + n) row[c] = blank
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
        snap.lines.joinToString("\n") { row -> String(CharArray(row.size) { row[it].ch }).trimEnd() }.trimEnd()

    fun makeGrid(r: Int, c: Int): Array<Array<TerminalCell>> = Array(r) { blankRow(c) }
    fun blankRow(c: Int): Array<TerminalCell> = Array(c) { TerminalCell.EMPTY }

    fun resizeGrid(old: Array<Array<TerminalCell>>, newR: Int, newC: Int): Array<Array<TerminalCell>> {
        val next = makeGrid(newR, newC)
        for (r in 0 until minOf(old.size, newR)) next[r] = resizeRow(old[r], newC)
        return next
    }

    fun resizeRow(old: Array<TerminalCell>, newC: Int): Array<TerminalCell> {
        val next = blankRow(newC)
        for (c in 0 until minOf(old.size, newC)) next[c] = old[c]
        return next
    }
}
