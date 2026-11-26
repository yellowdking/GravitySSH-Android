package com.gravityssh.app.terminal

internal object AnsiSgrHelper {
    fun apply(initial: TerminalStyle, params: List<Int>): TerminalStyle {
        var st = initial
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> st = TerminalStyle()
                1 -> st = st.copy(bold = true)
                2 -> st = st.copy(dim = true)
                4 -> st = st.copy(underline = true)
                7 -> st = st.copy(inverse = true)
                22 -> st = st.copy(bold = false, dim = false)
                24 -> st = st.copy(underline = false)
                27 -> st = st.copy(inverse = false)
                in 30..37 -> st = st.copy(fg = TerminalColors.ansi256(code - 30))
                39 -> st = st.copy(fg = TerminalColors.DEFAULT_FG)
                in 40..47 -> st = st.copy(bg = TerminalColors.ansi256(code - 40))
                49 -> st = st.copy(bg = TerminalColors.DEFAULT_BG)
                in 90..97 -> st = st.copy(fg = TerminalColors.ansi256(code - 90 + 8))
                in 100..107 -> st = st.copy(bg = TerminalColors.ansi256(code - 100 + 8))
                38, 48 -> {
                    val isFg = code == 38
                    val mode = params.getOrNull(i + 1)
                    if (mode == 5 && i + 2 < params.size) {
                        val color = TerminalColors.ansi256(params[i + 2])
                        st = if (isFg) st.copy(fg = color) else st.copy(bg = color)
                        i += 2
                    } else if (mode == 2 && i + 4 < params.size) {
                        val color = TerminalColors.rgb(params[i + 2], params[i + 3], params[i + 4])
                        st = if (isFg) st.copy(fg = color) else st.copy(bg = color)
                        i += 4
                    }
                }
            }
            i++
        }
        return st
    }
}
