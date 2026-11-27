package com.gravityssh.app.terminal

import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.ssh.HostMetricsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAndGuardTest {

    @Test
    fun terminalBufferAndAnsiParser() {
        val ctrl = TerminalSessionController("s1", initialCols = 40, initialRows = 10, keywordHighlightEnabled = { false })
        ctrl.onRemoteOutput("Hello \u001B[31mWorld\u001B[0m\r\nLine2")
        val snap = ctrl.snapshot()
        assertEquals('H', snap.lines[0][0].ch)
        assertEquals('W', snap.lines[0][6].ch)
        assertEquals(TerminalColors.ansi256(1), snap.lines[0][6].fg)
        assertEquals("Hello World\nLine2", ctrl.extractPlainText())

        // Alternate screen & bracketed paste
        ctrl.onRemoteOutput("\u001B[?1049h\u001B[?2004hAltScreen")
        assertTrue(ctrl.snapshot().altScreen)
        assertTrue(ctrl.bracketedPaste.value)
        assertEquals("AltScreen", ctrl.extractPlainText())

        ctrl.onRemoteOutput("\u001B[?1049l\u001B[?2004l")
        assertFalse(ctrl.snapshot().altScreen)
        assertEquals("Hello World\nLine2", ctrl.extractPlainText())
    }

    @Test
    fun wideCjkCellsWrapOverwriteAndSelection() {
        assertEquals(2, charWidth('中'))
        assertEquals(2, charWidth('あ'))
        assertEquals(2, charWidth('한'))
        assertEquals(2, charWidth('Ａ'))
        assertEquals(2, charWidth('。'))
        assertEquals(1, charWidth('A'))
        assertEquals(1, charWidth('─'))

        // 10-col terminal: write 9 ASCII + 1 wide char -> wraps wide char cleanly to row 1 col 0
        val ctrl = TerminalSessionController("wide1", initialCols = 10, initialRows = 5, keywordHighlightEnabled = { false })
        ctrl.onRemoteOutput("123456789中文A")
        val snap = ctrl.snapshot()
        assertEquals('9', snap.lines[0][8].ch)
        assertEquals(' ', snap.lines[0][9].ch)
        assertEquals('中', snap.lines[1][0].ch)
        assertTrue(snap.lines[1][0].isWide)
        assertFalse(snap.lines[1][0].isContinuation)
        assertEquals('\u0000', snap.lines[1][1].ch)
        assertTrue(snap.lines[1][1].isContinuation)
        assertEquals('文', snap.lines[1][2].ch)
        assertTrue(snap.lines[1][2].isWide)
        assertTrue(snap.lines[1][3].isContinuation)
        assertEquals('A', snap.lines[1][4].ch)
        assertEquals("123456789\n中文A", ctrl.extractPlainText())

        // Selection on wide continuation cell snaps to lead and extracts without NULs
        val wordSel = TerminalSelectionHelper.selectWordAt(snap, row = 1, col = 1)
        assertEquals(CellPos(1, 0), wordSel.start)
        assertEquals(CellPos(1, 4), wordSel.end)
        assertEquals("中文A", TerminalSelectionHelper.extractText(snap, wordSel))

        // Overwriting continuation cell blanks its paired lead cell
        ctrl.onRemoteOutput("\u001B[2;2HX")
        val snap2 = ctrl.snapshot()
        assertEquals(' ', snap2.lines[1][0].ch)
        assertFalse(snap2.lines[1][0].isWide)
        assertEquals('X', snap2.lines[1][1].ch)

        // Overwriting wide lead cell blanks its paired continuation cell
        ctrl.onRemoteOutput("\u001B[2;3HY")
        val snap3 = ctrl.snapshot()
        assertEquals('Y', snap3.lines[1][2].ch)
        assertEquals(' ', snap3.lines[1][3].ch)
        assertFalse(snap3.lines[1][3].isContinuation)
    }

    @Test
    fun vt100RepColonSgrDecGraphicsDcsDsrAndOsc7() {
        val replies = mutableListOf<String>()
        val cwds = mutableListOf<String>()
        val buf = TerminalBuffer(40, 10)
        val parser = AnsiParser(buf, onCwdChanged = { cwds.add(it) }, onReply = { replies.add(it) })

        // CSI b (REP), DEC Special Graphics, DCS/APC skip
        parser.feed("A\u001B[4b \u001B(0lqk\u001B(B \u001BP+q544e\u001B\\ok\u001B_hidden\u001B\\!")
        assertEquals("AAAAA ┌─┐ ok!", buf.extractPlainText())

        // Colon SGR (38:2:r:g:b, 38:2::r:g:b, 48:5:idx) without resetting bold
        parser.feed("\r\n\u001B[1;38:2:255:128:64mR\u001B[38:2::10:20:30;48:5:196mG\u001B[0m")
        val snap = buf.snapshot()
        assertTrue(snap.lines[1][0].bold)
        assertEquals(TerminalColors.rgb(255, 128, 64), snap.lines[1][0].fg)
        assertTrue(snap.lines[1][1].bold)
        assertEquals(TerminalColors.rgb(10, 20, 30), snap.lines[1][1].fg)
        assertEquals(TerminalColors.ansi256(196), snap.lines[1][1].bg)

        // DSR (CSI 6n) & DA1 (CSI c)
        parser.feed("\u001B[3;5H\u001B[6n\u001B[c")
        assertEquals(listOf("\u001B[3;5R", "\u001B[?62;c"), replies)

        // OSC 7 root "/" and %XX UTF-8 decoding
        parser.feed("\u001B]7;file://srv/\u0007")
        parser.feed("\u001B]7;file://srv/home/my%20dir/%E4%B8%AD%E6%96%87/c++\u001B\\")
        assertEquals(listOf("/", "/home/my dir/中文/c++"), cwds)

        // BCE on eraseDisplay & main saved cursor preservation across alt-screen
        parser.feed("\u001B[2;4H\u001B7\u001B[?1049h\u001B[44m\u001B[2J\u001B[1;1H\u001B7\u001B[?1049l\u001B8")
        assertEquals(1 to 3, buf.cursorPosition())
        parser.feed("\u001B[42m\u001B[2J")
        assertEquals(TerminalColors.ansi256(2), buf.snapshot().lines[0][0].bg)
    }

    @Test
    fun pasteGuardDetectsRisks() {
        val danger = PasteGuard.inspect("rm -rf /tmp && rm -rf /", bracketedPasteActive = true)
        assertTrue(danger.needsConfirm)
        assertTrue(danger.warnings.any { it.kind == PasteWarningKind.DANGER })

        // Variants: rm -fr, rm -f -r, chmod 777 -R, chmod -R 0777
        assertTrue(PasteGuard.inspect("rm -fr /", true).warnings.any { it.kind == PasteWarningKind.DANGER })
        assertTrue(PasteGuard.inspect("rm -f -r /", true).warnings.any { it.kind == PasteWarningKind.DANGER })
        assertTrue(PasteGuard.inspect("chmod 777 -R /", true).warnings.any { it.kind == PasteWarningKind.DANGER })
        assertTrue(PasteGuard.inspect("chmod -R 0777 /", true).warnings.any { it.kind == PasteWarningKind.DANGER })
        assertFalse(PasteGuard.inspect("rm -rf /tmp/build", true).needsConfirm)

        val hidden = PasteGuard.inspect("echo \u200Bhello", bracketedPasteActive = true)
        assertTrue(hidden.hasHiddenChars)
        assertEquals("echo hello", PasteGuard.stripHiddenChars("echo \u200Bhello"))

        val pipe = PasteGuard.inspect("curl -fsSL https://example.com/a.sh | sudo bash", bracketedPasteActive = true)
        assertTrue(pipe.warnings.any { it.kind == PasteWarningKind.PIPE_SHELL })

        val multi = PasteGuard.inspect("ls\npwd", bracketedPasteActive = false)
        assertTrue(multi.warnings.any { it.kind == PasteWarningKind.MULTILINE })
        assertFalse(PasteGuard.inspect("ls\npwd", bracketedPasteActive = true).needsConfirm)

        // Bare \r multiline and trailing newline when !bracketedPasteActive
        assertTrue(PasteGuard.inspect("ls\rpwd", bracketedPasteActive = false).warnings.any { it.kind == PasteWarningKind.MULTILINE })
        assertTrue(PasteGuard.inspect("ls -la\n", bracketedPasteActive = false).warnings.any { it.kind == PasteWarningKind.MULTILINE })
        assertTrue(PasteGuard.inspect("ls -la\r", bracketedPasteActive = false).warnings.any { it.kind == PasteWarningKind.MULTILINE })
        assertFalse(PasteGuard.inspect("ls -la\n", bracketedPasteActive = true).needsConfirm)
    }

    @Test
    fun keywordHighlighterAndAutocomplete() {
        val hl = KeywordHighlighter.highlight("ERROR failed on 10.0.0.1 OK")
        assertTrue(hl.contains("\u001B[38;5;203mERROR\u001B[39m"))
        assertTrue(hl.contains("\u001B[38;5;117m10.0.0.1\u001B[39m"))
        assertTrue(hl.contains("\u001B[38;5;114mOK\u001B[39m"))

        // Stateful session preserves split ANSI sequence across chunks and remote color state
        val session = KeywordHighlighter.Session()
        val p1 = session.highlight("prefix \u001B[31")
        val p2 = session.highlight("mERROR\u001B[0m OK")
        assertEquals("prefix ", p1)
        assertEquals("\u001B[31mERROR\u001B[0m \u001B[38;5;114mOK\u001B[39m", p2)

        val suggestions = AutocompleteEngine.suggest(
            input = "doc",
            shortcuts = listOf(CommandShortcut(id = "1", name = "MyDocker", command = "docker ps --format table")),
            history = listOf(CommandRecord(id = "h1", command = "docker logs -f app")),
            settings = AppSettings()
        )
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.none { it.source == "shortcut" })
        assertTrue(suggestions.first().insertText.startsWith("docker"))

        val edit = AutocompleteEngine.computeCompletionInsert("doc", "docker ps")
        assertEquals(0, edit.backspaces)
        assertEquals("ker ps", edit.insertText)

        // Semver comparison including V-prefix and prerelease
        assertEquals(0, UpdateChecker.compareSemver("v1.0.6", "V1.0.6"))
        assertTrue(UpdateChecker.compareSemver("1.0.6-rc1", "1.0.6") < 0)
        assertTrue(UpdateChecker.compareSemver("1.0.6", "1.0.6-rc1") > 0)
        assertTrue(UpdateChecker.compareSemver("1.0.7-rc1", "1.0.6") > 0)
    }

    @Test
    fun hostMetricsParserParsesProcOutput() {
        val raw = """
            ---CPU---
            cpu  100 0 100 800 0 0 0 0
            ---MEM---
            MemTotal: 2048000
            MemAvailable: 1024000
            ---DISK---
            42% /
            ---NET---
            102400 204800
            ---LOAD---
            0.75 0.50 0.25 1/120 1234
            4
            ---GPU---
            35, NVIDIA L4
        """.trimIndent()

        val (m1, cpu1, net1) = HostMetricsSampler.parseOutput("s1", raw, 18L, null, null, 0L, 10_000L)
        assertTrue(m1.supported)
        assertEquals(18L, m1.latencyMs)
        assertEquals(2000L, m1.memTotalMb)
        assertEquals(1000L, m1.memUsedMb)
        assertEquals(42.0, m1.diskPercent, 0.01)
        assertEquals(4, m1.cores)
        assertTrue(m1.hasGpu)
        assertEquals("NVIDIA L4", m1.gpuName)

        val raw2 = raw.replace("100 0 100 800", "200 0 150 850").replace("102400 204800", "204800 409600")
        val (m2, _, _) = HostMetricsSampler.parseOutput("s1", raw2, 20L, cpu1, net1, 10_000L, 12_000L)
        assertEquals(75.0, m2.cpuPercent, 0.5)
        assertEquals(50.0, m2.netRxKbps, 0.5)
        assertEquals(100.0, m2.netTxKbps, 0.5)
    }

    @Test
    fun longPressRowAndScreenCopyExtraction() {
        val ctrl = TerminalSessionController("s2", initialCols = 40, initialRows = 6, keywordHighlightEnabled = { false })
        ctrl.onRemoteOutput("\u001B[32mroot@srv\u001B[0m:~# ls -la   \r\napp.log\r\n\u001B[33mWARN\u001B[0m disk 90%")

        val snap = ctrl.snapshot(0)
        fun rowText(row: Int): String =
            snap.lines.getOrNull(row)?.let { cells -> String(CharArray(cells.size) { cells[it].ch }).trimEnd() }.orEmpty()

        assertEquals("root@srv:~# ls -la", rowText(0))
        assertEquals("app.log", rowText(1))
        assertEquals("WARN disk 90%", rowText(2))
        assertEquals("", rowText(3))
        assertEquals("", rowText(99))
        assertEquals("root@srv:~# ls -la\napp.log\nWARN disk 90%", ctrl.extractPlainText(0))
    }
}
