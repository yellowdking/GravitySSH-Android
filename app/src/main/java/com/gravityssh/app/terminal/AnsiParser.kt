package com.gravityssh.app.terminal

class AnsiParser(
    private val buffer: TerminalBuffer,
    private val onTitleChanged: (String) -> Unit = {},
    private val onCwdChanged: (String) -> Unit = {}
) {
    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private var state = State.GROUND
    private val csiBuf = StringBuilder()
    private val oscBuf = StringBuilder()

    fun feed(text: String) {
        if (text.isEmpty()) return
        buffer.synchronizedBuffer {
            for (ch in text) processChar(ch)
            buffer.touch()
        }
    }

    private fun processChar(ch: Char) {
        when (state) {
            State.GROUND -> handleGround(ch)
            State.ESC -> handleEsc(ch)
            State.CHARSET -> state = State.GROUND
            State.CSI -> handleCsi(ch)
            State.OSC -> handleOsc(ch)
            State.OSC_ESC -> { if (ch == '\\') finishOsc(); state = State.GROUND }
        }
    }

    private fun handleGround(ch: Char) {
        when (ch) {
            '\u001B' -> state = State.ESC
            '\r' -> buffer.carriageReturn()
            '\n', '\u000B', '\u000C' -> buffer.lineFeed()
            '\b' -> buffer.backspace()
            '\t' -> buffer.tab()
            '\u0007', '\u0000' -> Unit
            else -> if (ch >= ' ') buffer.putChar(ch)
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> { csiBuf.setLength(0); state = State.CSI }
            ']' -> { oscBuf.setLength(0); state = State.OSC }
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> { buffer.saveCursor(); state = State.GROUND }
            '8' -> { buffer.restoreCursor(); state = State.GROUND }
            'D' -> { buffer.lineFeed(); state = State.GROUND }
            'E' -> { buffer.carriageReturn(); buffer.lineFeed(); state = State.GROUND }
            'M' -> { buffer.reverseIndex(); state = State.GROUND }
            'c' -> { buffer.resetModes(); buffer.style = TerminalStyle(); buffer.eraseDisplay(2); buffer.moveCursorTo(0, 0); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            executeCsi(csiBuf.toString(), ch)
            state = State.GROUND
        } else if (csiBuf.length < 128) {
            csiBuf.append(ch)
        }
    }

    private fun handleOsc(ch: Char) {
        when (ch) {
            '\u0007' -> { finishOsc(); state = State.GROUND }
            '\u001B' -> state = State.OSC_ESC
            else -> if (oscBuf.length < 512) oscBuf.append(ch)
        }
    }

    private fun finishOsc() {
        val raw = oscBuf.toString()
        val sep = raw.indexOf(';')
        if (sep <= 0) return
        val arg = raw.substring(sep + 1)
        when (raw.substring(0, sep)) {
            "0", "2" -> onTitleChanged(arg)
            "7" -> {
                val path = arg.removePrefix("file://").substringAfter('/', "").let { "/$it" }
                if (path.length > 1) onCwdChanged(path)
            }
        }
    }

    private fun executeCsi(rawParams: String, finalChar: Char) {
        val isPrivate = rawParams.startsWith("?")
        if (!isPrivate && (rawParams.startsWith(">") || rawParams.startsWith("=") || rawParams.startsWith("!"))) return
        val clean = rawParams.trimStart('?', '>', '!', '=')
        val params = if (clean.isEmpty()) emptyList() else clean.split(';').map { it.toIntOrNull() ?: 0 }
        fun p(i: Int, def: Int = 1): Int = params.getOrNull(i)?.takeIf { it > 0 } ?: def
        fun p0(i: Int): Int = params.getOrNull(i) ?: 0

        if (isPrivate) {
            if (finalChar == 'h' || finalChar == 'l') {
                val enable = finalChar == 'h'
                for (code in params) TerminalModes.applyPrivateMode(buffer, code, enable)
            }
            return
        }

        when (finalChar) {
            'A' -> buffer.moveCursorBy(-p(0), 0)
            'B', 'e' -> buffer.moveCursorBy(p(0), 0)
            'C', 'a' -> buffer.moveCursorBy(0, p(0))
            'D' -> buffer.moveCursorBy(0, -p(0))
            'E' -> { buffer.moveCursorBy(p(0), 0); buffer.carriageReturn() }
            'F' -> { buffer.moveCursorBy(-p(0), 0); buffer.carriageReturn() }
            'G', '`' -> buffer.moveCursorTo(buffer.cursorRow, p(0) - 1)
            'd' -> buffer.moveCursorTo(p(0) - 1, buffer.cursorCol)
            'H', 'f' -> buffer.moveCursorTo(p(0) - 1, p(1) - 1)
            'J' -> buffer.eraseDisplay(p0(0))
            'K' -> buffer.eraseLine(p0(0))
            'X' -> buffer.eraseChars(p(0))
            'P' -> buffer.deleteChars(p(0))
            '@' -> buffer.insertChars(p(0))
            'L' -> buffer.insertLines(p(0))
            'M' -> buffer.deleteLines(p(0))
            'S' -> buffer.scrollUp(p(0))
            'T' -> buffer.scrollDown(p(0))
            'r' -> buffer.setScrollRegion(p(0, 1) - 1, p(1, buffer.rows) - 1)
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'm' -> buffer.style = AnsiSgrHelper.apply(buffer.style, if (params.isEmpty()) listOf(0) else params)
        }
    }
}
