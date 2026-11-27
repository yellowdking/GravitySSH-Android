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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

private class TerminalFrameSnapshotCache {
    private var cachedSessionId: String? = null
    private var cachedVersion: Long = Long.MIN_VALUE
    private var cachedScrollOffset: Int = Int.MIN_VALUE
    private var cachedSnapshot: TerminalSnapshot? = null

    fun getOrUpdate(controller: TerminalSessionController, version: Long, scrollOffset: Int): TerminalSnapshot {
        val cur = cachedSnapshot
        if (cur != null && cachedSessionId == controller.sessionId && cachedVersion == version && cachedScrollOffset == scrollOffset) {
            return cur
        }
        val next = controller.snapshot(scrollOffset)
        cachedSessionId = controller.sessionId
        cachedVersion = version
        cachedScrollOffset = scrollOffset
        cachedSnapshot = next
        return next
    }

    fun currentOrFetch(controller: TerminalSessionController, scrollOffset: Int): TerminalSnapshot =
        getOrUpdate(controller, controller.frameVersion.value, scrollOffset)
}

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
    // Hold State<Long> without reading .value in composition so 60fps frames only invalidate the Canvas draw phase.
    val frameVersionState = controller.frameVersion.collectAsStateWithLifecycle()
    val snapshotCache = remember(controller.sessionId) { TerminalFrameSnapshotCache() }
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
            val rows = floor((canvasSize.height - TERM_PAD_Y * 2) / cellH).toInt().coerceIn(8, 120)
            if (cols != lastCols || rows != lastRows) {
                if (lastCols != 0 && lastRows != 0) delay(180L)
                lastCols = cols; lastRows = rows; onResize(cols, rows)
            }
        }
    }

    val sSnapCache by rememberUpdatedState(snapshotCache)
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
    val searchMatchesState = remember(controller.sessionId) { mutableStateOf<List<SearchMatch>>(emptyList()) }

    LaunchedEffect(controller, qLower, scrollOffset) {
        if (qLower.isEmpty()) {
            searchMatchesState.value = emptyList()
            return@LaunchedEffect
        }
        controller.frameVersion.collect {
            val matches = withContext(Dispatchers.Default) {
                val snap = controller.snapshot(scrollOffset)
                computeSearchMatches(snap, qLower)
            }
            searchMatchesState.value = matches
            delay(50L)
        }
    }

    Box(modifier.fillMaxSize().background(AgBg).onSizeChanged { canvasSize = it }) {
        TerminalImeSink(focusReq, onImeInput, onHardwareArrow)
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTerminalTouchGestures(
                    d, { sMode }, { sCtrl.isAlternateScreen() }, { sCW }, { sCH }, { sFont }, { sScroll },
                    { sSnapCache.currentOrFetch(sCtrl, sScroll).scrollbackSize },
                    { ts.cancelFling() },
                    { r, c ->
                        val handledCursorTap = ts.handleTapCell(r, c, sMode, sCtrl)
                        if (!handledCursorTap) {
                            runCatching { focusReq.requestFocus() }
                            keyboard?.show()
                        }
                    },
                    { sSel },
                    { r, c ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val snap = sSnapCache.currentOrFetch(sCtrl, sScroll)
                        sOnSel(TerminalSelectionHelper.selectWordAt(snap, r, c))
                    },
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
        ) {
            val version = frameVersionState.value
            val snap = snapshotCache.getOrUpdate(controller, version, scrollOffset)
            val matches = searchMatchesState.value
            drawTerminalSnapshot(snap, paint, boldPaint, cellW, cellH, baseline, scrollOffset, matches, drawState, ts.tapCell, selection)
        }
        if (scrollOffset == 0 && selection == null && autocomplete.visible && autocomplete.suggestions.isNotEmpty()) {
            FloatingAutocompleteHost(
                controller = controller,
                frameVersionState = frameVersionState,
                autocomplete = autocomplete,
                cellW = cellW,
                cellH = cellH,
                canvasSize = canvasSize,
                density = d
            )
        }
        selection?.let { sel ->
            SelectionActionToolbar(
                sel, cellW, cellH, canvasSize, d,
                {
                    val snap = snapshotCache.currentOrFetch(controller, scrollOffset)
                    onCopySelection(TerminalSelectionHelper.extractText(snap, sel))
                    onSelectionChange(null)
                },
                {
                    val snap = snapshotCache.currentOrFetch(controller, scrollOffset)
                    onSelectionChange(TerminalSelectionHelper.selectLine(snap, sel.start.row))
                },
                {
                    val snap = snapshotCache.currentOrFetch(controller, scrollOffset)
                    onSelectionChange(TerminalSelectionHelper.selectAllVisible(snap))
                },
                { onSelectionChange(null) }
            )
        }
        TouchVelocityBadgeOverlay(ts, Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
}

@Composable
private fun FloatingAutocompleteHost(
    controller: TerminalSessionController,
    frameVersionState: State<Long>,
    autocomplete: FloatingAutocompleteState,
    cellW: Float,
    cellH: Float,
    canvasSize: IntSize,
    density: Float
) {
    val cursorPos by remember(controller, frameVersionState) {
        derivedStateOf {
            frameVersionState.value
            controller.cursorPosition()
        }
    }
    FloatingAutocompletePopup(
        state = autocomplete,
        cursorRow = cursorPos.first,
        cursorCol = cursorPos.second,
        cellW = cellW,
        cellH = cellH,
        canvasSize = canvasSize,
        density = density
    )
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
