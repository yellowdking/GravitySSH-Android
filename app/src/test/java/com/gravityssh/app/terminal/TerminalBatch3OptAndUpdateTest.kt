package com.gravityssh.app.terminal

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TerminalBatch3OptAndUpdateTest {

    @Test
    fun terminalBufferDirtyRowCowSnapshotsReuseCleanRowsAndIsolateEdits() {
        val buf = TerminalBuffer(initialCols = 20, initialRows = 5)
        val parser = AnsiParser(buf)
        parser.feed("Row0\r\nRow1\r\nRow2\r\nRow3")

        val snap1 = buf.snapshot()
        val snap2 = buf.snapshot()
        // Zero mutations between frames -> every row reuses its cached immutable snapshot array
        for (r in 0 until 5) {
            assertSame("Clean row $r should reuse cached snapshot", snap1.lines[r], snap2.lines[r])
        }

        // Mutate ONLY row 1 -> row 1 gets a fresh snapshot array without mutating snap1.lines[1], clean rows stay shared
        parser.feed("\u001B[2;1HEdited1")
        val snap3 = buf.snapshot()
        assertSame(snap1.lines[0], snap3.lines[0])
        assertNotSame(snap1.lines[1], snap3.lines[1])
        assertSame(snap1.lines[2], snap3.lines[2])
        assertSame(snap1.lines[3], snap3.lines[3])
        assertSame(snap1.lines[4], snap3.lines[4])
        assertEquals("Row1", rowText(snap1.lines[1]))
        assertEquals("Edited1", rowText(snap3.lines[1]))

        // Scroll up shifts cached snapshots cleanly without invalidating shifted clean rows
        parser.feed("\u001B[5;1HRow4\r\nRow5")
        val snap4 = buf.snapshot()
        assertSame("Shifted row 0 should reuse previous row 1 snapshot", snap3.lines[1], snap4.lines[0])
        assertSame("Shifted row 1 should reuse previous row 2 snapshot", snap3.lines[2], snap4.lines[1])
        assertEquals("Edited1", rowText(snap4.lines[0]))
        assertEquals("Row5", rowText(snap4.lines[4]))
        // Scrollback holds immutable Row0
        val scrolled = buf.snapshot(scrollOffset = 1)
        assertEquals("Row0", rowText(scrolled.lines[0]))
        assertSame(snap4.lines[0], scrolled.lines[1])

        // Erase line invalidates only cursorRow
        parser.feed("\u001B[2;1H\u001B[2K")
        val snap5 = buf.snapshot()
        assertSame(snap4.lines[0], snap5.lines[0])
        assertNotSame(snap4.lines[1], snap5.lines[1])
        assertEquals("Row2", rowText(snap4.lines[1]))
        assertEquals("", rowText(snap5.lines[1]))

        // Alternate screen maintains independent snapshot cache and restores clean main rows on exit
        parser.feed("\u001B[?1049hAltLine")
        val altSnap = buf.snapshot()
        assertTrue(altSnap.altScreen)
        assertEquals("AltLine", rowText(altSnap.lines[0]))
        parser.feed("\u001B[?1049l")
        val restored = buf.snapshot()
        assertFalse(restored.altScreen)
        assertSame(snap5.lines[0], restored.lines[0])
        assertEquals("Edited1", rowText(restored.lines[0]))
    }

    @Test
    fun keywordHighlighterSinglePassMatchesAllBucketsAndZeroAllocFastPath() {
        val plain = "just ordinary shell output 12345"
        assertSame(plain, KeywordHighlighter.highlight(plain))

        val allBuckets = KeywordHighlighter.highlight(
            "ERROR panic denied WARN deprecated timeout retry OK passed healthy active (running) 192.168.1.10"
        )
        assertTrue(allBuckets.contains("\u001B[38;5;203mERROR\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;203mpanic\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;203mdenied\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;221mWARN\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;221mdeprecated\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;221mtimeout\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;221mretry\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;114mOK\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;114mpassed\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;114mhealthy\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;114mactive (running)\u001B[39m"))
        assertTrue(allBuckets.contains("\u001B[38;5;117m192.168.1.10\u001B[39m"))

        // Remote foreground color (including colon-SGR) suppresses highlighting until reset
        val colored = KeywordHighlighter.highlight("\u001B[38:2:255:0:0mERROR\u001B[39m OK")
        assertEquals("\u001B[38:2:255:0:0mERROR\u001B[39m \u001B[38;5;114mOK\u001B[39m", colored)

        // Streaming session reuses buffer cleanly across chunks with split ANSI
        val sess = KeywordHighlighter.Session()
        assertEquals("hello ", sess.highlight("hello \u001B[38;5;"))
        assertEquals("\u001B[38;5;196mERROR\u001B[0m \u001B[38;5;221mWARN\u001B[39m", sess.highlight("196mERROR\u001B[0m WARN"))
    }

    @Test
    fun updateCheckerCheckLatestHandlesReleaseAssetsRedirectsAndRejections() = runTest {
        // SemVer comparison edge cases
        assertEquals(0, UpdateChecker.compareSemver("v1.1.4+build99", "1.1.4"))
        assertTrue(UpdateChecker.compareSemver("1.1.4-alpha.1", "1.1.4-alpha.2") < 0)
        assertTrue(UpdateChecker.compareSemver("1.1.4-alpha.2", "1.1.4-beta") < 0)
        assertTrue(UpdateChecker.compareSemver("1.1.4", "1.1.4-rc.1") > 0)
        assertTrue(UpdateChecker.compareSemver("1.2", "1.1.9") > 0)

        val validJson = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/yellowdking/GravitySSH-Android/releases/tag/v1.2.0",
              "body": "Release notes",
              "published_at": "2026-11-28T00:00:00Z",
              "assets": [
                {
                  "name": "evil-release.apk",
                  "browser_download_url": "https://github.com/attacker/GravitySSH-Android/releases/download/v1.2.0/evil-release.apk"
                },
                {
                  "name": "GravitySSH-Android-v1.2.0-debug.apk",
                  "browser_download_url": "https://github.com/yellowdking/GravitySSH-Android/releases/download/v1.2.0/GravitySSH-Android-v1.2.0-debug.apk"
                },
                {
                  "name": "GravitySSH-Android-v1.2.0-release.apk",
                  "browser_download_url": "https://github.com/yellowdking/GravitySSH-Android/releases/download/v1.2.0/GravitySSH-Android-v1.2.0-release.apk"
                }
              ]
            }
        """.trimIndent()

        // 1. Trusted redirect followed + prefers trusted -release.apk over untrusted or debug apk
        val visited = ArrayList<String>()
        val okChecker = object : UpdateChecker("1.1.4") {
            override suspend fun executeFetch(req: Request): FetchResult {
                visited.add(req.url.toString())
                return if (visited.size == 1) {
                    FetchResult(302, "https://api.github.com/repositories/42/releases/latest", "")
                } else {
                    FetchResult(200, null, validJson)
                }
            }
        }
        val res = okChecker.checkLatest()
        assertEquals(2, visited.size)
        assertEquals("1.2.0", res.latestVersion)
        assertTrue(res.hasUpdate)
        assertEquals(
            "https://github.com/yellowdking/GravitySSH-Android/releases/download/v1.2.0/GravitySSH-Android-v1.2.0-release.apk",
            res.downloadUrl
        )

        // 2. Untrusted redirect host rejected
        val badRedirectChecker = object : UpdateChecker("1.1.4") {
            override suspend fun executeFetch(req: Request): FetchResult =
                FetchResult(302, "https://evil.example.com/releases/latest", "")
        }
        assertTrue(runCatching { badRedirectChecker.checkLatest() }.exceptionOrNull() is IOException)

        // 3. Excessive redirects (>3) rejected
        val loopRedirectChecker = object : UpdateChecker("1.1.4") {
            override suspend fun executeFetch(req: Request): FetchResult =
                FetchResult(302, "https://api.github.com/repositories/42/releases/latest", "")
        }
        assertTrue(runCatching { loopRedirectChecker.checkLatest() }.exceptionOrNull() is IOException)

        // 4. Untrusted html_url rejected
        val badHtmlChecker = object : UpdateChecker("1.1.4") {
            override suspend fun executeFetch(req: Request): FetchResult =
                FetchResult(
                    200,
                    null,
                    """{"tag_name":"v1.2.0","html_url":"https://github.com/attacker/GravitySSH-Android/releases/tag/v1.2.0"}"""
                )
        }
        assertTrue(runCatching { badHtmlChecker.checkLatest() }.exceptionOrNull() is IOException)

        // 5. Non-2xx status rejected
        val errorChecker = object : UpdateChecker("1.1.4") {
            override suspend fun executeFetch(req: Request): FetchResult = FetchResult(503, null, "")
        }
        assertTrue(runCatching { errorChecker.checkLatest() }.exceptionOrNull() is IOException)
    }

    private fun rowText(row: Array<TerminalCell>): String {
        val sb = StringBuilder(row.size)
        for (cell in row) cell.appendTo(sb)
        return sb.toString().trimEnd()
    }
}
