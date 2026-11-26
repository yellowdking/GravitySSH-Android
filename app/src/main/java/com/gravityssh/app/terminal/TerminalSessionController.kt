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
    private val onCommandCommitted: (String) -> Unit = {}
) {
    val buffer = TerminalBuffer(initialCols, initialRows)
    private val parser = AnsiParser(
        buffer = buffer,
        onTitleChanged = { _title.value = it },
        onCwdChanged = onCwdDetected
    )

    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _currentInputLine = MutableStateFlow("")
    val currentInputLine: StateFlow<String> = _currentInputLine.asStateFlow()

    private val _bracketedPaste = MutableStateFlow(false)
    val bracketedPaste: StateFlow<Boolean> = _bracketedPaste.asStateFlow()

    fun onRemoteOutput(rawText: String) {
        if (rawText.isEmpty()) return
        val processed = if (keywordHighlightEnabled() && !buffer.isAltScreen) {
            KeywordHighlighter.highlight(rawText)
        } else {
            rawText
        }
        parser.feed(processed)
        _bracketedPaste.value = buffer.bracketedPaste
        _frameVersion.value = _frameVersion.value + 1
    }

    fun onLocalInput(data: String) {
        if (data.isEmpty()) return
        val prev = _currentInputLine.value
        if (data.contains('\r') || data.contains('\n')) {
            val committed = prev.trim()
            if (committed.isNotEmpty() && !buffer.isAltScreen) {
                onCommandCommitted(committed)
            }
        }
        _currentInputLine.value = AutocompleteEngine.applyTerminalInputToLine(prev, data)
    }

    fun resize(cols: Int, rows: Int) {
        buffer.resize(cols, rows)
        _frameVersion.value = _frameVersion.value + 1
    }

    fun clear() {
        buffer.clearAll()
        _frameVersion.value = _frameVersion.value + 1
    }

    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = buffer.snapshot(scrollOffset)

    fun extractPlainText(scrollOffset: Int = 0): String = buffer.extractPlainText(scrollOffset)
}
