package com.gravityssh.app.ui.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.theme.AgBg
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun TerminalCanvas(
    controller: TerminalSessionController,
    touchMode: TouchCursorMode,
    touchSensitivity: TouchSensitivity,
    touchFlingEnabled: Boolean,
    fontSizeSp: Float,
    searchQuery: String,
    scrollOffset: Int,
    autocomplete: FloatingAutocompleteState,
    selection: TextSelection?,
    onScrollOffsetChange: (Int) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onFontSizeCommit: (Int) -> Unit = {},
    onResize: (cols: Int, rows: Int) -> Unit,
    onImeInput: (String) -> Unit,
    onHardwareArrow: (CursorDir, Boolean, Boolean) -> Unit = { dir, ctrl, alt ->
        if (ctrl || alt) onImeInput(applyArrowModifiers(dir, ctrl, alt)) else controller.sendArrow(dir, 1)
    },
    imeFocusRequester: FocusRequester = remember { FocusRequester() },
    onSelectionChange: (TextSelection?) -> Unit,
    onCopySelection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val frameVersion by controller.frameVersion.collectAsStateWithLifecycle()
    val snapshot = remember(frameVersion, scrollOffset, controller.sessionId) {
        controller.snapshot(scrollOffset)
    }
    val density = LocalDensity.current
    val d = density.density
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val focusReq = imeFocusRequester
    val ts = remember { TerminalTouchState(scope, haptic, TouchCursorEngine(touchSensitivity)) }
    val drawState = remember { TerminalCanvasDrawState() }

    LaunchedEffect(touchSensitivity) { ts.engine.sensitivity = touchSensitivity }
    LaunchedEffect(touchMode, controller.sessionId) { ts.cancelFling() }
    DisposableEffect(Unit) { onDispose { ts.dispose() } }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastCols by remember(controller.sessionId) { mutableIntStateOf(0) }
    var lastRows by remember(controller.sessionId) { mutableIntStateOf(0) }

    val fontPx = with(density) { fontSizeSp.coerceIn(10f, 22f).sp.toPx() * 1.05f }
    val paint = remember(fontPx) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = fontPx; isSubpixelText = true } }
    val boldPaint = remember(fontPx) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = fontPx; isSubpixelText = true } }
    val cellW = remember(paint) { paint.measureText("M").coerceAtLeast(6f) }
    val fm = remember(paint) { paint.fontMetrics }
    val cellH = remember(fm) { (fm.descent - fm.ascent + 2f).coerceAtLeast(10f) }
    val baseline = remember(fm) { -fm.ascent + 1f }

    LaunchedEffect(canvasSize, cellW, cellH, controller.sessionId) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val cols = floor((canvasSize.width - TERM_PAD_X * 2) / cellW).toInt().coerceIn(20, 240)
            val rows = floor((canvasSize.height - TERM_PAD_Y * 2) / cellH).toInt().coerceIn(5, 100)
            if (cols != lastCols || rows != lastRows) {
                if (lastCols != 0 && lastRows != 0) delay(180L)
                lastCols = cols; lastRows = rows; onResize(cols, rows)
            }
        }
    }

    val sSnap by rememberUpdatedState(snapshot)
    val sSel by rememberUpdatedState(selection)
    val sMode by rememberUpdatedState(touchMode)
    val sFling by rememberUpdatedState(touchFlingEnabled)
    val sFont by rememberUpdatedState(fontSizeSp)
    val sScroll by rememberUpdatedState(scrollOffset)
    val sCW by rememberUpdatedState(cellW)
    val sCH by rememberUpdatedState(cellH)
    val sCtrl by rememberUpdatedState(controller)
    val sOnSel by rememberUpdatedState(onSelectionChange)
    val sOnFont by rememberUpdatedState(onFontSizeChange)
    val sOnFontCommit by rememberUpdatedState(onFontSizeCommit)
    val sOnScroll by rememberUpdatedState(onScrollOffsetChange)
    val qLower = remember(searchQuery) { searchQuery.trim().lowercase() }
    val searchMatches = remember(snapshot, qLower) { computeSearchMatches(snapshot, qLower, drawState) }

    Box(modifier.fillMaxSize().background(AgBg).onSizeChanged { canvasSize = it }) {
        TerminalImeSink(focusReq, onImeInput, onHardwareArrow)
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTerminalTouchGestures(
                    d, { sMode }, { sCtrl.isAlternateScreen() }, { sCW }, { sCH }, { sFont }, { sScroll }, { sSnap.scrollbackSize },
                    { ts.cancelFling() },
                    { r, c ->
                        val handledCursorTap = ts.handleTapCell(r, c, sMode, sCtrl)
                        if (!handledCursorTap) {
                            runCatching { focusReq.requestFocus() }
                            keyboard?.show()
                        }
                    },
                    { sSel },
                    { r, c -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); sOnSel(TerminalSelectionHelper.selectWordAt(sSnap, r, c)) },
                    { sOnSel(it) },
                    { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    { sOnFont(it) },
                    { sOnFontCommit(it.roundToInt().coerceIn(10, 22)) },
                    { sOnScroll(it) },
                    { ts.handleDragStart() },
                    { dx, dy, dt -> ts.handleDragDelta(dx, dy, dt, sCW / d, sCH / d, sCtrl) },
                    { vx, vy -> ts.handleDragEnd(vx, vy, sCW / d, sCH / d, sFling, sCtrl) }
                )
            }
        ) { drawTerminalSnapshot(snapshot, paint, boldPaint, cellW, cellH, baseline, scrollOffset, searchMatches, drawState, ts.tapCell, selection) }
        if (scrollOffset == 0 && selection == null) {
            FloatingAutocompletePopup(autocomplete, snapshot.cursorRow, snapshot.cursorCol, cellW, cellH, canvasSize, d)
        }
        selection?.let { sel ->
            SelectionActionToolbar(
                sel, cellW, cellH, canvasSize, d,
                { onCopySelection(TerminalSelectionHelper.extractText(sSnap, sel)); onSelectionChange(null) },
                { onSelectionChange(TerminalSelectionHelper.selectLine(sSnap, sel.start.row)) },
                { onSelectionChange(TerminalSelectionHelper.selectAllVisible(sSnap)) },
                { onSelectionChange(null) }
            )
        }
        TouchVelocityBadgeOverlay(ts, Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
}

@Composable
private fun TouchVelocityBadgeOverlay(ts: TerminalTouchState, modifier: Modifier = Modifier) {
    val badge = ts.dragBadge
    TouchVelocityBadge(
        visible = badge != null,
        zoneLabel = badge?.zoneLabel ?: "标准",
        gain = badge?.gain ?: 1f,
        modifier = modifier
    )
}
