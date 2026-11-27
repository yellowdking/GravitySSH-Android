package com.gravityssh.app.terminal

class AnsiParser(
    private val buffer: TerminalBuffer,
    private val onCwdChanged: (String) -> Unit = {},
    private val onReply: ((String) -> Unit)? = null
) {
    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, CHARSET, STR_SKIP, STR_SKIP_ESC }

    private var state = State.GROUND
    private val csiBuf = StringBuilder(32)
    private val oscBuf = StringBuilder(128)
    private val csiParams = IntArray(32)
    private var csiParamCount = 0

    private var lastPrintedChar: Char = '\u0000'
    private var charsetSlot = 0
    private var g0DecGraphics = false
    private var g1DecGraphics = false
    private var useG1 = false
    private var pendingReplies: MutableList<String>? = null

    fun feed(text: String) {
        if (text.isEmpty()) return
        var repliesToSend: List<String>? = null
        buffer.synchronizedBuffer {
            for (i in 0 until text.length) processChar(text[i])
            buffer.touch()
            pendingReplies?.let {
                repliesToSend = it
                pendingReplies = null
            }
        }
        val replyCb = onReply
        if (replyCb != null && repliesToSend != null) {
            for (reply in repliesToSend!!) replyCb(reply)
        }
    }

    private fun emitReply(reply: String) {
        if (onReply == null) return
        val list = pendingReplies ?: ArrayList<String>(2).also { pendingReplies = it }
        list.add(reply)
    }

    private fun processChar(ch: Char) {
        when (state) {
            State.GROUND -> handleGround(ch)
            State.ESC -> handleEsc(ch)
            State.CHARSET -> handleCharset(ch)
            State.CSI -> handleCsi(ch)
            State.OSC -> handleOsc(ch)
            State.OSC_ESC -> {
                if (ch == '\\') {
                    finishOsc()
                    state = State.GROUND
                } else {
                    state = State.ESC
                    handleEsc(ch)
                }
            }
            State.STR_SKIP -> handleStrSkip(ch)
            State.STR_SKIP_ESC -> {
                when (ch) {
                    '\\' -> state = State.GROUND
                    '\u001B' -> state = State.STR_SKIP_ESC
                    else -> {
                        state = State.ESC
                        handleEsc(ch)
                    }
                }
            }
        }
    }

    private fun handleGround(ch: Char) {
        when (ch) {
            '\u001B' -> state = State.ESC
            '\r' -> buffer.carriageReturn()
            '\n', '\u000B', '\u000C' -> buffer.lineFeed()
            '\b' -> buffer.backspace()
            '\t' -> buffer.tab()
            '\u000E' -> useG1 = true
            '\u000F' -> useG1 = false
            '\u0007', '\u0000' -> Unit
            else -> if (ch >= ' ') {
                val activeDec = if (useG1) g1DecGraphics else g0DecGraphics
                val mapped = if (activeDec && ch.code in 0x60..0x7E) mapDecGraphics(ch) else ch
                buffer.putChar(mapped)
                lastPrintedChar = mapped
            }
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> { csiBuf.setLength(0); state = State.CSI }
            ']' -> { oscBuf.setLength(0); state = State.OSC }
            'P', '_', '^', 'X' -> state = State.STR_SKIP
            '(' -> { charsetSlot = 0; state = State.CHARSET }
            ')' -> { charsetSlot = 1; state = State.CHARSET }
            '*', '+' -> { charsetSlot = 2; state = State.CHARSET }
            '7' -> { buffer.saveCursor(); state = State.GROUND }
            '8' -> { buffer.restoreCursor(); state = State.GROUND }
            'D' -> { buffer.lineFeed(); state = State.GROUND }
            'E' -> { buffer.carriageReturn(); buffer.lineFeed(); state = State.GROUND }
            'M' -> { buffer.reverseIndex(); state = State.GROUND }
            'c' -> {
                buffer.resetModes()
                buffer.style = TerminalStyle()
                buffer.eraseDisplay(2)
                buffer.moveCursorTo(0, 0)
                lastPrintedChar = '\u0000'
                g0DecGraphics = false
                g1DecGraphics = false
                useG1 = false
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun handleCharset(ch: Char) {
        when (charsetSlot) {
            0 -> g0DecGraphics = ch == '0'
            1 -> g1DecGraphics = ch == '0'
        }
        state = State.GROUND
    }

    private fun handleCsi(ch: Char) {
        when {
            ch == '\u001B' -> state = State.ESC
            ch == '\u0018' || ch == '\u001A' -> state = State.GROUND
            ch.code < 0x20 -> handleGround(ch)
            ch.code in 0x40..0x7E -> {
                executeCsi(ch)
                state = State.GROUND
            }
            else -> if (csiBuf.length < 128) csiBuf.append(ch)
        }
    }

    private fun handleOsc(ch: Char) {
        when (ch) {
            '\u0007' -> { finishOsc(); state = State.GROUND }
            '\u001B' -> state = State.OSC_ESC
            '\u0018', '\u001A' -> state = State.GROUND
            else -> if (oscBuf.length < 512) oscBuf.append(ch)
        }
    }

    private fun handleStrSkip(ch: Char) {
        when (ch) {
            '\u0007', '\u0018', '\u001A' -> state = State.GROUND
            '\u001B' -> state = State.STR_SKIP_ESC
        }
    }

    private fun finishOsc() {
        val raw = oscBuf.toString()
        val sep = raw.indexOf(';')
        if (sep <= 0) return
        val arg = raw.substring(sep + 1)
        if (raw.substring(0, sep) == "7") {
            val path = parseOsc7Path(arg)
            if (path.isNotEmpty()) onCwdChanged(path)
        }
    }

    private fun executeCsi(finalChar: Char) {
        val len = csiBuf.length
        val first = if (len > 0) csiBuf[0] else '\u0000'
        val isPrivate = first == '?'
        if (!isPrivate && (first == '>' || first == '=' || first == '!')) return

        if (!isPrivate && finalChar == 'm') {
            buffer.style = AnsiSgrHelper.apply(buffer.style, csiBuf)
            return
        }

        val startIdx = if (isPrivate) 1 else 0
        parseCsiParams(startIdx, len)

        if (isPrivate) {
            if (finalChar == 'h' || finalChar == 'l') {
                val enable = finalChar == 'h'
                for (i in 0 until csiParamCount) {
                    TerminalModes.applyPrivateMode(buffer, csiParams[i], enable)
                }
            }
            return
        }

        when (finalChar) {
            'A' -> buffer.moveCursorBy(-param(0, 1), 0)
            'B', 'e' -> buffer.moveCursorBy(param(0, 1), 0)
            'C', 'a' -> buffer.moveCursorBy(0, param(0, 1))
            'D' -> buffer.moveCursorBy(0, -param(0, 1))
            'E' -> { buffer.moveCursorBy(param(0, 1), 0); buffer.carriageReturn() }
            'F' -> { buffer.moveCursorBy(-param(0, 1), 0); buffer.carriageReturn() }
            'G', '`' -> buffer.moveCursorTo(buffer.cursorRow, param(0, 1) - 1)
            'd' -> buffer.moveCursorTo(param(0, 1) - 1, buffer.cursorCol)
            'H', 'f' -> buffer.moveCursorTo(param(0, 1) - 1, param(1, 1) - 1)
            'J' -> buffer.eraseDisplay(param0(0))
            'K' -> buffer.eraseLine(param0(0))
            'X' -> buffer.eraseChars(param(0, 1))
            'P' -> buffer.deleteChars(param(0, 1))
            '@' -> buffer.insertChars(param(0, 1))
            'L' -> buffer.insertLines(param(0, 1))
            'M' -> buffer.deleteLines(param(0, 1))
            'S' -> buffer.scrollUp(param(0, 1))
            'T' -> buffer.scrollDown(param(0, 1))
            'r' -> buffer.setScrollRegion(param(0, 1) - 1, param(1, buffer.rows) - 1)
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'b' -> {
                val ch = lastPrintedChar
                if (ch != '\u0000') {
                    repeat(param(0, 1).coerceAtMost(4096)) { buffer.putChar(ch) }
                }
            }
            'n' -> when (param0(0)) {
                5 -> emitReply("\u001B[0n")
                6 -> emitReply("\u001B[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R")
            }
            'c' -> if (param0(0) == 0) emitReply("\u001B[?62;c")
        }
    }

    private fun parseCsiParams(start: Int, end: Int) {
        csiParamCount = 0
        if (start >= end) return
        var cur = 0
        var hasDigits = false
        for (i in start until end) {
            val ch = csiBuf[i]
            when {
                ch in '0'..'9' -> {
                    cur = (cur * 10 + (ch - '0')).coerceAtMost(999_999)
                    hasDigits = true
                }
                ch == ';' -> {
                    if (csiParamCount < csiParams.size) csiParams[csiParamCount++] = if (hasDigits) cur else 0
                    cur = 0
                    hasDigits = false
                }
                else -> Unit
            }
        }
        if (csiParamCount < csiParams.size) csiParams[csiParamCount++] = if (hasDigits) cur else 0
    }

    private fun param(index: Int, defaultVal: Int): Int {
        if (index >= csiParamCount) return defaultVal
        val v = csiParams[index]
        return if (v > 0) v else defaultVal
    }

    private fun param0(index: Int): Int = if (index < csiParamCount) csiParams[index] else 0

    companion object {
        private fun mapDecGraphics(ch: Char): Char = when (ch) {
            '`' -> '◆'
            'a' -> '▒'
            'f' -> '°'
            'g' -> '±'
            'j' -> '┘'
            'k' -> '┐'
            'l' -> '┌'
            'm' -> '└'
            'n' -> '┼'
            'o' -> '⎺'
            'p' -> '⎻'
            'q' -> '─'
            'r' -> '⎼'
            's' -> '⎽'
            't' -> '├'
            'u' -> '┤'
            'v' -> '┴'
            'w' -> '┬'
            'x' -> '│'
            'y' -> '≤'
            'z' -> '≥'
            '{' -> 'π'
            '|' -> '≠'
            '}' -> '£'
            '~' -> '·'
            else -> ch
        }

        internal fun parseOsc7Path(arg: String): String {
            val rawPath = when {
                arg.startsWith("file://", ignoreCase = true) -> {
                    val rest = arg.substring(7)
                    val slashIdx = rest.indexOf('/')
                    if (slashIdx < 0) return ""
                    rest.substring(slashIdx)
                }
                arg.startsWith("/") -> arg
                else -> return ""
            }
            return decodePercentUtf8(rawPath)
        }

        internal fun decodePercentUtf8(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder(s.length)
            var buf = ByteArray(16)
            var bLen = 0
            fun flush() {
                if (bLen > 0) {
                    out.append(String(buf, 0, bLen, Charsets.UTF_8))
                    bLen = 0
                }
            }
            var i = 0
            val n = s.length
            while (i < n) {
                if (s[i] == '%' && i + 2 < n) {
                    val hi = hexDigit(s[i + 1])
                    val lo = hexDigit(s[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        if (bLen == buf.size) buf = buf.copyOf(buf.size * 2)
                        buf[bLen++] = ((hi shl 4) or lo).toByte()
                        i += 3
                        continue
                    }
                }
                flush()
                out.append(s[i++])
            }
            flush()
            return out.toString()
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
