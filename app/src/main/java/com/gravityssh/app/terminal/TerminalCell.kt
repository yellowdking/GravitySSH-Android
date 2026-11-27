package com.gravityssh.app.terminal

fun charWidth(ch: Char): Int {
    val c = ch.code
    if (c < 0x1100) return 1
    return when {
        c in 0x1100..0x115F -> 2 // Hangul Jamo init. consonants
        c == 0x2329 || c == 0x232A -> 2 // Angle brackets
        c in 0x2E80..0x303F -> 2 // CJK Radicals, Ideographic Desc, CJK Punctuation
        c in 0x3040..0x33FF -> 2 // Hiragana, Katakana, Bopomofo, Enclosed CJK, CJK Compat
        c in 0x3400..0x4DBF -> 2 // CJK Unified Ideographs Ext A
        c in 0x4E00..0x9FFF -> 2 // CJK Unified Ideographs
        c in 0xA000..0xA4CF -> 2 // Yi Syllables & Radicals
        c in 0xA960..0xA97F -> 2 // Hangul Jamo Extended-A
        c in 0xAC00..0xD7A3 -> 2 // Hangul Syllables
        c in 0xF900..0xFAFF -> 2 // CJK Compatibility Ideographs
        c in 0xFE10..0xFE19 -> 2 // Vertical Forms
        c in 0xFE30..0xFE6F -> 2 // CJK Compatibility Forms
        c in 0xFF01..0xFF60 -> 2 // Fullwidth Forms
        c in 0xFFE0..0xFFE6 -> 2 // Fullwidth Symbol Variants
        else -> 1
    }
}

object TerminalColors {
    const val DEFAULT_FG: Int = 0xFFE6EAF5.toInt()
    const val DEFAULT_BG: Int = 0xFF0B0D12.toInt()

    private val ANSI_16 = intArrayOf(
        0xFF1A202E.toInt(), // 0 black
        0xFFF87171.toInt(), // 1 red
        0xFF4ADE80.toInt(), // 2 green
        0xFFFBBF24.toInt(), // 3 yellow
        0xFF60A5FA.toInt(), // 4 blue
        0xFFC084FC.toInt(), // 5 magenta
        0xFF38BDF8.toInt(), // 6 cyan
        0xFFE6EAF5.toInt(), // 7 white
        0xFF64748B.toInt(), // 8 bright black
        0xFFFCA5A5.toInt(), // 9 bright red
        0xFF86EFAC.toInt(), // 10 bright green
        0xFFFDE047.toInt(), // 11 bright yellow
        0xFF93C5FD.toInt(), // 12 bright blue
        0xFFD8B4FE.toInt(), // 13 bright magenta
        0xFF7DD3FC.toInt(), // 14 bright cyan
        0xFFFFFFFF.toInt()  // 15 bright white
    )

    fun ansi256(index: Int): Int {
        val idx = index.coerceIn(0, 255)
        if (idx < 16) return ANSI_16[idx]
        if (idx < 232) {
            val c = idx - 16
            val r = cubeValue(c / 36)
            val g = cubeValue((c / 6) % 6)
            val b = cubeValue(c % 6)
            return rgb(r, g, b)
        }
        val gray = 8 + (idx - 232) * 10
        return rgb(gray, gray, gray)
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun cubeValue(n: Int): Int = if (n == 0) 0 else 55 + n * 40
}

data class TerminalStyle(
    val fg: Int = TerminalColors.DEFAULT_FG,
    val bg: Int = TerminalColors.DEFAULT_BG,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false
)

data class TerminalCell(
    val ch: Char = ' ',
    val fg: Int = TerminalColors.DEFAULT_FG,
    val bg: Int = TerminalColors.DEFAULT_BG,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val isWide: Boolean = false,
    val isContinuation: Boolean = false
) {
    companion object {
        val EMPTY = TerminalCell()

        fun blank(style: TerminalStyle): TerminalCell =
            if (style.fg == TerminalColors.DEFAULT_FG && style.bg == TerminalColors.DEFAULT_BG) {
                EMPTY
            } else {
                TerminalCell(
                    ch = ' ',
                    fg = style.fg,
                    bg = style.bg,
                    bold = false,
                    dim = false,
                    underline = false,
                    inverse = false
                )
            }

        fun continuation(style: TerminalStyle): TerminalCell = TerminalCell(
            ch = '\u0000',
            fg = style.fg,
            bg = style.bg,
            bold = style.bold,
            dim = style.dim,
            underline = style.underline,
            inverse = style.inverse,
            isWide = false,
            isContinuation = true
        )
    }
}

data class TerminalSnapshot(
    val cols: Int,
    val rows: Int,
    val cursorCol: Int,
    val cursorRow: Int,
    val cursorVisible: Boolean,
    val lines: List<Array<TerminalCell>>,
    val scrollbackSize: Int,
    val scrollOffset: Int,
    val altScreen: Boolean,
    val bracketedPaste: Boolean,
    val version: Long
)
