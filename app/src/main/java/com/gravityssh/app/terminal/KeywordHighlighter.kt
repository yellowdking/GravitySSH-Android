package com.gravityssh.app.terminal

object KeywordHighlighter {
    private val ANSI_TOKEN_RE = Regex(
        "(\\u001B\\[[0-9;?]*[ -/]*[@-~]|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|\\u001B[@-Z\\\\-_])"
    )
    private val FAST_CHECK_RE = Regex("[efpwosad0-9]", RegexOption.IGNORE_CASE)
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
    private const val RESET = "\u001B[0m"

    fun highlight(text: String): String {
        if (text.isEmpty() || !FAST_CHECK_RE.containsMatchIn(text)) return text
        val out = StringBuilder(text.length + 32)
        var inColor = false
        var changed = false
        var lastEnd = 0

        for (match in ANSI_TOKEN_RE.findAll(text)) {
            val plain = text.substring(lastEnd, match.range.first)
            if (plain.isNotEmpty()) {
                if (inColor) {
                    out.append(plain)
                } else {
                    val replaced = colorizePlain(plain)
                    if (replaced !== plain) changed = true
                    out.append(replaced)
                }
            }
            val seq = match.value
            out.append(seq)
            if (isSgrReset(seq)) inColor = false
            else if (isSgrColorSet(seq)) inColor = true
            lastEnd = match.range.last + 1
        }

        if (lastEnd < text.length) {
            val tail = text.substring(lastEnd)
            if (inColor) {
                out.append(tail)
            } else {
                val replaced = colorizePlain(tail)
                if (replaced !== tail) changed = true
                out.append(replaced)
            }
        }
        return if (changed) out.toString() else text
    }

    private fun colorizePlain(segment: String): String {
        if (!HIGHLIGHT_RE.containsMatchIn(segment)) return segment
        return HIGHLIGHT_RE.replace(segment) { m -> "${pickColor(m.value)}${m.value}$RESET" }
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

    private fun isSgrReset(seq: String): Boolean =
        seq == "\u001B[0m" || seq == "\u001B[m" || seq == "\u001B[39m" || seq == "\u001B[0;39m"

    private fun isSgrColorSet(seq: String): Boolean =
        seq.startsWith("\u001B[") && seq.endsWith("m") && !isSgrReset(seq)
}
