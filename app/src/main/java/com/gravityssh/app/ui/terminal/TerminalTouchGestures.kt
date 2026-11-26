package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.gravityssh.app.terminal.TouchCursorMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

internal const val TERM_PAD_X = 12f
internal const val TERM_PAD_Y = 4f

private suspend fun AwaitPointerEventScope.drainPointers() {
    while (true) {
        val ev = awaitPointerEvent()
        ev.changes.forEach { it.consume() }
        if (ev.changes.none { it.pressed }) break
    }
}

internal suspend fun PointerInputScope.detectTerminalTouchGestures(
    density: Float,
    getMode: () -> TouchCursorMode,
    getIsAltScreen: () -> Boolean,
    getCellW: () -> Float,
    getCellH: () -> Float,
    getFontSizeSp: () -> Float,
    getScrollOffset: () -> Int,
    getScrollbackSize: () -> Int,
    onCancelFling: () -> Unit,
    onTapCell: (row: Int, col: Int) -> Unit,
    onLongPressCopy: (row: Int, col: Int) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onScrollOffsetChange: (Int) -> Unit,
    onCursorDragStart: () -> Unit,
    onCursorDragDelta: (dxDp: Float, dyDp: Float, dtSec: Float, horizontalOnly: Boolean) -> Unit,
    onCursorDragEnd: (vxDpPerSec: Float, vyDpPerSec: Float, horizontalOnly: Boolean) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    val lpMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onCancelFling()
        val pos0 = down.position
        val t0 = down.uptimeMillis
        val ns0 = System.nanoTime()
        var prevMs = t0
        var dragging = false
        var multi = false
        var lpFired = false
        var scrollAcc = 0f
        var cursorDrag = false
        var horizOnly = false
        val tracker = VelocityTracker().apply { addPosition(t0, pos0) }

        fun col() = floor((pos0.x - TERM_PAD_X) / getCellW().coerceAtLeast(6f)).toInt().coerceAtLeast(0)
        fun row() = floor((pos0.y - TERM_PAD_Y) / getCellH().coerceAtLeast(10f)).toInt().coerceAtLeast(0)

        while (true) {
            val event = if (!dragging && !multi && !lpFired) {
                val rem = lpMs - (System.nanoTime() - ns0) / 1_000_000L
                if (rem <= 0L) null else withTimeoutOrNull(rem) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                lpFired = true
                onLongPressCopy(row(), col())
                drainPointers()
                break
            }

            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                multi = true
                if (cursorDrag) { cursorDrag = false; onCursorDragEnd(0f, 0f, horizOnly) }
                val zoom = event.calculateZoom()
                if (zoom > 1.015f || zoom < 0.985f) onFontSizeChange((getFontSizeSp() * zoom).coerceIn(10f, 22f))
                event.changes.forEach { it.consume() }
                continue
            }
            if (multi) continue

            val chg = pressed.first()
            val delta = chg.positionChange()
            val nowMs = chg.uptimeMillis
            val dtSec = ((nowMs - prevMs).coerceAtLeast(4L)) / 1000f
            prevMs = nowMs
            tracker.addPosition(nowMs, chg.position)

            if (!dragging) {
                val dist = hypot(chg.position.x - pos0.x, chg.position.y - pos0.y)
                if (dist >= slop) {
                    dragging = true
                    val mode = getMode()
                    val alt = getIsAltScreen()
                    if (mode == TouchCursorMode.CURSOR || (mode == TouchCursorMode.AUTO && alt)) {
                        cursorDrag = true; horizOnly = false; onCursorDragStart()
                    } else if (mode == TouchCursorMode.AUTO && !alt && abs(chg.position.x - pos0.x) > abs(chg.position.y - pos0.y) * 1.35f) {
                        cursorDrag = true; horizOnly = true; onCursorDragStart()
                    }
                } else if (!lpFired && (nowMs - t0) >= lpMs) {
                    lpFired = true
                    onLongPressCopy(row(), col())
                    drainPointers()
                    break
                }
            }

            if (dragging && (delta.x != 0f || delta.y != 0f)) {
                chg.consume()
                if (cursorDrag) {
                    onCursorDragDelta(delta.x / density, if (horizOnly) 0f else delta.y / density, dtSec, horizOnly)
                } else {
                    scrollAcc += delta.y
                    val ch = getCellH().coerceAtLeast(10f)
                    val steps = (scrollAcc / ch).toInt()
                    if (steps != 0) {
                        scrollAcc -= steps * ch
                        onScrollOffsetChange((getScrollOffset() + steps).coerceIn(0, getScrollbackSize()))
                    }
                }
            }
        }

        if (cursorDrag) {
            val vel = tracker.calculateVelocity()
            onCursorDragEnd(vel.x / density, if (horizOnly) 0f else vel.y / density, horizOnly)
        } else if (!dragging && !multi && !lpFired) {
            onTapCell(row(), col())
        }
    }
}
