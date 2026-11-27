package com.gravityssh.app.ui.terminal

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
internal fun TerminalImeSink(focusRequester: FocusRequester, onImeInput: (String) -> Unit) {
    var imeValue by remember { mutableStateOf(SENTINEL_VALUE) }
    BasicTextField(
        value = imeValue,
        onValueChange = { next ->
            if (next == imeValue) return@BasicTextField
            val composing = next.composition != null && !next.composition!!.collapsed
            if (composing) {
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
        keyboardActions = KeyboardActions(onSend = { onImeInput("\r") }),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focusRequester)
    )
}
