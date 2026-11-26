package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.gravityssh.app.terminal.TouchCursorMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

internal const val TERM_PAD_X = 12f
internal const val TERM_PAD_Y = 8f

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
    onLongPressCopy: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onScrollOffsetChange: (Int) -> Unit,
    onCursorDragStart: () -> Unit,
    onCursorDragDelta: (dxDp: Float, dyDp: Float, dtSec: Float, horizontalOnly: Boolean) -> Unit,
    onCursorDragEnd: (vxDpPerSec: Float, vyDpPerSec: Float, horizontalOnly: Boolean) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        onCancelFling()
        val downPos = firstDown.position
        val downTime = firstDown.uptimeMillis
        var prevTime = downTime
        var totalMove = 0f
        var dragging = false
        var multiTouch = false
        var longPressFired = false
        var dragScrollAccum = 0f
        var axisChosen = 0 // 0=unset, 1=horizontal, 2=vertical
        var activeCursorDrag = false
        var cursorHorizontalOnly = false
        val tracker = VelocityTracker()
        tracker.addPosition(downTime, downPos)

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                multiTouch = true
                if (activeCursorDrag) {
                    activeCursorDrag = false
                    onCursorDragEnd(0f, 0f, cursorHorizontalOnly)
                }
                val zoom = event.calculateZoom()
                if (zoom > 1.015f || zoom < 0.985f) {
                    onFontSizeChange((getFontSizeSp() * zoom).coerceIn(10f, 22f))
                }
                event.changes.forEach { it.consume() }
                continue
            }

            if (multiTouch) continue
            val change = pressed.first()
            val delta = change.positionChange()
            val nowMs = change.uptimeMillis
            val dtSec = ((nowMs - prevTime).coerceAtLeast(4L)) / 1000f
            prevTime = nowMs
            tracker.addPosition(nowMs, change.position)

            if (!dragging) {
                totalMove = hypot(change.position.x - downPos.x, change.position.y - downPos.y)
                if (totalMove >= slop) {
                    dragging = true
                    val mode = getMode()
                    val alt = getIsAltScreen()
                    if (mode == TouchCursorMode.CURSOR || (mode == TouchCursorMode.AUTO && alt)) {
                        activeCursorDrag = true
                        cursorHorizontalOnly = false
                        onCursorDragStart()
                    } else if (mode == TouchCursorMode.AUTO && !alt) {
                        val dx0 = abs(change.position.x - downPos.x)
                        val dy0 = abs(change.position.y - downPos.y)
                        axisChosen = if (dx0 > dy0 * 1.35f) 1 else 2
                        if (axisChosen == 1) {
                            activeCursorDrag = true
                            cursorHorizontalOnly = true
                            onCursorDragStart()
                        }
                    }
                } else if (!longPressFired && (nowMs - downTime) >= longPressMs) {
                    longPressFired = true
                    onLongPressCopy()
                }
            }

            if (dragging && (delta.x != 0f || delta.y != 0f)) {
                change.consume()
                if (activeCursorDrag) {
                    val dxDp = delta.x / density
                    val dyDp = if (cursorHorizontalOnly) 0f else delta.y / density
                    onCursorDragDelta(dxDp, dyDp, dtSec, cursorHorizontalOnly)
                } else {
                    dragScrollAccum += delta.y
                    val ch = getCellH().coerceAtLeast(10f)
                    val steps = (dragScrollAccum / ch).toInt()
                    if (steps != 0) {
                        dragScrollAccum -= steps * ch
                        val next = (getScrollOffset() + steps).coerceIn(0, getScrollbackSize())
                        onScrollOffsetChange(next)
                    }
                }
            }
        }

        if (activeCursorDrag) {
            val vel = tracker.calculateVelocity()
            val vxDp = vel.x / density
            val vyDp = if (cursorHorizontalOnly) 0f else vel.y / density
            onCursorDragEnd(vxDp, vyDp, cursorHorizontalOnly)
        } else if (!dragging && !multiTouch && !longPressFired) {
            val col = floor((downPos.x - TERM_PAD_X) / getCellW().coerceAtLeast(6f)).toInt().coerceAtLeast(0)
            val row = floor((downPos.y - TERM_PAD_Y) / getCellH().coerceAtLeast(10f)).toInt().coerceAtLeast(0)
            onTapCell(row, col)
        }
    }
}
