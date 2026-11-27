package com.gravityssh.app.terminal

internal object AnsiSgrHelper {
    private const val EXT_NONE = 0
    private const val EXT_FG_MODE = 1
    private const val EXT_BG_MODE = 2
    private const val EXT_FG_256 = 3
    private const val EXT_BG_256 = 4
    private const val EXT_FG_R = 5
    private const val EXT_FG_G = 6
    private const val EXT_FG_B = 7
    private const val EXT_BG_R = 8
    private const val EXT_BG_G = 9
    private const val EXT_BG_B = 10

    fun apply(initial: TerminalStyle, raw: CharSequence): TerminalStyle {
        val len = raw.length
        if (len == 0) return TerminalStyle()

        var st = initial
        var ext = EXT_NONE
        var rgbR = 0
        var rgbG = 0
        var pos = 0

        while (true) {
            var sep = pos
            var hasColon = false
            while (sep < len) {
                val c = raw[sep]
                if (c == ';') break
                if (c == ':') hasColon = true
                sep++
            }

            if (hasColon) {
                ext = EXT_NONE
                st = applyColonSubparams(st, raw, pos, sep)
            } else {
                val code = parseNonNegativeInt(raw, pos, sep, emptyValue = 0)
                if (ext != EXT_NONE) {
                    val v = code.coerceAtLeast(0)
                    when (ext) {
                        EXT_FG_MODE -> ext = when (v) { 5 -> EXT_FG_256; 2 -> EXT_FG_R; else -> EXT_NONE }
                        EXT_BG_MODE -> ext = when (v) { 5 -> EXT_BG_256; 2 -> EXT_BG_R; else -> EXT_NONE }
                        EXT_FG_256 -> { st = st.copy(fg = TerminalColors.ansi256(v)); ext = EXT_NONE }
                        EXT_BG_256 -> { st = st.copy(bg = TerminalColors.ansi256(v)); ext = EXT_NONE }
                        EXT_FG_R -> { rgbR = v; ext = EXT_FG_G }
                        EXT_FG_G -> { rgbG = v; ext = EXT_FG_B }
                        EXT_FG_B -> { st = st.copy(fg = TerminalColors.rgb(rgbR, rgbG, v)); ext = EXT_NONE }
                        EXT_BG_R -> { rgbR = v; ext = EXT_BG_G }
                        EXT_BG_G -> { rgbG = v; ext = EXT_BG_B }
                        EXT_BG_B -> { st = st.copy(bg = TerminalColors.rgb(rgbR, rgbG, v)); ext = EXT_NONE }
                    }
                } else if (code >= 0) {
                    when (code) {
                        0 -> st = TerminalStyle()
                        1 -> st = st.copy(bold = true)
                        2 -> st = st.copy(dim = true)
                        4 -> st = st.copy(underline = true)
                        7 -> st = st.copy(inverse = true)
                        22 -> st = st.copy(bold = false, dim = false)
                        24 -> st = st.copy(underline = false)
                        27 -> st = st.copy(inverse = false)
                        in 30..37 -> st = st.copy(fg = TerminalColors.ansi256(code - 30))
                        38 -> ext = EXT_FG_MODE
                        39 -> st = st.copy(fg = TerminalColors.DEFAULT_FG)
                        in 40..47 -> st = st.copy(bg = TerminalColors.ansi256(code - 40))
                        48 -> ext = EXT_BG_MODE
                        49 -> st = st.copy(bg = TerminalColors.DEFAULT_BG)
                        in 90..97 -> st = st.copy(fg = TerminalColors.ansi256(code - 90 + 8))
                        in 100..107 -> st = st.copy(bg = TerminalColors.ansi256(code - 100 + 8))
                    }
                }
            }

            if (sep >= len) break
            pos = sep + 1
        }
        return st
    }

    private fun applyColonSubparams(initial: TerminalStyle, raw: CharSequence, start: Int, end: Int): TerminalStyle {
        var s0 = -1; var s1 = -1; var s2 = -1; var s3 = -1; var s4 = -1; var s5 = -1
        var count = 0
        var p = start
        while (true) {
            var c = p
            while (c < end && raw[c] != ':') c++
            val v = parseNonNegativeInt(raw, p, c, emptyValue = -1)
            when (count) {
                0 -> s0 = v
                1 -> s1 = v
                2 -> s2 = v
                3 -> s3 = v
                4 -> s4 = v
                5 -> s5 = v
            }
            count++
            if (c >= end) break
            p = c + 1
        }

        if ((s0 == 38 || s0 == 48) && count >= 3) {
            val isFg = s0 == 38
            if (s1 == 5 && s2 >= 0) {
                val color = TerminalColors.ansi256(s2)
                return if (isFg) initial.copy(fg = color) else initial.copy(bg = color)
            }
            if (s1 == 2) {
                // ITU T.416 form: 38:2:colorspace:r:g:b (or 38:2::r:g:b)
                if (count >= 6 && s3 >= 0 && s4 >= 0 && s5 >= 0) {
                    val color = TerminalColors.rgb(s3, s4, s5)
                    return if (isFg) initial.copy(fg = color) else initial.copy(bg = color)
                }
                // Short colon form: 38:2:r:g:b
                if (count >= 5 && s2 >= 0 && s3 >= 0 && s4 >= 0) {
                    val color = TerminalColors.rgb(s2, s3, s4)
                    return if (isFg) initial.copy(fg = color) else initial.copy(bg = color)
                }
            }
        } else if (s0 == 4 && count >= 2) {
            return initial.copy(underline = s1 > 0)
        }
        return initial
    }

    private fun parseNonNegativeInt(raw: CharSequence, start: Int, end: Int, emptyValue: Int): Int {
        if (start >= end) return emptyValue
        var value = 0
        for (i in start until end) {
            val d = raw[i] - '0'
            if (d !in 0..9) return -2
            value = (value * 10 + d).coerceAtMost(999_999)
        }
        return value
    }
}
