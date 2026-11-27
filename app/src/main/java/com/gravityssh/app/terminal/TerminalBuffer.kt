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

    internal fun activeGrid() = if (isAltScreen) altGrid else mainGrid
    internal fun currentRowCells() = activeGrid()[cursorRow]
    internal fun clearWrapPending() { wrapPending = false }
    internal fun markAltNanoDirty() { if (isAltScreen) altNanoDirty = true }
    internal fun markAltRowDirty(row: Int) {
        if (isAltScreen && (row == 0 || row >= rows - 2)) altNanoDirty = true
    }
    private fun resetMargins() { topMargin = 0; bottomMargin = rows - 1; wrapPending = false }
    private fun clampCursor() { cursorCol = cursorCol.coerceIn(0, cols - 1); cursorRow = cursorRow.coerceIn(0, rows - 1) }

    fun <T> synchronizedBuffer(block: () -> T): T = synchronized(lock) { block() }
    fun touch() { version++ }

    fun putChar(ch: Char) {
        val w = charWidth(ch)
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
            E.writeWideCell(activeGrid(), cursorRow, cursorCol, rows, cols, ch, style)
            markAltRowDirty(cursorRow)
            if (cursorCol + 2 >= cols) {
                cursorCol = cols - 1
                wrapPending = true
            } else {
                cursorCol += 2
            }
        } else {
            E.writeCell(activeGrid(), cursorRow, cursorCol, rows, cols, ch, style)
            markAltRowDirty(cursorRow)
            if (cursorCol >= cols - 1) wrapPending = true else cursorCol++
        }
    }

    fun carriageReturn() { cursorCol = 0; wrapPending = false }
    fun backspace() { wrapPending = false; if (cursorCol > 0) cursorCol-- }
    fun tab() { wrapPending = false; cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1) }
    fun lineFeed() { wrapPending = false; if (cursorRow == bottomMargin) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++ }
    fun reverseIndex() { wrapPending = false; if (cursorRow == topMargin) scrollDown(1) else if (cursorRow > 0) cursorRow-- }
    fun moveCursorTo(row: Int, col: Int) { wrapPending = false; cursorRow = row.coerceIn(0, rows - 1); cursorCol = col.coerceIn(0, cols - 1) }
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
        clampCursor(); wrapPending = false
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
        exitAlternateScreen(false); applicationCursorKeys = false; mouseMask = 0
        sgrMouseMode = false; bracketedPaste = false; cursorVisible = true
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

    fun resize(newCols: Int, newRows: Int) = synchronized(lock) {
        val c = newCols.coerceIn(10, 400); val r = newRows.coerceIn(5, 200)
        if (c == cols && r == rows) return@synchronized
        mainGrid = E.resizeGrid(mainGrid, r, c); altGrid = E.resizeGrid(altGrid, r, c)
        cols = c; rows = r; clampCursor(); resetMargins()
        if (isAltScreen) altNanoDirty = true
        version++
    }

    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = synchronized(lock) {
        E.buildSnapshot(cols, rows, cursorCol, cursorRow, cursorVisible, activeGrid(), scrollback, isAltScreen, bracketedPaste, version, scrollOffset)
    }

    fun extractPlainText(scrollOffset: Int = 0): String = E.extractText(snapshot(scrollOffset))
}
