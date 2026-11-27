package com.gravityssh.app.ui.terminal

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.gravityssh.app.terminal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val IME_SENTINEL = "\u200B"
private val SENTINEL_VALUE = TextFieldValue(IME_SENTINEL, TextRange(IME_SENTINEL.length))

internal class TerminalTouchState(
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    val engine: TouchCursorEngine
) {
    var dragBadge by mutableStateOf<VelocitySample?>(null)
    var tapCell by mutableStateOf<Pair<Int, Int>?>(null)
    private var flingJob: Job? = null
    private var tapJob: Job? = null

    fun cancelFling() {
        flingJob?.cancel()
        flingJob = null
    }

    fun dispose() {
        cancelFling()
        tapJob?.cancel()
    }

    fun handleTapCell(
        row: Int,
        col: Int,
        mode: TouchCursorMode,
        controller: TerminalSessionController
    ): Boolean {
        val alt = controller.isAlternateScreen()
        if (mode != TouchCursorMode.CURSOR && !(mode == TouchCursorMode.AUTO && alt)) return false
        val (rows, cols) = controller.screenSize()
        val tRow = row.coerceIn(0, (rows - 1).coerceAtLeast(0))
        val tCol = col.coerceIn(0, (cols - 1).coerceAtLeast(0))
        if (controller.isMouseTrackingEnabled() && controller.isSgrMouseMode()) {
            controller.sendSgrMouseTap(tCol, tRow)
        } else {
            val (cRow, cCol) = controller.cursorPosition()
            engine.computeTapToCursorSteps(cRow, cCol, tRow, tCol, rows, cols)
                .forEach { controller.sendArrow(it.dir, it.count) }
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        tapCell = tRow to tCol
        tapJob?.cancel()
        tapJob = scope.launch { delay(280L); tapCell = null }
        return true
    }

    fun handleDragStart() {
        cancelFling()
        engine.reset()
    }

    fun handleDragDelta(
        dxDp: Float,
        dyDp: Float,
        dtSec: Float,
        cellWDp: Float,
        cellHDp: Float,
        controller: TerminalSessionController
    ) {
        val res = engine.onDragDelta(dxDp, dyDp, dtSec, cellWDp, cellHDp, axisLock = true)
        dragBadge = res.velocity
        if (res.steps.isNotEmpty()) {
            res.steps.forEach { controller.sendArrow(it.dir, it.count) }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun handleDragEnd(
        vxDp: Float,
        vyDp: Float,
        cellWDp: Float,
        cellHDp: Float,
        flingEnabled: Boolean,
        controller: TerminalSessionController
    ) {
        dragBadge = null
        if (flingEnabled) {
            val pulses = engine.computeFlingSchedule(vxDp, vyDp, cellWDp, cellHDp)
            if (pulses.isNotEmpty()) {
                cancelFling()
                flingJob = scope.launch {
                    for (p in pulses) {
                        delay(p.delayMs)
                        controller.sendArrow(p.step.dir, p.step.count)
                    }
                }
            }
        }
        engine.reset()
    }
}

@Composable
internal fun TerminalImeSink(
    focusRequester: FocusRequester,
    onImeInput: (String) -> Unit,
    onHardwareArrow: (CursorDir, Boolean, Boolean) -> Unit
) {
    var imeValue by remember { mutableStateOf(SENTINEL_VALUE) }

    fun flushPendingComposition() {
        val pending = imeValue.text.replace(IME_SENTINEL, "")
        if (pending.isNotEmpty()) {
            onImeInput(pending.replace("\n", "\r"))
        }
        imeValue = SENTINEL_VALUE
    }

    BasicTextField(
        value = imeValue,
        onValueChange = { next ->
            if (next == imeValue) return@BasicTextField
            val composing = next.composition != null && !next.composition!!.collapsed
            if (composing && !next.text.contains('\n')) {
                imeValue = next
                return@BasicTextField
            }
            val wasComposing = imeValue.composition?.let { !it.collapsed } == true
            val raw = next.text
            when {
                raw.isEmpty() -> if (!wasComposing) onImeInput("\u007F")
                raw.startsWith(IME_SENTINEL) -> raw.removePrefix(IME_SENTINEL).takeIf { it.isNotEmpty() }?.let { onImeInput(it.replace("\n", "\r")) }
                else -> raw.replace(IME_SENTINEL, "").takeIf { it.isNotEmpty() }?.let { onImeInput(it.replace("\n", "\r")) }
            }
            imeValue = SENTINEL_VALUE
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(
            onSend = {
                flushPendingComposition()
                onImeInput("\r")
            }
        ),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                when (event.key) {
                    Key.DirectionUp -> {
                        flushPendingComposition()
                        onHardwareArrow(CursorDir.UP, ctrl, alt)
                        true
                    }
                    Key.DirectionDown -> {
                        flushPendingComposition()
                        onHardwareArrow(CursorDir.DOWN, ctrl, alt)
                        true
                    }
                    Key.DirectionLeft -> {
                        flushPendingComposition()
                        onHardwareArrow(CursorDir.LEFT, ctrl, alt)
                        true
                    }
                    Key.DirectionRight -> {
                        flushPendingComposition()
                        onHardwareArrow(CursorDir.RIGHT, ctrl, alt)
                        true
                    }
                    Key.Tab -> {
                        flushPendingComposition()
                        onImeInput(if (ctrl || alt) applyTerminalModifiers("\t", ctrl, alt) else "\t")
                        true
                    }
                    Key.Escape -> {
                        imeValue = SENTINEL_VALUE
                        onImeInput("\u001B")
                        true
                    }
                    Key.MoveHome -> {
                        flushPendingComposition()
                        onImeInput(if (ctrl || alt) applyTerminalModifiers("\u001B[H", ctrl, alt) else "\u001B[H")
                        true
                    }
                    Key.MoveEnd -> {
                        flushPendingComposition()
                        onImeInput(if (ctrl || alt) applyTerminalModifiers("\u001B[F", ctrl, alt) else "\u001B[F")
                        true
                    }
                    Key.PageUp -> {
                        flushPendingComposition()
                        onImeInput(if (ctrl || alt) applyTerminalModifiers("\u001B[5~", ctrl, alt) else "\u001B[5~")
                        true
                    }
                    Key.PageDown -> {
                        flushPendingComposition()
                        onImeInput(if (ctrl || alt) applyTerminalModifiers("\u001B[6~", ctrl, alt) else "\u001B[6~")
                        true
                    }
                    Key.Delete -> {
                        flushPendingComposition()
                        onImeInput("\u001B[3~")
                        true
                    }
                    Key.Backspace -> {
                        val hasPending = imeValue.text.length > IME_SENTINEL.length ||
                            (imeValue.composition != null && !imeValue.composition!!.collapsed)
                        if (hasPending) {
                            false
                        } else {
                            onImeInput(if (alt) "\u001B\u007F" else "\u007F")
                            true
                        }
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        flushPendingComposition()
                        onImeInput("\r")
                        true
                    }
                    else -> {
                        if (ctrl || alt) {
                            val shiftOnly = event.nativeKeyEvent.metaState and NativeKeyEvent.META_SHIFT_MASK
                            val chCode = event.nativeKeyEvent.getUnicodeChar(shiftOnly)
                            if (chCode in 0x20..0x7E) {
                                flushPendingComposition()
                                onImeInput(applyTerminalModifiers(chCode.toChar().toString(), ctrl, alt))
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                }
            }
    )
}
