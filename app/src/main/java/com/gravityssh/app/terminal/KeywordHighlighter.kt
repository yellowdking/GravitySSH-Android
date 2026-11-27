package com.gravityssh.app.terminal

import java.util.regex.Pattern

object KeywordHighlighter {
    private val ANSI_TOKEN_PATTERN: Pattern = Pattern.compile(
        "(\\u001B\\[[0-9:;<=>?]*[ -/]*[@-~]|" +
            "\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|" +
            "\\u001B[P_^X][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|" +
            "\\u001B[()*+][ -~]|" +
            "\\u001B[@-OQ-WYZ\\\\])"
    )
    private val HIGHLIGHT_PATTERN: Pattern = Pattern.compile(
        "(\\b(?:error|fatal|panic|failed|failure|exception|critical|denied)\\b|" +
            "\\b(?:warn|warning|deprecated|timeout|retry)\\b|" +
            "\\b(?:ok|success|succeeded|passed|healthy)\\b|" +
            "active \\(running\\)|" +
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b)",
        Pattern.CASE_INSENSITIVE
    )

    private const val RED = "\u001B[38;5;203m"
    private const val AMBER = "\u001B[38;5;221m"
    private const val GREEN = "\u001B[38;5;114m"
    private const val CYAN = "\u001B[38;5;117m"
    private const val FG_RESET = "\u001B[39m"
    private const val MAX_PENDING_ESC = 512
    private const val MAX_REUSABLE_SB_CAPACITY = 32 * 1024

    fun highlight(text: String): String {
        if (text.isEmpty()) return text
        return Session().process(text, holdIncompleteTail = false)
    }

    class Session {
        private val lock = Any()
        private var inRemoteFgColor = false
        private var inAnsiString = false
        private var pendingEsc = ""
        private val outBuf = StringBuilder(256)

        fun highlight(chunk: String): String = synchronized(lock) {
            process(chunk, holdIncompleteTail = true)
        }

        fun flushPending(): String = synchronized(lock) {
            if (pendingEsc.isEmpty()) return ""
            val out = pendingEsc
            pendingEsc = ""
            out
        }

        fun reset() = synchronized(lock) {
            pendingEsc = ""
            inRemoteFgColor = false
            inAnsiString = false
            resetBuf()
        }

        internal fun flushAndReset(): String = synchronized(lock) {
            val out = pendingEsc
            pendingEsc = ""
            inRemoteFgColor = false
            inAnsiString = false
            resetBuf()
            out
        }

        internal fun peekPending(): String = synchronized(lock) { pendingEsc }

        private fun resetBuf() {
            outBuf.setLength(0)
            if (outBuf.capacity() > MAX_REUSABLE_SB_CAPACITY) {
                outBuf.trimToSize()
            }
        }

        private fun finishBuf(): String {
            val result = outBuf.toString()
            resetBuf()
            return result
        }

        internal fun process(chunk: String, holdIncompleteTail: Boolean): String {
            val text = if (pendingEsc.isNotEmpty()) {
                val merged = pendingEsc + chunk
                pendingEsc = ""
                merged
            } else {
                chunk
            }
            if (text.isEmpty()) return text

            var startOffset = 0
            if (inAnsiString) {
                val termEnd = findAnsiStringTerminatorEnd(text)
                if (termEnd < 0) {
                    if (holdIncompleteTail && text.endsWith("\u001B")) {
                        pendingEsc = "\u001B"
                        return text.substring(0, text.length - 1)
                    }
                    return text
                }
                inAnsiString = false
                startOffset = termEnd
                if (startOffset >= text.length) return text
            }

            if (text.indexOf('\u001B', startOffset) < 0) {
                if (inRemoteFgColor || !mightMatch(text, startOffset, text.length)) return text
                outBuf.setLength(0)
                if (startOffset > 0) outBuf.append(text, 0, startOffset)
                val changed = appendColorizedPlain(outBuf, text, startOffset, text.length)
                return if (changed) finishBuf() else {
                    resetBuf()
                    text
                }
            }

            outBuf.setLength(0)
            if (startOffset > 0) outBuf.append(text, 0, startOffset)
            var changed = false
            var lastEnd = startOffset

            val ansiMatcher = ANSI_TOKEN_PATTERN.matcher(text).region(startOffset, text.length)
            while (ansiMatcher.find()) {
                val mStart = ansiMatcher.start()
                val mEnd = ansiMatcher.end()
                if (mStart > lastEnd) {
                    if (inRemoteFgColor) {
                        outBuf.append(text, lastEnd, mStart)
                    } else if (appendColorizedPlain(outBuf, text, lastEnd, mStart)) {
                        changed = true
                    }
                }
                outBuf.append(text, mStart, mEnd)
                inRemoteFgColor = updateFgColorState(inRemoteFgColor, text, mStart, mEnd)
                lastEnd = mEnd
            }

            if (lastEnd < text.length) {
                val escIdx = if (holdIncompleteTail) text.indexOf('\u001B', lastEnd) else -1
                if (escIdx >= 0 && isIncompleteAnsiPrefix(text, escIdx)) {
                    if (escIdx > lastEnd) {
                        if (inRemoteFgColor) {
                            outBuf.append(text, lastEnd, escIdx)
                        } else if (appendColorizedPlain(outBuf, text, lastEnd, escIdx)) {
                            changed = true
                        }
                    }
                    if (text.length - escIdx <= MAX_PENDING_ESC) {
                        pendingEsc = text.substring(escIdx)
                        changed = true
                    } else if (isStringIntroducer(text, escIdx)) {
                        inAnsiString = true
                        if (text.endsWith("\u001B")) {
                            outBuf.append(text, escIdx, text.length - 1)
                            pendingEsc = "\u001B"
                            changed = true
                        } else {
                            outBuf.append(text, escIdx, text.length)
                        }
                    } else {
                        outBuf.append(text, escIdx, text.length)
                    }
                } else {
                    if (inRemoteFgColor) {
                        outBuf.append(text, lastEnd, text.length)
                    } else if (appendColorizedPlain(outBuf, text, lastEnd, text.length)) {
                        changed = true
                    }
                }
            }
            return if (changed) finishBuf() else {
                resetBuf()
                text
            }
        }
    }

    private fun findAnsiStringTerminatorEnd(text: String): Int {
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\u0007', '\u0018', '\u001A' -> return i + 1
                '\u001B' -> {
                    if (i + 1 < n) {
                        return if (text[i + 1] == '\\') i + 2 else i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun isStringIntroducer(text: String, start: Int): Boolean {
        if (start + 1 >= text.length) return false
        return when (text[start + 1]) {
            ']', 'P', '_', '^', 'X' -> true
            else -> false
        }
    }

    private fun appendColorizedPlain(out: StringBuilder, text: String, start: Int, end: Int): Boolean {
        if (end - start < 2 || !mightMatch(text, start, end)) {
            out.append(text, start, end)
            return false
        }
        val matcher = HIGHLIGHT_PATTERN.matcher(text).region(start, end)
        if (!matcher.find()) {
            out.append(text, start, end)
            return false
        }
        var cursor = start
        do {
            val ms = matcher.start()
            val me = matcher.end()
            if (ms > cursor) out.append(text, cursor, ms)
            out.append(pickColor(text, ms, me))
            out.append(text, ms, me)
            out.append(FG_RESET)
            cursor = me
        } while (matcher.find())
        if (cursor < end) out.append(text, cursor, end)
        return true
    }

    private fun mightMatch(s: String, start: Int, end: Int): Boolean {
        if (end - start < 2) return false
        for (i in start until end - 1) {
            val c0 = s[i]
            if (c0 == '.' && i > start && s[i - 1] in '0'..'9' && s[i + 1] in '0'..'9') return true
            if (i == start || !isWordChar(s[i - 1])) {
                val a = c0.lowercaseChar()
                val b = s[i + 1].lowercaseChar()
                val hit = when (a) {
                    'e' -> b == 'r' || b == 'x'
                    'f', 'p', 'w' -> b == 'a'
                    'c' -> b == 'r'
                    'd', 'r', 'h' -> b == 'e'
                    't' -> b == 'i'
                    'o' -> b == 'k'
                    's' -> b == 'u'
                    'a' -> b == 'c'
                    else -> false
                }
                if (hit) return true
            }
        }
        return false
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun isIncompleteAnsiPrefix(text: String, start: Int): Boolean {
        val rem = text.length - start
        if (rem <= 1) return true
        return when (val second = text[start + 1]) {
            '[' -> {
                for (i in start + 2 until text.length) {
                    if (text[i].code !in 0x20..0x3F) return false
                }
                true
            }
            ']', 'P', '_', '^', 'X' -> true
            '(', ')', '*', '+' -> rem == 2
            else -> second == '\u001B'
        }
    }

    private fun updateFgColorState(current: Boolean, seq: CharSequence, start: Int, end: Int): Boolean {
        val len = end - start
        if (len < 3 || seq[start] != '\u001B' || seq[start + 1] != '[' || seq[end - 1] != 'm') return current
        if (len == 3) return false
        val first = seq[start + 2]
        if (first == '?' || first == '>' || first == '=' || first == '!') return current

        var fg = current
        var ext = 0 // 1 = awaiting mode for 38, 2 = awaiting mode for 48
        var skip = 0
        var pos = start + 2
        val paramEnd = end - 1
        while (true) {
            var sep = pos
            var colonIdx = -1
            while (sep < paramEnd) {
                val c = seq[sep]
                if (c == ';') break
                if (c == ':' && colonIdx < 0) colonIdx = sep
                sep++
            }
            if (colonIdx >= 0) {
                ext = 0
                skip = 0
                val head = parseInt(seq, pos, colonIdx, -1)
                if (head == 38) fg = true
            } else {
                val v = parseInt(seq, pos, sep, 0)
                if (ext != 0) {
                    skip = when (v) { 5 -> 1; 2 -> 3; else -> 0 }
                    ext = 0
                } else if (skip > 0) {
                    skip--
                } else {
                    when (v) {
                        0, 39 -> fg = false
                        in 30..37, in 90..97 -> fg = true
                        38 -> { fg = true; ext = 1 }
                        48 -> ext = 2
                    }
                }
            }
            if (sep >= paramEnd) break
            pos = sep + 1
        }
        return fg
    }

    private fun parseInt(s: CharSequence, start: Int, end: Int, emptyVal: Int): Int {
        if (start >= end) return emptyVal
        var acc = 0
        for (i in start until end) {
            val d = s[i] - '0'
            if (d !in 0..9) return -1
            acc = (acc * 10 + d).coerceAtMost(999_999)
        }
        return acc
    }

    private fun pickColor(text: CharSequence, start: Int, end: Int): String {
        val first = text[start]
        if (first in '0'..'9') return CYAN
        return when (first.lowercaseChar()) {
            'a', 'o', 's', 'h' -> GREEN
            'p' -> if (end - start == 6 && text[start + 2].lowercaseChar() == 's') GREEN else RED
            'w', 't', 'r' -> AMBER
            'd' -> if (end - start == 10 && text[start + 2].lowercaseChar() == 'p') AMBER else RED
            else -> RED
        }
    }
}
