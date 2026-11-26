package com.gravityssh.app.ui.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gravityssh.app.terminal.TerminalSnapshot
import com.gravityssh.app.ui.theme.AgBg
import kotlin.math.floor

private const val IME_SENTINEL = "\u200B"

@Composable
fun TerminalCanvas(
    snapshot: TerminalSnapshot,
    fontSizeSp: Float,
    searchQuery: String,
    scrollOffset: Int,
    onScrollOffsetChange: (Int) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onImeInput: (String) -> Unit,
    onLongPressCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastCols by remember { mutableIntStateOf(0) }
    var lastRows by remember { mutableIntStateOf(0) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    val fontSizePx = with(density) { fontSizeSp.coerceIn(10f, 22f).dp.toPx() * 1.05f }
    val paint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = fontSizePx; isSubpixelText = true
        }
    }
    val boldPaint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = fontSizePx; isSubpixelText = true
        }
    }
    val cellW = remember(paint) { paint.measureText("M").coerceAtLeast(6f) }
    val fm = remember(paint) { paint.fontMetrics }
    val cellH = remember(fm) { (fm.descent - fm.ascent + 2f).coerceAtLeast(10f) }
    val baselineOffset = remember(fm) { -fm.ascent + 1f }

    LaunchedEffect(canvasSize, cellW, cellH) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val cols = floor((canvasSize.width - 24f) / cellW).toInt().coerceIn(20, 240)
            val rows = floor((canvasSize.height - 16f) / cellH).toInt().coerceIn(8, 100)
            if (cols != lastCols || rows != lastRows) {
                lastCols = cols; lastRows = rows; onResize(cols, rows)
            }
        }
    }

    var imeValue by remember { mutableStateOf(TextFieldValue(IME_SENTINEL, TextRange(IME_SENTINEL.length))) }
    val qLower = remember(searchQuery) { searchQuery.trim().lowercase() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AgBg)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { runCatching { focusRequester.requestFocus() }; keyboard?.show() },
                    onLongPress = { onLongPressCopy() }
                )
            }
            .pointerInput(snapshot.scrollbackSize, cellH, fontSizeSp) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f && (zoom > 1.02f || zoom < 0.98f)) {
                        onFontSizeChange((fontSizeSp * zoom).coerceIn(10f, 22f))
                    } else if (pan.y != 0f) {
                        dragAccum += pan.y
                        val steps = (dragAccum / cellH).toInt()
                        if (steps != 0) {
                            dragAccum -= steps * cellH
                            onScrollOffsetChange((scrollOffset + steps).coerceIn(0, snapshot.scrollbackSize))
                        }
                    }
                }
            }
    ) {
        BasicTextField(
            value = imeValue,
            onValueChange = { next ->
                val raw = next.text
                when {
                    raw.isEmpty() -> onImeInput("\u007F")
                    raw.startsWith(IME_SENTINEL) -> raw.removePrefix(IME_SENTINEL).takeIf { it.isNotEmpty() }?.let { onImeInput(it.replace("\n", "\r")) }
                    else -> raw.replace(IME_SENTINEL, "").takeIf { it.isNotEmpty() }?.let { onImeInput(it.replace("\n", "\r")) }
                }
                imeValue = TextFieldValue(IME_SENTINEL, TextRange(IME_SENTINEL.length))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onImeInput("\r") }),
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focusRequester)
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTerminalSnapshot(snapshot, paint, boldPaint, cellW, cellH, baselineOffset, scrollOffset, qLower)
        }
    }
}
