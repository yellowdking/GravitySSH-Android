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
    fun pasteGuardDetectsRisks() {
        val danger = PasteGuard.inspect("rm -rf /tmp && rm -rf /", bracketedPasteActive = true)
        assertTrue(danger.needsConfirm)
        assertTrue(danger.warnings.any { it.kind == PasteWarningKind.DANGER })

        val hidden = PasteGuard.inspect("echo \u200Bhello", bracketedPasteActive = true)
        assertTrue(hidden.hasHiddenChars)
        assertEquals("echo hello", PasteGuard.stripHiddenChars("echo \u200Bhello"))

        val pipe = PasteGuard.inspect("curl -fsSL https://example.com/a.sh | sudo bash", bracketedPasteActive = true)
        assertTrue(pipe.warnings.any { it.kind == PasteWarningKind.PIPE_SHELL })

        val multi = PasteGuard.inspect("ls\npwd", bracketedPasteActive = false)
        assertTrue(multi.warnings.any { it.kind == PasteWarningKind.MULTILINE })
        assertFalse(PasteGuard.inspect("ls\npwd", bracketedPasteActive = true).needsConfirm)
    }

    @Test
    fun keywordHighlighterAndAutocomplete() {
        val hl = KeywordHighlighter.highlight("ERROR failed on 10.0.0.1 OK")
        assertTrue(hl.contains("\u001B[38;5;203mERROR\u001B[0m"))
        assertTrue(hl.contains("\u001B[38;5;117m10.0.0.1\u001B[0m"))
        assertTrue(hl.contains("\u001B[38;5;114mOK\u001B[0m"))

        val suggestions = AutocompleteEngine.suggest(
            input = "doc",
            shortcuts = listOf(CommandShortcut(id = "1", name = "MyDocker", command = "docker ps --format table")),
            history = listOf(CommandRecord(id = "h1", command = "docker logs -f app")),
            settings = AppSettings()
        )
        assertTrue(suggestions.isNotEmpty())
        assertEquals("shortcut", suggestions.first().source)

        val edit = AutocompleteEngine.computeCompletionInsert("doc", "docker ps")
        assertEquals(0, edit.backspaces)
        assertEquals("ker ps", edit.insertText)
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
