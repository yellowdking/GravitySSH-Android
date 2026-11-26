package com.gravityssh.app.ui.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.theme.AgBg
import kotlin.math.floor

@Composable
fun TerminalCanvas(
  snapshot: TerminalSnapshot,
  controller: TerminalSessionController,
  touchMode: TouchCursorMode,
  touchSensitivity: TouchSensitivity,
  touchFlingEnabled: Boolean,
  fontSizeSp: Float,
  searchQuery: String,
  scrollOffset: Int,
  autocomplete: FloatingAutocompleteState,
  onScrollOffsetChange: (Int) -> Unit,
  onFontSizeChange: (Float) -> Unit,
  onResize: (cols: Int, rows: Int) -> Unit,
  onImeInput: (String) -> Unit,
  onLongPressCopy: (row: Int, col: Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current
  val keyboard = LocalSoftwareKeyboardController.current
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }
  val touchState = remember { TerminalTouchState(scope, haptic, TouchCursorEngine(touchSensitivity)) }

  LaunchedEffect(touchSensitivity) { touchState.engine.sensitivity = touchSensitivity }
  LaunchedEffect(touchMode, controller.sessionId) { touchState.cancelFling() }
  DisposableEffect(Unit) { onDispose { touchState.dispose() } }

  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var lastCols by remember(controller.sessionId) { mutableIntStateOf(0) }
  var lastRows by remember(controller.sessionId) { mutableIntStateOf(0) }

  val fontPx = with(density) { fontSizeSp.coerceIn(10f, 22f).dp.toPx() * 1.05f }
  val paint = remember(fontPx) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = fontPx; isSubpixelText = true } }
  val boldPaint = remember(fontPx) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = fontPx; isSubpixelText = true } }
  val cellW = remember(paint) { paint.measureText("M").coerceAtLeast(6f) }
  val fm = remember(paint) { paint.fontMetrics }
  val cellH = remember(fm) { (fm.descent - fm.ascent + 2f).coerceAtLeast(10f) }
  val baseline = remember(fm) { -fm.ascent + 1f }

  LaunchedEffect(canvasSize, cellW, cellH, controller.sessionId) {
    if (canvasSize.width > 0 && canvasSize.height > 0) {
      val cols = floor((canvasSize.width - TERM_PAD_X * 2) / cellW).toInt().coerceIn(20, 240)
      val rows = floor((canvasSize.height - TERM_PAD_Y * 2) / cellH).toInt().coerceIn(4, 100)
      if (cols != lastCols || rows != lastRows) { lastCols = cols; lastRows = rows; onResize(cols, rows) }
    }
  }

  val curMode by rememberUpdatedState(touchMode)
  val curFling by rememberUpdatedState(touchFlingEnabled)
  val curFont by rememberUpdatedState(fontSizeSp)
  val curScroll by rememberUpdatedState(scrollOffset)
  val curScrollback by rememberUpdatedState(snapshot.scrollbackSize)
  val curCellW by rememberUpdatedState(cellW)
  val curCellH by rememberUpdatedState(cellH)
  val curCtrl by rememberUpdatedState(controller)
  val curOnLongPress by rememberUpdatedState(onLongPressCopy)
  val qLower = remember(searchQuery) { searchQuery.trim().lowercase() }

  Box(
    modifier = modifier.fillMaxSize().background(AgBg).onSizeChanged { canvasSize = it }
      .pointerInput(Unit) {
        detectTerminalTouchGestures(
          density.density, { curMode }, { curCtrl.isAlternateScreen() },
          { curCellW }, { curCellH }, { curFont }, { curScroll }, { curScrollback },
          { touchState.cancelFling() },
          { row, col ->
            touchState.handleTapCell(row, col, curMode, curCtrl)
            runCatching { focusRequester.requestFocus() }; keyboard?.show()
          },
          { row, col -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); curOnLongPress(row, col) },
          onFontSizeChange, onScrollOffsetChange,
          { touchState.handleDragStart() },
          { dx, dy, dt, _ -> touchState.handleDragDelta(dx, dy, dt, curCellW / density.density, curCellH / density.density, curCtrl) },
          { vx, vy, _ -> touchState.handleDragEnd(vx, vy, curCellW / density.density, curCellH / density.density, curFling, curCtrl) }
        )
      }
  ) {
    TerminalImeSink(focusRequester, onImeInput)
    Canvas(Modifier.fillMaxSize()) {
      drawTerminalSnapshot(snapshot, paint, boldPaint, cellW, cellH, baseline, scrollOffset, qLower, touchState.tapCell)
    }
    if (scrollOffset == 0) {
      FloatingAutocompletePopup(autocomplete, snapshot.cursorRow, snapshot.cursorCol, cellW, cellH, canvasSize, density.density)
    }
    TouchVelocityBadge(
      visible = touchState.dragBadge != null,
      zoneLabel = touchState.dragBadge?.zoneLabel ?: "标准",
      gain = touchState.dragBadge?.gain ?: 1.0f,
      modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
    )
  }
}
