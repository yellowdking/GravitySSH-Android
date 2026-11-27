package com.gravityssh.app.terminal

object KeywordHighlighter {
    private val ANSI_TOKEN_RE = Regex(
        "(\\u001B\\[[0-9:;<=>?]*[ -/]*[@-~]|" +
            "\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|" +
            "\\u001B[P_^X][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|" +
            "\\u001B[()*+][ -~]|" +
            "\\u001B[@-OQ-WYZ\\\\])"
    )
    private val IPV4_RE = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    private val HIGHLIGHT_RE = Regex(
        "(\\b(?:error|fatal|panic|failed|failure|exception|critical|denied)\\b|" +
            "\\b(?:warn|warning|deprecated|timeout|retry)\\b|" +
            "\\b(?:ok|success|succeeded|passed|healthy)\\b|" +
            "active \\(running\\)|" +
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b)",
        RegexOption.IGNORE_CASE
    )

    private const val RED = "\u001B[38;5;203m"
    private const val AMBER = "\u001B[38;5;221m"
    private const val GREEN = "\u001B[38;5;114m"
    private const val CYAN = "\u001B[38;5;117m"
    private const val FG_RESET = "\u001B[39m"
    private const val MAX_PENDING_ESC = 512

    fun highlight(text: String): String {
        if (text.isEmpty()) return text
        return Session().process(text, holdIncompleteTail = false)
    }

    class Session {
        private val lock = Any()
        private var inRemoteFgColor = false
        private var inAnsiString = false
        private var pendingEsc = ""

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
        }

        internal fun flushAndReset(): String = synchronized(lock) {
            val out = pendingEsc
            pendingEsc = ""
            inRemoteFgColor = false
            inAnsiString = false
            out
        }

        internal fun peekPending(): String = synchronized(lock) { pendingEsc }

        internal fun process(chunk: String, holdIncompleteTail: Boolean): String {
            var text = if (pendingEsc.isNotEmpty()) {
                val merged = pendingEsc + chunk
                pendingEsc = ""
                merged
            } else {
                chunk
            }
            if (text.isEmpty()) return text

            var prefixRaw = ""
            if (inAnsiString) {
                val termEnd = findAnsiStringTerminatorEnd(text)
                if (termEnd < 0) {
                    if (holdIncompleteTail && text.endsWith("\u001B")) {
                        pendingEsc = "\u001B"
                        return text.substring(0, text.length - 1)
                    }
                    return text
                }
                prefixRaw = text.substring(0, termEnd)
                inAnsiString = false
                text = text.substring(termEnd)
                if (text.isEmpty()) return prefixRaw
            }

            if ('\u001B' !in text) {
                val rest = if (inRemoteFgColor) text else colorizePlain(text)
                return if (prefixRaw.isEmpty()) rest else prefixRaw + rest
            }

            val out = StringBuilder(prefixRaw.length + text.length + 32)
            if (prefixRaw.isNotEmpty()) out.append(prefixRaw)
            var changed = prefixRaw.isNotEmpty()
            var lastEnd = 0

            for (match in ANSI_TOKEN_RE.findAll(text)) {
                val plain = text.substring(lastEnd, match.range.first)
                if (plain.isNotEmpty()) {
                    if (inRemoteFgColor) {
                        out.append(plain)
                    } else {
                        val replaced = colorizePlain(plain)
                        if (replaced !== plain) changed = true
                        out.append(replaced)
                    }
                }
                val seq = match.value
                out.append(seq)
                inRemoteFgColor = updateFgColorState(inRemoteFgColor, seq)
                lastEnd = match.range.last + 1
            }

            if (lastEnd < text.length) {
                val escIdx = if (holdIncompleteTail) text.indexOf('\u001B', lastEnd) else -1
                if (escIdx >= 0 && isIncompleteAnsiPrefix(text, escIdx)) {
                    if (escIdx > lastEnd) {
                        val beforeEsc = text.substring(lastEnd, escIdx)
                        if (inRemoteFgColor) {
                            out.append(beforeEsc)
                        } else {
                            out.append(colorizePlain(beforeEsc))
                        }
                    }
                    if (text.length - escIdx <= MAX_PENDING_ESC) {
                        pendingEsc = text.substring(escIdx)
                        changed = true
                    } else if (isStringIntroducer(text, escIdx)) {
                        inAnsiString = true
                        if (text.endsWith("\u001B")) {
                            out.append(text, escIdx, text.length - 1)
                            pendingEsc = "\u001B"
                            changed = true
                        } else {
                            out.append(text, escIdx, text.length)
                        }
                    } else {
                        out.append(text, escIdx, text.length)
                    }
                } else {
                    val tail = text.substring(lastEnd)
                    if (inRemoteFgColor) {
                        out.append(tail)
                    } else {
                        val replaced = colorizePlain(tail)
                        if (replaced !== tail) changed = true
                        out.append(replaced)
                    }
                }
            }
            return if (changed) out.toString() else text
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

    private fun colorizePlain(segment: String): String {
        if (!mightMatch(segment) || !HIGHLIGHT_RE.containsMatchIn(segment)) return segment
        return HIGHLIGHT_RE.replace(segment) { m -> "${pickColor(m.value)}${m.value}$FG_RESET" }
    }

    private fun mightMatch(s: String): Boolean {
        val n = s.length
        if (n < 2) return false
        for (i in 0 until n - 1) {
            val c0 = s[i]
            if (c0 == '.' && i > 0 && s[i - 1] in '0'..'9' && s[i + 1] in '0'..'9') return true
            if (i == 0 || !isWordChar(s[i - 1])) {
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

    private fun updateFgColorState(current: Boolean, seq: String): Boolean {
        val len = seq.length
        if (len < 3 || seq[0] != '\u001B' || seq[1] != '[' || seq[len - 1] != 'm') return current
        if (len == 3) return false
        val first = seq[2]
        if (first == '?' || first == '>' || first == '=' || first == '!') return current

        var fg = current
        var ext = 0 // 1 = awaiting mode for 38, 2 = awaiting mode for 48
        var skip = 0
        var pos = 2
        val end = len - 1
        while (true) {
            var sep = pos
            var colonIdx = -1
            while (sep < end) {
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
            if (sep >= end) break
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

    private fun pickColor(word: String): String {
        val lower = word.lowercase()
        if (IPV4_RE.matches(lower)) return CYAN
        if (lower == "active (running)" || lower == "ok" || lower == "success" ||
            lower == "succeeded" || lower == "passed" || lower == "healthy"
        ) return GREEN
        if (lower == "warn" || lower == "warning" || lower == "deprecated" ||
            lower == "timeout" || lower == "retry"
        ) return AMBER
        return RED
    }
}
