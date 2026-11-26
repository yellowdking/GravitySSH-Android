package com.gravityssh.app.terminal

class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val maxScrollback: Int = 3000
) {
    private val lock = Any()
    var cols: Int = initialCols.coerceIn(10, 400); private set
    var rows: Int = initialRows.coerceIn(5, 200); private set

    private var mainGrid = TerminalBufferEdit.makeGrid(rows, cols)
    private var altGrid = TerminalBufferEdit.makeGrid(rows, cols)
    private val scrollback = ArrayDeque<Array<TerminalCell>>()

    var isAltScreen: Boolean = false; private set
    var cursorCol: Int = 0; private set
    var cursorRow: Int = 0; private set
    var cursorVisible: Boolean = true
    var bracketedPaste: Boolean = false
    var style: TerminalStyle = TerminalStyle()

    private var savedCol = 0; private var savedRow = 0; private var savedStyle = TerminalStyle()
    private var mainCursorCol = 0; private var mainCursorRow = 0
    private var topMargin = 0; private var bottomMargin = rows - 1
    private var wrapPending = false; private var version: Long = 0L

    private fun activeGrid() = if (isAltScreen) altGrid else mainGrid
    fun <T> synchronizedBuffer(block: () -> T): T = synchronized(lock) { block() }
    fun touch() { version++ }

    fun putChar(ch: Char) {
        if (wrapPending) { cursorCol = 0; lineFeed(); wrapPending = false }
        val grid = activeGrid()
        if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
            grid[cursorRow][cursorCol] = TerminalCell(ch, style.fg, style.bg, style.bold, style.dim, style.underline, style.inverse)
        }
        if (cursorCol >= cols - 1) wrapPending = true else cursorCol++
    }

    fun carriageReturn() { cursorCol = 0; wrapPending = false }
    fun backspace() { wrapPending = false; if (cursorCol > 0) cursorCol-- }
    fun tab() { wrapPending = false; cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1) }

    fun lineFeed() {
        wrapPending = false
        if (cursorRow == bottomMargin) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    fun reverseIndex() {
        wrapPending = false
        if (cursorRow == topMargin) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    fun moveCursorTo(row: Int, col: Int) {
        wrapPending = false; cursorRow = row.coerceIn(0, rows - 1); cursorCol = col.coerceIn(0, cols - 1)
    }

    fun moveCursorBy(dRow: Int, dCol: Int) = moveCursorTo(cursorRow + dRow, cursorCol + dCol)
    fun saveCursor() { savedCol = cursorCol; savedRow = cursorRow; savedStyle = style }

    fun restoreCursor() {
        cursorCol = savedCol.coerceIn(0, cols - 1); cursorRow = savedRow.coerceIn(0, rows - 1)
        style = savedStyle; wrapPending = false
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1); val b = bottom.coerceIn(0, rows - 1)
        if (t < b) { topMargin = t; bottomMargin = b } else { topMargin = 0; bottomMargin = rows - 1 }
        moveCursorTo(0, 0)
    }

    fun setAltScreen(enable: Boolean) {
        if (enable == isAltScreen) return
        if (enable) {
            mainCursorCol = cursorCol; mainCursorRow = cursorRow
            altGrid = TerminalBufferEdit.makeGrid(rows, cols); isAltScreen = true; cursorCol = 0; cursorRow = 0
        } else {
            isAltScreen = false; cursorCol = mainCursorCol.coerceIn(0, cols - 1); cursorRow = mainCursorRow.coerceIn(0, rows - 1)
        }
        topMargin = 0; bottomMargin = rows - 1; wrapPending = false
    }

    fun scrollUp(count: Int) = TerminalBufferEdit.scrollUp(
        activeGrid(), scrollback, topMargin, bottomMargin, cols, rows, isAltScreen, maxScrollback, count
    )

    fun scrollDown(count: Int) = TerminalBufferEdit.scrollDown(activeGrid(), topMargin, bottomMargin, cols, count)

    fun eraseDisplay(mode: Int) = TerminalBufferEdit.eraseDisplay(activeGrid(), cursorRow, cursorCol, cols, rows, style, mode) { if (!isAltScreen) scrollback.clear() }
    fun eraseLine(mode: Int) = TerminalBufferEdit.eraseLine(activeGrid()[cursorRow], cursorCol, cols, style, mode)
    fun eraseChars(count: Int) = TerminalBufferEdit.eraseChars(activeGrid()[cursorRow], cursorCol, cols, style, count)
    fun deleteChars(count: Int) = TerminalBufferEdit.deleteChars(activeGrid()[cursorRow], cursorCol, cols, style, count)
    fun insertChars(count: Int) = TerminalBufferEdit.insertChars(activeGrid()[cursorRow], cursorCol, cols, style, count)
    fun insertLines(count: Int) { if (cursorRow in topMargin..bottomMargin) { val s = topMargin; topMargin = cursorRow; scrollDown(count); topMargin = s } }
    fun deleteLines(count: Int) { if (cursorRow in topMargin..bottomMargin) { val s = topMargin; topMargin = cursorRow; scrollUp(count); topMargin = s } }

    fun resize(newCols: Int, newRows: Int) = synchronized(lock) {
        val c = newCols.coerceIn(10, 400); val r = newRows.coerceIn(5, 200)
        if (c == cols && r == rows) return@synchronized
        mainGrid = TerminalBufferEdit.resizeGrid(mainGrid, r, c); altGrid = TerminalBufferEdit.resizeGrid(altGrid, r, c)
        cols = c; rows = r; topMargin = 0; bottomMargin = r - 1
        cursorCol = cursorCol.coerceIn(0, c - 1); cursorRow = cursorRow.coerceIn(0, r - 1); wrapPending = false; version++
    }

    fun clearAll() = synchronized(lock) {
        mainGrid = TerminalBufferEdit.makeGrid(rows, cols); altGrid = TerminalBufferEdit.makeGrid(rows, cols)
        scrollback.clear(); cursorCol = 0; cursorRow = 0; wrapPending = false; version++
    }

    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = synchronized(lock) {
        TerminalBufferEdit.buildSnapshot(cols, rows, cursorCol, cursorRow, cursorVisible, activeGrid(), scrollback, isAltScreen, bracketedPaste, version, scrollOffset)
    }

    fun extractPlainText(scrollOffset: Int = 0): String = TerminalBufferEdit.extractText(snapshot(scrollOffset))
}
