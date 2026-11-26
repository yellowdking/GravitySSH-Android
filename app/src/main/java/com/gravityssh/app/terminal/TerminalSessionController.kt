package com.gravityssh.app.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalSessionController(
    val sessionId: String,
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val keywordHighlightEnabled: () -> Boolean = { true },
    private val onCwdDetected: (String) -> Unit = {},
    private val onCommandCommitted: (String) -> Unit = {},
    onSendInput: (String) -> Unit = {}
) {
    val buffer = TerminalBuffer(initialCols, initialRows)
    private val parser = AnsiParser(
        buffer = buffer,
        onTitleChanged = { _title.value = it },
        onCwdChanged = onCwdDetected
    )

    @Volatile
    private var inputSender: (String) -> Unit = onSendInput

    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _currentInputLine = MutableStateFlow("")
    val currentInputLine: StateFlow<String> = _currentInputLine.asStateFlow()

    private val _bracketedPaste = MutableStateFlow(false)
    val bracketedPaste: StateFlow<Boolean> = _bracketedPaste.asStateFlow()

    private val _alternateScreen = MutableStateFlow(false)
    val alternateScreen: StateFlow<Boolean> = _alternateScreen.asStateFlow()

    private val _nanoDetected = MutableStateFlow(false)
    val nanoDetected: StateFlow<Boolean> = _nanoDetected.asStateFlow()

    fun setInputSender(sender: (String) -> Unit) {
        inputSender = sender
    }

    fun onRemoteOutput(rawText: String) {
        if (rawText.isEmpty()) return
        val skipHighlight = buffer.isAlternateScreen || TerminalModes.containsAltScreenEnter(rawText)
        val processed = if (keywordHighlightEnabled() && !skipHighlight) {
            KeywordHighlighter.highlight(rawText)
        } else {
            rawText
        }
        parser.feed(processed)
        syncModeFlows()
        _frameVersion.value = _frameVersion.value + 1
    }

    fun onLocalInput(data: String) {
        if (data.isEmpty()) return
        val prev = _currentInputLine.value
        if (data.contains('\r') || data.contains('\n')) {
            val committed = prev.trim()
            if (committed.isNotEmpty() && !buffer.isAlternateScreen) {
                onCommandCommitted(committed)
            }
        }
        _currentInputLine.value = AutocompleteEngine.applyTerminalInputToLine(prev, data)
    }

    fun resize(cols: Int, rows: Int) {
        buffer.resize(cols, rows)
        syncModeFlows()
        _frameVersion.value = _frameVersion.value + 1
    }

    fun clear() {
        buffer.clearAll()
        syncModeFlows()
        _frameVersion.value = _frameVersion.value + 1
    }

    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = buffer.snapshot(scrollOffset)

    fun extractPlainText(scrollOffset: Int = 0): String = buffer.extractPlainText(scrollOffset)

    fun isAlternateScreen(): Boolean = buffer.isAlternateScreen

    fun isNanoDetected(): Boolean = buffer.isNanoScreenHint()

    fun isApplicationCursorKeys(): Boolean = buffer.applicationCursorKeys

    fun isMouseTrackingEnabled(): Boolean = buffer.mouseTrackingEnabled

    fun isSgrMouseMode(): Boolean = buffer.sgrMouseMode

    fun cursorPosition(): Pair<Int, Int> = buffer.cursorPosition()

    fun screenSize(): Pair<Int, Int> = buffer.screenSize()

    fun arrowSequence(dir: CursorDir): String =
        TerminalModes.arrowSequence(dir, buffer.applicationCursorKeys)

    fun sendArrow(dir: CursorDir, repeat: Int = 1): String {
        if (repeat <= 0) return ""
        val seq = arrowSequence(dir).repeat(repeat.coerceAtMost(500))
        inputSender(seq)
        return seq
    }

    fun sendSgrMouseTap(col0Based: Int, row0Based: Int): String {
        val seq = TerminalModes.sgrMouseTapSequence(col0Based, row0Based, buffer.cols, buffer.rows)
        inputSender(seq)
        return seq
    }

    private fun syncModeFlows() {
        _bracketedPaste.value = buffer.bracketedPasteMode
        _alternateScreen.value = buffer.isAlternateScreen
        _nanoDetected.value = buffer.isNanoScreenHint()
    }
}
