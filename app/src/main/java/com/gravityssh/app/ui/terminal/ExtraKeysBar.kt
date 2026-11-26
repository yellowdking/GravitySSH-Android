package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgBorder
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgSurface
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.theme.MonoFontFamily

private data class ExtraKeySpec(
    val label: String,
    val sequence: String = "",
    val isStickyCtrl: Boolean = false,
    val isStickyAlt: Boolean = false,
    val accent: Boolean = false
)

private val EXTRA_KEYS = listOf(
    ExtraKeySpec("ESC", "\u001B"),
    ExtraKeySpec("TAB", "\t"),
    ExtraKeySpec("CTRL", isStickyCtrl = true),
    ExtraKeySpec("ALT", isStickyAlt = true),
    ExtraKeySpec("↑", "\u001B[A"),
    ExtraKeySpec("↓", "\u001B[B"),
    ExtraKeySpec("←", "\u001B[D"),
    ExtraKeySpec("→", "\u001B[C"),
    ExtraKeySpec("^C", "\u0003", accent = true),
    ExtraKeySpec("^D", "\u0004"),
    ExtraKeySpec("^L", "\u000C"),
    ExtraKeySpec("|", "|"),
    ExtraKeySpec("/", "/"),
    ExtraKeySpec("~", "~"),
    ExtraKeySpec("-", "-"),
    ExtraKeySpec("HOME", "\u001B[H"),
    ExtraKeySpec("END", "\u001B[F")
)

@Composable
fun ExtraKeysBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendSequence: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AgSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EXTRA_KEYS.forEach { key ->
            val active = (key.isStickyCtrl && ctrlActive) || (key.isStickyAlt && altActive)
            val bg = when {
                active -> AgAccent
                key.accent -> Color(0xFF311B24)
                else -> AgElevated
            }
            val borderColor = when {
                active -> AgCyan
                key.accent -> Color(0xFF7F1D1D)
                else -> AgBorder
            }
            val textColor = when {
                active -> Color.White
                key.accent -> Color(0xFFFCA5A5)
                else -> AgText
            }
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when {
                        key.isStickyCtrl -> onToggleCtrl()
                        key.isStickyAlt -> onToggleAlt()
                        else -> onSendSequence(key.sequence)
                    }
                },
                shape = RoundedCornerShape(7.dp),
                color = bg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.height(34.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = key.label,
                        color = textColor,
                        fontFamily = MonoFontFamily,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Applies sticky CTRL or ALT modifier to a typed character.
 */
fun applyTerminalModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
    if ((!ctrl && !alt) || input.isEmpty()) return input
    if (input.length == 1) {
        val ch = input[0]
        var out = input
        if (ctrl) {
            val upper = ch.uppercaseChar()
            out = when {
                upper in 'A'..'Z' -> (upper.code - 64).toChar().toString()
                ch == '[' -> "\u001B"
                ch == '\\' -> "\u001C"
                ch == ']' -> "\u001D"
                ch == ' ' -> "\u0000"
                else -> input
            }
        }
        return if (alt) "\u001B$out" else out
    }
    return input
}
