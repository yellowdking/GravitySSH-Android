package com.gravityssh.app.terminal

fun charWidth(ch: Char): Int = codePointWidth(ch.code)

fun isEmojiSkinToneModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

fun codePointWidth(cp: Int): Int {
    if (cp <= 0) return 0
    if (cp < 0x20 || cp in 0x7F..0x9F) return 0
    if (cp < 0x0300) return 1

    // Zero-width combining marks, variation selectors, ZWJ/ZWNJ, Hangul Jamo medial/trailing
    if (cp in 0x0300..0x036F ||
        cp in 0x1160..0x11FF ||
        cp in 0x200B..0x200F ||
        cp in 0x2028..0x202E ||
        cp in 0x2060..0x206F ||
        cp in 0xD7B0..0xD7FF ||
        cp in 0xFE00..0xFE0F ||
        cp == 0xFEFF ||
        cp in 0xE0100..0xE01EF
    ) {
        return 0
    }
    if (cp != 0x00AD) {
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
        ) {
            return 0
        }
    }

    if (cp < 0x1100) return 1
    return when {
        // East Asian Wide / Fullwidth BMP
        cp in 0x1100..0x115F -> 2 // Hangul Jamo init. consonants
        cp == 0x2329 || cp == 0x232A -> 2 // Angle brackets
        cp in 0x2E80..0x303F -> 2 // CJK Radicals, Ideographic Desc, CJK Punctuation
        cp in 0x3040..0x33FF -> 2 // Hiragana, Katakana, Bopomofo, Enclosed CJK, CJK Compat
        cp in 0x3400..0x4DBF -> 2 // CJK Unified Ideographs Ext A
        cp in 0x4E00..0x9FFF -> 2 // CJK Unified Ideographs
        cp in 0xA000..0xA4CF -> 2 // Yi Syllables & Radicals
        cp in 0xA960..0xA97F -> 2 // Hangul Jamo Extended-A
        cp in 0xAC00..0xD7A3 -> 2 // Hangul Syllables
        cp in 0xF900..0xFAFF -> 2 // CJK Compatibility Ideographs
        cp in 0xFE10..0xFE19 -> 2 // Vertical Forms
        cp in 0xFE30..0xFE6F -> 2 // CJK Compatibility Forms
        cp in 0xFF01..0xFF60 -> 2 // Fullwidth Forms
        cp in 0xFFE0..0xFFE6 -> 2 // Fullwidth Symbol Variants

        // Wide BMP Emoji (Unicode Emoji_Presentation=Yes)
        cp in 0x231A..0x231B -> 2
        cp in 0x23E9..0x23EC || cp == 0x23F0 || cp == 0x23F3 -> 2
        cp in 0x25FD..0x25FE -> 2
        cp in 0x2614..0x2615 || cp in 0x2648..0x2653 -> 2
        cp == 0x267F || cp == 0x2693 || cp == 0x26A1 || cp in 0x26AA..0x26AB -> 2
        cp in 0x26BD..0x26BE || cp in 0x26C4..0x26C5 || cp == 0x26CE || cp == 0x26D4 -> 2
        cp == 0x26EA || cp in 0x26F2..0x26F3 || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD -> 2
        cp == 0x2705 || cp in 0x270A..0x270B || cp == 0x2728 || cp == 0x274C || cp == 0x274E -> 2
        cp in 0x2753..0x2755 || cp == 0x2757 || cp in 0x2795..0x2797 || cp == 0x27B0 || cp == 0x27BF -> 2
        cp in 0x2B1B..0x2B1C || cp == 0x2B50 || cp == 0x2B55 -> 2

        // Supplementary Plane Wide (Emoji, Pictographs, Ideographic, CJK Ext B..I)
        cp in 0x16FE0..0x16FFF -> 2
        cp in 0x17000..0x187F7 || cp in 0x18800..0x18CD5 || cp in 0x18D00..0x18D08 -> 2
        cp in 0x1AFF0..0x1B2FF -> 2
        cp == 0x1F004 || cp == 0x1F0CF -> 2
        cp == 0x1F18E || cp in 0x1F191..0x1F19A || cp in 0x1F1E6..0x1F1FF -> 2
        cp in 0x1F200..0x1F2FF -> 2
        cp in 0x1F300..0x1F64F -> 2
        cp in 0x1F680..0x1F6FF -> 2
        cp in 0x1F7E0..0x1F7EB || cp == 0x1F7F0 -> 2
        cp in 0x1F900..0x1F9FF -> 2
        cp in 0x1FA70..0x1FAFF -> 2
        cp in 0x20000..0x2FFFD -> 2
        cp in 0x30000..0x3FFFD -> 2
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
    val codePoint: Int = ' '.code,
    val fg: Int = TerminalColors.DEFAULT_FG,
    val bg: Int = TerminalColors.DEFAULT_BG,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val isWide: Boolean = false,
    val isContinuation: Boolean = false,
    val combining: String? = null
) {
    val ch: Char
        get() = if (codePoint in 0..0xFFFF) codePoint.toChar() else '\uFFFD'

    val isBlank: Boolean
        get() = !isContinuation && (codePoint == ' '.code || codePoint == 0) && combining.isNullOrEmpty()

    fun appendTo(sb: StringBuilder) {
        if (isContinuation) return
        if (codePoint == 0) {
            sb.append(' ')
        } else {
            sb.appendCodePoint(codePoint)
        }
        if (!combining.isNullOrEmpty()) {
            sb.append(combining)
        }
    }

    fun toDisplayString(): String {
        if (isContinuation) return ""
        if (codePoint == 0) return " "
        if (codePoint <= 0xFFFF && combining.isNullOrEmpty()) return codePoint.toChar().toString()
        val sb = StringBuilder(4)
        sb.appendCodePoint(codePoint)
        if (!combining.isNullOrEmpty()) sb.append(combining)
        return sb.toString()
    }

    companion object {
        val EMPTY = TerminalCell()

        fun blank(style: TerminalStyle): TerminalCell =
            if (style.fg == TerminalColors.DEFAULT_FG && style.bg == TerminalColors.DEFAULT_BG) {
                EMPTY
            } else {
                TerminalCell(
                    codePoint = ' '.code,
                    fg = style.fg,
                    bg = style.bg,
                    bold = false,
                    dim = false,
                    underline = false,
                    inverse = false
                )
            }

        fun continuation(style: TerminalStyle): TerminalCell = TerminalCell(
            codePoint = 0,
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
