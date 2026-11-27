package com.gravityssh.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternateScreenParserTest {

    @Test
    fun alternateScreenPreservesMainContentAndCursorAndDetectsNano() {
        val ctrl = TerminalSessionController("s-nano", 40, 10, keywordHighlightEnabled = { false })

        ctrl.onRemoteOutput("user@host:~$ ls -la\r\nGNU nano prompt")
        val savedCursor = ctrl.cursorPosition()
        assertEquals(1 to 15, savedCursor)
        assertEquals(10 to 40, ctrl.screenSize())
        assertFalse(ctrl.buffer.isAltScreen)
        assertFalse(ctrl.buffer.isNanoScreenHint())
        assertFalse(ctrl.isNanoDetected())

        // Enter alternate screen (?1049h) and render nano UI
        ctrl.onRemoteOutput(
            "\u001B[?1049h" +
                "\u001B[1;1H  GNU nano 7.2   /etc/hosts" +
                "\u001B[3;1H127.0.0.1 localhost" +
                "\u001B[10;1H^X Exit  ^O Write Out" +
                "\u001B[3;11H"
        )
        assertTrue(ctrl.buffer.isAltScreen)
        assertTrue(ctrl.isAlternateScreen())
        assertTrue(ctrl.alternateScreen.value)
        assertTrue(ctrl.buffer.isNanoScreenHint())
        assertTrue(ctrl.isNanoDetected())
        assertTrue(ctrl.nanoDetected.value)
        assertEquals(2 to 10, ctrl.cursorPosition())
        assertTrue(ctrl.extractPlainText().contains("127.0.0.1 localhost"))
        assertFalse(ctrl.extractPlainText().contains("user@host:~$"))

        // Exit alternate screen (?1049l) -> restores normal screen & cursor
        ctrl.onRemoteOutput("\u001B[?1049l")
        assertFalse(ctrl.buffer.isAltScreen)
        assertFalse(ctrl.isAlternateScreen())
        assertFalse(ctrl.alternateScreen.value)
        assertFalse(ctrl.buffer.isNanoScreenHint())
        assertFalse(ctrl.isNanoDetected())
        assertEquals(savedCursor, ctrl.cursorPosition())
        assertEquals("user@host:~$ ls -la\nGNU nano prompt", ctrl.extractPlainText())
    }

    @Test
    fun decckmTogglesApplicationCursorKeysAndArrowSequences() {
        val emitted = mutableListOf<String>()
        val ctrl = TerminalSessionController("s-decckm", 40, 10, keywordHighlightEnabled = { false }, onSendInput = { emitted.add(it) })

        assertFalse(ctrl.buffer.applicationCursorKeys)
        assertFalse(ctrl.isApplicationCursorKeys())
        assertEquals("\u001B[A", ctrl.arrowSequence(CursorDir.UP))
        assertEquals("\u001B[B", ctrl.arrowSequence(CursorDir.DOWN))
        assertEquals("\u001B[C", ctrl.arrowSequence(CursorDir.RIGHT))
        assertEquals("\u001B[D", ctrl.arrowSequence(CursorDir.LEFT))

        // Enable DECCKM (?1h)
        ctrl.onRemoteOutput("\u001B[?1h")
        assertTrue(ctrl.buffer.applicationCursorKeys)
        assertTrue(ctrl.isApplicationCursorKeys())
        assertEquals("\u001BOA", ctrl.arrowSequence(CursorDir.UP))
        assertEquals("\u001BOB", ctrl.arrowSequence(CursorDir.DOWN))
        assertEquals("\u001BOC", ctrl.arrowSequence(CursorDir.RIGHT))
        assertEquals("\u001BOD", ctrl.arrowSequence(CursorDir.LEFT))
        assertEquals("\u001BOC\u001BOC\u001BOC", ctrl.sendArrow(CursorDir.RIGHT, 3))
        assertEquals(listOf("\u001BOC\u001BOC\u001BOC"), emitted)

        // Disable DECCKM (?1l)
        ctrl.onRemoteOutput("\u001B[?1l")
        assertFalse(ctrl.buffer.applicationCursorKeys)
        assertFalse(ctrl.isApplicationCursorKeys())
        assertEquals("\u001B[A", ctrl.arrowSequence(CursorDir.UP))
        assertEquals("\u001B[B", ctrl.arrowSequence(CursorDir.DOWN))
        assertEquals("\u001B[C", ctrl.arrowSequence(CursorDir.RIGHT))
        assertEquals("\u001B[D", ctrl.arrowSequence(CursorDir.LEFT))
        assertEquals("\u001B[D\u001B[D", ctrl.sendArrow(CursorDir.LEFT, 2))
        assertEquals(listOf("\u001BOC\u001BOC\u001BOC", "\u001B[D\u001B[D"), emitted)
    }

    @Test
    fun mouseReportingModesAndSgrMouseTapSequence() {
        val emitted = mutableListOf<String>()
        val ctrl = TerminalSessionController("s-mouse", 80, 24, keywordHighlightEnabled = { false })
        ctrl.setInputSender { emitted.add(it) }

        assertFalse(ctrl.buffer.mouseTrackingEnabled)
        assertFalse(ctrl.buffer.sgrMouseMode)

        ctrl.onRemoteOutput("\u001B[?1000h\u001B[?1006h")
        assertTrue(ctrl.buffer.mouseTrackingEnabled && ctrl.isMouseTrackingEnabled())
        assertTrue(ctrl.buffer.sgrMouseMode && ctrl.isSgrMouseMode())

        assertEquals("\u001B[<0;6;8M\u001B[<0;6;8m", ctrl.sendSgrMouseTap(5, 7))
        assertEquals(listOf("\u001B[<0;6;8M\u001B[<0;6;8m"), emitted)
        assertEquals("\u001B[<0;1;1M\u001B[<0;1;1m", ctrl.sendSgrMouseTap(-3, -5))
        assertEquals("\u001B[<0;80;24M\u001B[<0;80;24m", ctrl.sendSgrMouseTap(999, 999))

        ctrl.onRemoteOutput("\u001B[?1000l\u001B[?1006l")
        assertFalse(ctrl.buffer.mouseTrackingEnabled || ctrl.isMouseTrackingEnabled())
        assertFalse(ctrl.buffer.sgrMouseMode || ctrl.isSgrMouseMode())

        // Compound enable/disable + multiple tracking bits
        ctrl.onRemoteOutput("\u001B[?1000;1002;1006h")
        assertTrue(ctrl.isMouseTrackingEnabled() && ctrl.isSgrMouseMode())
        ctrl.onRemoteOutput("\u001B[?1000l")
        assertTrue(ctrl.isMouseTrackingEnabled())
        ctrl.onRemoteOutput("\u001B[?1002;1006l")
        assertFalse(ctrl.isMouseTrackingEnabled() || ctrl.isSgrMouseMode())
    }
}
