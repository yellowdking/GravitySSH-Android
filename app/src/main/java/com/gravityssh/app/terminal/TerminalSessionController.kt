package com.gravityssh.app.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    @Volatile
    private var inputSender: (String) -> Unit = onSendInput

    private val parser = AnsiParser(
        buffer = buffer,
        onCwdChanged = onCwdDetected,
        onReply = { reply -> inputSender(reply) }
    )
    private val highlighter = KeywordHighlighter.Session()

    private val frameSeq = AtomicLong(0L)
    private val lastFrameEmitNs = AtomicLong(0L)
    private val pendingEmit = AtomicBoolean(false)

    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

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
        val skipHighlight = buffer.isAltScreen || TerminalModes.containsAltScreenEnter(rawText)
        val processed = if (skipHighlight) {
            val pending = highlighter.flushPending()
            highlighter.reset()
            if (pending.isEmpty()) rawText else pending + rawText
        } else if (keywordHighlightEnabled()) {
            highlighter.highlight(rawText)
        } else {
            val pending = highlighter.flushPending()
            if (pending.isEmpty()) rawText else pending + rawText
        }
        if (processed.isNotEmpty()) {
            parser.feed(processed)
            syncModeFlows()
            notifyFrameUpdated(immediate = false)
        }
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
        syncModeFlows()
        notifyFrameUpdated(immediate = true)
    }

    fun snapshot(scrollOffset: Int = 0): TerminalSnapshot = buffer.snapshot(scrollOffset)

    fun extractPlainText(scrollOffset: Int = 0): String = buffer.extractPlainText(scrollOffset)

    fun isAlternateScreen(): Boolean = buffer.isAltScreen

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
        val (rows, cols) = buffer.screenSize()
        val seq = TerminalModes.sgrMouseTapSequence(col0Based, row0Based, cols, rows)
        inputSender(seq)
        return seq
    }

    private fun syncModeFlows() {
        _bracketedPaste.value = buffer.bracketedPaste
        val alt = buffer.isAltScreen
        _alternateScreen.value = alt
        _nanoDetected.value = if (alt) buffer.isNanoScreenHint() else false
    }

    private fun notifyFrameUpdated(immediate: Boolean) {
        val next = frameSeq.incrementAndGet()
        if (immediate) {
            lastFrameEmitNs.set(System.nanoTime())
            pendingEmit.set(false)
            _frameVersion.value = next
            return
        }
        val now = System.nanoTime()
        val prev = lastFrameEmitNs.get()
        if (prev == 0L || now - prev >= FRAME_COALESCE_NS) {
            if (lastFrameEmitNs.compareAndSet(prev, now)) {
                pendingEmit.set(false)
                _frameVersion.value = next
                return
            }
        }
        if (pendingEmit.compareAndSet(false, true)) {
            val remainingMs = ((FRAME_COALESCE_NS - (now - lastFrameEmitNs.get())).coerceAtLeast(1_000_000L) + 999_999L) / 1_000_000L
            frameFlushExecutor.schedule({
                pendingEmit.set(false)
                lastFrameEmitNs.set(System.nanoTime())
                _frameVersion.value = frameSeq.get()
            }, remainingMs.coerceIn(1L, 16L), TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private const val FRAME_COALESCE_NS = 16_000_000L
        private val frameFlushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TerminalFrameCoalescer").apply { isDaemon = true }
        }
    }
}
