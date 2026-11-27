package com.gravityssh.app.terminal

import com.gravityssh.app.terminal.TerminalBufferEdit as E
import com.gravityssh.app.terminal.TerminalModes as M

class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    internal val maxScrollback: Int = 3000
) {
    private val lock = Any()
    var cols: Int = initialCols.coerceIn(10, 400); private set
    var rows: Int = initialRows.coerceIn(5, 200); private set

    private var mainGrid = E.makeGrid(rows, cols)
    private var altGrid = E.makeGrid(rows, cols)
    internal val scrollback = ArrayDeque<Array<TerminalCell>>()

    @Volatile var isAltScreen: Boolean = false; private set
    @Volatile var applicationCursorKeys: Boolean = false
    @Volatile private var mouseMask: Int = 0
    val mouseTrackingEnabled: Boolean get() = mouseMask != 0
    @Volatile var sgrMouseMode: Boolean = false
    @Volatile var bracketedPaste: Boolean = false
    @Volatile var cursorVisible: Boolean = true

    var cursorCol: Int = 0; private set
    var cursorRow: Int = 0; private set
    var style: TerminalStyle = TerminalStyle()

    private var mainSavedCol = 0; private var mainSavedRow = 0; private var mainSavedStyle = TerminalStyle()
    private var altSavedCol = 0; private var altSavedRow = 0; private var altSavedStyle = TerminalStyle()
    private var mainCol = 0; private var mainRow = 0; private var mainStyle = TerminalStyle()
    internal var topMargin = 0; internal var bottomMargin = rows - 1
    private var wrapPending = false; private var version: Long = 0L
    private var altNanoDirty = false; private var cachedNanoHint = false
    private var lastWriteRow = -1; private var lastWriteCol = -1

    internal fun activeGrid() = if (isAltScreen) altGrid else mainGrid
    internal fun currentRowCells() = activeGrid()[cursorRow]
    internal fun clearWrapPending() { wrapPending = false; clearLastWrite() }
    internal fun clearLastWrite() { lastWriteRow = -1; lastWriteCol = -1 }
    internal fun markAltNanoDirty() { if (isAltScreen) altNanoDirty = true }
    internal fun markAltRowDirty(row: Int) {
        if (isAltScreen && (row == 0 || row >= rows - 2)) altNanoDirty = true
    }
    private fun resetMargins() { topMargin = 0; bottomMargin = rows - 1; wrapPending = false; clearLastWrite() }
    private fun clampCursor() { cursorCol = cursorCol.coerceIn(0, cols - 1); cursorRow = cursorRow.coerceIn(0, rows - 1) }

    fun <T> synchronizedBuffer(block: () -> T): T = synchronized(lock) { block() }
    fun touch() { version++ }

    fun putChar(ch: Char) = putCodePoint(ch.code)

    fun putCodePoint(cp: Int) {
        val w = codePointWidth(cp)
        if (w == 0) {
            attachCombiningToPrevious(cp)
            return
        }
        // Attach ZWJ-joined emoji or emoji skin-tone modifiers onto the previous grapheme cell
        if (shouldAttachToPreviousCluster(cp)) {
            attachCombiningToPrevious(cp)
            return
        }
        if (wrapPending) { cursorCol = 0; lineFeed(); wrapPending = false }
        if (w == 2 && cols >= 2) {
            if (cursorCol >= cols - 1) {
                val row = activeGrid()[cursorRow]
                E.clearWideAt(row, cursorCol, cols, style)
                row[cursorCol] = TerminalCell.blank(style)
                markAltRowDirty(cursorRow)
                cursorCol = 0
                lineFeed()
            }
            val writtenRow = cursorRow
            val writtenCol = cursorCol
            E.writeWideCell(activeGrid(), writtenRow, writtenCol, rows, cols, cp, style)
            lastWriteRow = writtenRow
            lastWriteCol = writtenCol
            markAltRowDirty(writtenRow)
            if (cursorCol + 2 >= cols) {
                cursorCol = cols - 1
                wrapPending = true
            } else {
                cursorCol += 2
            }
        } else {
            val writtenRow = cursorRow
            val writtenCol = cursorCol
            E.writeCell(activeGrid(), writtenRow, writtenCol, rows, cols, cp, style)
            lastWriteRow = writtenRow
            lastWriteCol = writtenCol
            markAltRowDirty(writtenRow)
            if (cursorCol >= cols - 1) wrapPending = true else cursorCol++
        }
    }

    private fun shouldAttachToPreviousCluster(cp: Int): Boolean {
        if (lastWriteRow !in 0 until rows || lastWriteCol !in 0 until cols) return false
        val cell = activeGrid()[lastWriteRow][lastWriteCol]
        if (cell.isContinuation || cell.codePoint == 0) return false
        if (cell.combining?.endsWith("\u200D") == true && cp >= 0x2000) return true
        if (isEmojiSkinToneModifier(cp) && cell.codePoint >= 0x2000) return true
        return false
    }

    private fun attachCombiningToPrevious(cp: Int) {
        if (lastWriteRow !in 0 until rows || lastWriteCol !in 0 until cols) return
        val row = activeGrid()[lastWriteRow]
        val cell = row[lastWriteCol]
        if (cell.isContinuation || cell.codePoint == 0) return
        val existing = cell.combining.orEmpty()
        if (existing.length >= MAX_COMBINING_CHARS) return
        val sb = StringBuilder(existing.length + 2)
        sb.append(existing)
        sb.appendCodePoint(cp)
        row[lastWriteCol] = cell.copy(combining = sb.toString())
        markAltRowDirty(lastWriteRow)
    }

    fun carriageReturn() { cursorCol = 0; wrapPending = false; clearLastWrite() }
    fun backspace() { wrapPending = false; clearLastWrite(); if (cursorCol > 0) cursorCol-- }
    fun tab() { wrapPending = false; clearLastWrite(); cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1) }
    fun lineFeed() { wrapPending = false; clearLastWrite(); if (cursorRow == bottomMargin) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++ }
    fun reverseIndex() { wrapPending = false; clearLastWrite(); if (cursorRow == topMargin) scrollDown(1) else if (cursorRow > 0) cursorRow-- }
    fun moveCursorTo(row: Int, col: Int) { wrapPending = false; clearLastWrite(); cursorRow = row.coerceIn(0, rows - 1); cursorCol = col.coerceIn(0, cols - 1) }
    fun moveCursorBy(dRow: Int, dCol: Int) = moveCursorTo(cursorRow + dRow, cursorCol + dCol)

    fun saveCursor() {
        if (isAltScreen) {
            altSavedCol = cursorCol; altSavedRow = cursorRow; altSavedStyle = style
        } else {
            mainSavedCol = cursorCol; mainSavedRow = cursorRow; mainSavedStyle = style
        }
    }

    fun restoreCursor() {
        if (isAltScreen) {
            cursorCol = altSavedCol; cursorRow = altSavedRow; style = altSavedStyle
        } else {
            cursorCol = mainSavedCol; cursorRow = mainSavedRow; style = mainSavedStyle
        }
        clampCursor(); wrapPending = false; clearLastWrite()
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1); val b = bottom.coerceIn(0, rows - 1)
        topMargin = if (t < b) t else 0; bottomMargin = if (t < b) b else rows - 1
        moveCursorTo(0, 0)
    }

    fun enterAlternateScreen(saveCursor: Boolean = true) {
        if (isAltScreen) return
        if (saveCursor) {
            mainCol = cursorCol; mainRow = cursorRow; mainStyle = style
        }
        altSavedCol = 0; altSavedRow = 0; altSavedStyle = TerminalStyle()
        altGrid = E.makeGrid(rows, cols); isAltScreen = true; cursorCol = 0; cursorRow = 0
        cachedNanoHint = false; altNanoDirty = false
        resetMargins()
    }

    fun exitAlternateScreen(restoreCursor: Boolean = true) {
        if (!isAltScreen) return
        isAltScreen = false; altGrid = E.makeGrid(rows, cols)
        cachedNanoHint = false; altNanoDirty = false
        if (restoreCursor) { cursorCol = mainCol; cursorRow = mainRow; style = mainStyle }
        clampCursor(); resetMargins()
    }

    fun setMouseTrackingMode(code: Int, enable: Boolean) { mouseMask = M.updateMouseMask(mouseMask, code, enable) }

    fun resetModes() {
        exitAlternateScreen(false)
        applicationCursorKeys = false
        mouseMask = 0
        sgrMouseMode = false
        bracketedPaste = false
        cursorVisible = true
        resetMargins()
    }

    fun resetForReconnect() = synchronized(lock) {
        if (isAltScreen) exitAlternateScreen(restoreCursor = true)
        resetModes()
        style = TerminalStyle()
        mainSavedCol = 0; mainSavedRow = 0; mainSavedStyle = TerminalStyle()
        altSavedCol = 0; altSavedRow = 0; altSavedStyle = TerminalStyle()
        mainCol = 0; mainRow = 0; mainStyle = TerminalStyle()
        clearLastWrite()
        wrapPending = false
        version++
    }

    fun cursorPosition(): Pair<Int, Int> = synchronized(lock) { cursorRow to cursorCol }
    fun screenSize(): Pair<Int, Int> = synchronized(lock) { rows to cols }

    fun isNanoScreenHint(): Boolean = synchronized(lock) {
        if (!isAltScreen) {
            cachedNanoHint = false
            altNanoDirty = false
            return false
        }
        if (altNanoDirty) {
            cachedNanoHint = M.detectNanoHint(altGrid, rows)
            altNanoDirty = false
        }
        cachedNanoHint
    }

    internal fun currentPromptContext(): String = synchronized(lock) {
        if (isAltScreen || rows <= 0) return@synchronized ""
        val grid = mainGrid
        val r = cursorRow.coerceIn(0, grid.lastIndex)
        val curLine = rowToTrimmedString(grid[r])
        if (curLine.isEmpty()) {
            return@synchronized if (r > 0) rowToTrimmedString(grid[r - 1]) else ""
        }
        val lastCh = curLine.last()
        if (r > 0 && (lastCh == ':' || lastCh == '：' || lastCh == '?')) {
            val prevLine = rowToTrimmedString(grid[r - 1])
            if (prevLine.isNotEmpty()) {
                return@synchronized "$prevLine $curLine"
            }
        }
        curLine
    }

    private fun rowToTrimmedString(row: Array<TerminalCell>): String {
        val sb = StringBuilder(row.size)
        for (cell in row) cell.appendTo(sb)
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }

    fun resize(newCols: Int, newRows: Int) = synchronized(lock) {
        val c = newCols.coerceIn(10, 400); val r = newRows.coerceIn(5, 200)
        if (c == cols && r == rows) return@synchronized

        val activeMainCursorRow = if (isAltScreen) mainRow else cursorRow
        val drop = if (r < rows && activeMainCursorRow >= r) {
            (activeMainCursorRow - r + 1).coerceIn(0, mainGrid.size)
        } else {
            0
        }
        if (drop > 0) {
            for (i in 0 until drop) {
                scrollback.addLast(mainGrid[i])
            }
            while (scrollback.size > maxScrollback) {
                scrollback.removeFirst()
            }
            if (isAltScreen) {
                mainRow = (mainRow - drop).coerceIn(0, r - 1)
            } else {
                cursorRow = (cursorRow - drop).coerceIn(0, r - 1)
            }
            mainSavedRow = (mainSavedRow - drop).coerceIn(0, r - 1)
        }

        mainGrid = E.resizeGridWithTopDrop(mainGrid, r, c, drop)
        altGrid = E.resizeGrid(altGrid, r, c)
        cols = c; rows = r; clampCursor(); resetMargins()
        if (isAltScreen) altNanoDirty = true
        version++
    }

    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = synchronized(lock) {
        E.buildSnapshot(cols, rows, cursorCol, cursorRow, cursorVisible, activeGrid(), scrollback, isAltScreen, bracketedPaste, version, scrollOffset)
    }

    fun extractPlainText(scrollOffset: Int = 0): String = E.extractText(snapshot(scrollOffset))

    companion object {
        private const val MAX_COMBINING_CHARS = 16
    }
}
