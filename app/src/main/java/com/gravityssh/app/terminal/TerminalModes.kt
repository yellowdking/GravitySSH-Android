package com.gravityssh.app.terminal

import com.gravityssh.app.terminal.TerminalBufferEdit as E

internal object TerminalModes {
    private const val MOUSE_NORMAL_BIT = 1
    private const val MOUSE_BUTTON_BIT = 2
    private const val MOUSE_ANY_BIT = 4

    fun updateMouseMask(currentMask: Int, modeCode: Int, enable: Boolean): Int {
        val bit = when (modeCode) {
            1000 -> MOUSE_NORMAL_BIT
            1002 -> MOUSE_BUTTON_BIT
            1003 -> MOUSE_ANY_BIT
            else -> return currentMask
        }
        return if (enable) currentMask or bit else currentMask and bit.inv()
    }

    fun applyPrivateMode(buffer: TerminalBuffer, code: Int, enable: Boolean) {
        when (code) {
            1 -> buffer.applicationCursorKeys = enable
            25 -> buffer.cursorVisible = enable
            47, 1047 -> if (enable) buffer.enterAlternateScreen(false) else buffer.exitAlternateScreen(false)
            1048 -> if (enable) buffer.saveCursor() else buffer.restoreCursor()
            1049 -> if (enable) buffer.enterAlternateScreen(true) else buffer.exitAlternateScreen(true)
            1000, 1002, 1003 -> buffer.setMouseTrackingMode(code, enable)
            1006 -> buffer.sgrMouseMode = enable
            2004 -> buffer.bracketedPasteMode = enable
        }
    }

    fun arrowSequence(dir: CursorDir, applicationCursorKeys: Boolean): String {
        val prefix = if (applicationCursorKeys) "\u001BO" else "\u001B["
        return when (dir) {
            CursorDir.UP -> "${prefix}A"
            CursorDir.DOWN -> "${prefix}B"
            CursorDir.RIGHT -> "${prefix}C"
            CursorDir.LEFT -> "${prefix}D"
        }
    }

    fun sgrMouseTapSequence(col0Based: Int, row0Based: Int, cols: Int, rows: Int): String {
        val cx = col0Based.coerceIn(0, (cols - 1).coerceAtLeast(0)) + 1
        val cy = row0Based.coerceIn(0, (rows - 1).coerceAtLeast(0)) + 1
        return "\u001B[<0;$cx;${cy}M\u001B[<0;$cx;${cy}m"
    }

    fun detectNanoHint(grid: Array<Array<TerminalCell>>, rows: Int): Boolean {
        if (rows <= 0 || grid.isEmpty()) return false
        val last = (rows - 1).coerceAtMost(grid.lastIndex)
        if (rowMatchesNano(grid[0])) return true
        if (last > 0 && rowMatchesNano(grid[last])) return true
        if (last > 1 && rowMatchesNano(grid[last - 1])) return true
        return false
    }

    fun containsAltScreenEnter(rawText: String): Boolean =
        rawText.contains("\u001B[?1049h") || rawText.contains("\u001B[?1047h") || rawText.contains("\u001B[?47h")

    private fun rowMatchesNano(row: Array<TerminalCell>): Boolean =
        rowContainsAscii(row, "GNU nano", ignoreCase = true) ||
            rowContainsAscii(row, "^X", ignoreCase = false) ||
            rowContainsAscii(row, "^O", ignoreCase = false)

    private fun rowContainsAscii(row: Array<TerminalCell>, needle: String, ignoreCase: Boolean): Boolean {
        val nLen = needle.length
        if (nLen == 0 || row.size < nLen) return false
        val maxStart = row.size - nLen
        outer@ for (i in 0..maxStart) {
            for (j in 0 until nLen) {
                val a = row[i + j].ch
                val b = needle[j]
                if (a != b && (!ignoreCase || a.lowercaseChar() != b.lowercaseChar())) continue@outer
            }
            return true
        }
        return false
    }
}

fun TerminalBuffer.scrollUp(count: Int) {
    E.scrollUp(activeGrid(), scrollback, topMargin, bottomMargin, cols, rows, isAltScreen, maxScrollback, style, count)
    markAltNanoDirty()
}

fun TerminalBuffer.scrollDown(count: Int) {
    E.scrollDown(activeGrid(), topMargin, bottomMargin, cols, style, count)
    markAltNanoDirty()
}

fun TerminalBuffer.eraseDisplay(mode: Int) {
    clearWrapPending()
    E.eraseDisplay(activeGrid(), cursorRow, cursorCol, cols, rows, style, mode) { if (!isAltScreen) scrollback.clear() }
    markAltNanoDirty()
}

fun TerminalBuffer.eraseLine(mode: Int) {
    clearWrapPending()
    E.eraseLine(currentRowCells(), cursorCol, cols, style, mode)
    markAltRowDirty(cursorRow)
}

fun TerminalBuffer.eraseChars(count: Int) {
    clearWrapPending()
    E.eraseChars(currentRowCells(), cursorCol, cols, style, count)
    markAltRowDirty(cursorRow)
}

fun TerminalBuffer.deleteChars(count: Int) {
    clearWrapPending()
    E.deleteChars(currentRowCells(), cursorCol, cols, style, count)
    markAltRowDirty(cursorRow)
}

fun TerminalBuffer.insertChars(count: Int) {
    clearWrapPending()
    E.insertChars(currentRowCells(), cursorCol, cols, style, count)
    markAltRowDirty(cursorRow)
}

fun TerminalBuffer.insertLines(count: Int) {
    clearWrapPending()
    if (cursorRow in topMargin..bottomMargin) {
        carriageReturn()
        E.scrollDown(activeGrid(), cursorRow, bottomMargin, cols, style, count)
        markAltNanoDirty()
    }
}

fun TerminalBuffer.deleteLines(count: Int) {
    clearWrapPending()
    if (cursorRow in topMargin..bottomMargin) {
        carriageReturn()
        E.scrollUp(activeGrid(), scrollback, cursorRow, bottomMargin, cols, rows, true, maxScrollback, style, count)
        markAltNanoDirty()
    }
}
