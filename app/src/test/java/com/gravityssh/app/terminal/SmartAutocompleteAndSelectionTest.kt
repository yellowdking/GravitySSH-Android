package com.gravityssh.app.terminal

import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.SFTPFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutocompleteAndSelectionTest {

    @Test
    fun shortcutsAreNeverIncludedInAutocomplete() {
        val shortcuts = listOf(
            CommandShortcut(id = "s1", name = "DockerQuick", command = "docker ps --format table"),
            CommandShortcut(id = "s2", name = "ComposeUp", command = "docker compose up -d")
        )
        val history = listOf(CommandRecord(id = "h1", command = "docker stats"))
        for (q in listOf("doc", "docker ", "docker c", "docker compose ")) {
            val res = AutocompleteEngine.suggest(q, shortcuts, history, AppSettings(), limit = 5)
            assertTrue("Expected suggestions for '$q'", res.isNotEmpty())
            assertTrue("Shortcuts must never appear for '$q'", res.none { it.source == "shortcut" })
        }
    }

    @Test
    fun remoteFilesAndFoldersHaveTopPriority() {
        val entries = listOf(
            SFTPFileEntry("sshsync", "/opt/sshsync", isDir = true),
            SFTPFileEntry("docker-compose.yml", "/opt/docker-compose.yml", isDir = false),
            SFTPFileEntry(".env", "/opt/.env", isDir = false)
        )
        val cdRes = AutocompleteEngine.suggest(
            input = "cd ",
            history = listOf(CommandRecord(id = "h1", command = "cd /var/log")),
            settings = AppSettings(),
            limit = 3,
            remoteEntries = entries,
            currentDir = "/opt"
        )
        assertEquals("sshsync/", cdRes.first().command)
        assertEquals("dir", cdRes.first().source)
        assertEquals("cd sshsync/", cdRes.first().insertText)
        assertTrue(cdRes.none { it.command == "docker-compose.yml" })

        val nanoRes = AutocompleteEngine.suggest(
            input = "nano d",
            history = listOf(CommandRecord(id = "h2", command = "nano default.conf")),
            settings = AppSettings(),
            limit = 3,
            remoteEntries = entries,
            currentDir = "/opt"
        )
        assertEquals("docker-compose.yml", nanoRes.first().command)
        assertEquals("file", nanoRes.first().source)
        assertEquals("nano docker-compose.yml", nanoRes.first().insertText)

        val pq = AutocompleteEngine.resolvePathQuery("cd /opt/ss", "/root")
        assertNotNull(pq)
        assertEquals(PathQuery("/opt", "ss", "/opt/", true), pq)
    }

    @Test
    fun dockerSubcommandAndComposeContextualCompletion() {
        val root = AutocompleteEngine.suggest("docker ", settings = AppSettings(), limit = 3)
        val rootCmds = root.map { it.command }
        assertTrue("Expected 'ps -a' in top 3: $rootCmds", "ps -a" in rootCmds)
        assertTrue("Expected 'compose' in top 3: $rootCmds", "compose" in rootCmds)
        assertTrue(root.all { it.source == "subcmd" })

        val dockerC = AutocompleteEngine.suggest("docker c", settings = AppSettings(), limit = 3)
        assertEquals("compose", dockerC.first().command)
        assertEquals("docker compose ", dockerC.first().insertText)
        assertEquals(CompletionEdit(0, "ompose "), AutocompleteEngine.computeCompletionInsert("docker c", "docker compose "))

        val comp = AutocompleteEngine.suggest("docker compose ", settings = AppSettings(), limit = 3)
        assertEquals(listOf("up -d", "down", "pull"), comp.map { it.command })
        assertEquals(CompletionEdit(0, "up -d"), AutocompleteEngine.computeCompletionInsert("docker compose ", "docker compose up -d"))

        val compU = AutocompleteEngine.suggest("docker compose u", settings = AppSettings(), limit = 3)
        assertEquals("up -d", compU.first().command)
        assertEquals("docker compose up -d", compU.first().insertText)
        assertEquals(CompletionEdit(0, "p -d"), AutocompleteEngine.computeCompletionInsert("docker compose u", "docker compose up -d"))
    }

    @Test
    fun webStyleWordSelectionAndMultiLineExtraction() {
        val ctrl = TerminalSessionController("sel1", initialCols = 40, initialRows = 6, keywordHighlightEnabled = { false })
        ctrl.onRemoteOutput("addr 107.172.98.169:54843 ok   \r\nsecond line   \r\nthird")
        val snap = ctrl.snapshot(0)

        val wordSel = TerminalSelectionHelper.selectWordAt(snap, row = 0, col = 12)
        assertEquals(CellPos(0, 5), wordSel.start)
        assertEquals(CellPos(0, 24), wordSel.end)
        assertEquals("107.172.98.169:54843", TerminalSelectionHelper.extractText(snap, wordSel))

        val multiSel = TextSelection(CellPos(0, 5), CellPos(1, 5))
        assertEquals("107.172.98.169:54843 ok\nsecond", TerminalSelectionHelper.extractText(snap, multiSel))

        val cw = 10f; val ch = 20f; val px = 12f; val py = 4f; val r = 30f
        assertEquals(SelectionHandle.START, TerminalSelectionHelper.hitTestHandle(px + 5 * cw - r * 0.25f, py + ch + r * 0.45f, wordSel, cw, ch, px, py, r))
        assertEquals(SelectionHandle.END, TerminalSelectionHelper.hitTestHandle(px + 25 * cw + r * 0.25f, py + ch + r * 0.45f, wordSel, cw, ch, px, py, r))
        assertEquals(SelectionHandle.NONE, TerminalSelectionHelper.hitTestHandle(390f, 150f, wordSel, cw, ch, px, py, r))
    }
}
