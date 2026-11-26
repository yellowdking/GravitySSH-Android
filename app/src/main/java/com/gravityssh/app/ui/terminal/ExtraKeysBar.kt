package com.gravityssh.app.ui.terminal

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.gravityssh.app.terminal.CursorDir
import com.gravityssh.app.ui.theme.*

private data class CompactKey(val label: String, val seq: String = "", val arrow: CursorDir? = null)
private data class FnKey(val label: String, val seq: String = "", val ctrl: Boolean = false, val alt: Boolean = false, val red: Boolean = false)

private val COMPACT_KEYS = listOf(
  CompactKey("/", "/"),
  CompactKey("←", arrow = CursorDir.LEFT),
  CompactKey("↑", arrow = CursorDir.UP),
  CompactKey("↓", arrow = CursorDir.DOWN),
  CompactKey("→", arrow = CursorDir.RIGHT),
  CompactKey("ESC", "\u001B")
)

private val FN_ROW_1 = listOf(
  FnKey("TAB", "\t"), FnKey("CTRL", ctrl = true), FnKey("ALT", alt = true),
  FnKey("^C", "\u0003", red = true), FnKey("^D", "\u0004"), FnKey("^Z", "\u001A"), FnKey("^L", "\u000C")
)

private val FN_ROW_2 = listOf(
  FnKey("|", "|"), FnKey("-", "-"), FnKey("~", "~"),
  FnKey("HOME", "\u001B[H"), FnKey("END", "\u001B[F"), FnKey("PGUP", "\u001B[5~"), FnKey("PGDN", "\u001B[6~")
)

@Composable
fun ExtraKeysBar(
  onSendArrow: (CursorDir) -> Unit,
  onSendSequence: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val haptic = LocalHapticFeedback.current
  Row(
    modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 6.dp, vertical = 4.dp),
    Arrangement.spacedBy(5.dp),
    Alignment.CenterVertically
  ) {
    COMPACT_KEYS.forEach { k ->
      Surface(
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          if (k.arrow != null) onSendArrow(k.arrow) else onSendSequence(k.seq)
        },
        shape = RoundedCornerShape(7.dp),
        color = AgElevated,
        border = BorderStroke(1.dp, AgBorder),
        modifier = Modifier.weight(1f).height(34.dp)
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            k.label, color = AgText, fontFamily = MonoFontFamily,
            fontWeight = if (k.arrow != null || k.label == "/") FontWeight.Bold else FontWeight.Medium,
            fontSize = if (k.arrow != null) 14.sp else 12.sp, maxLines = 1, softWrap = false
          )
        }
      }
    }
  }
}

@Composable
fun ExpandedFunctionKeysPanel(
  visible: Boolean,
  ctrlActive: Boolean,
  altActive: Boolean,
  onToggleCtrl: () -> Unit,
  onToggleAlt: () -> Unit,
  onSendSequence: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(visible, modifier, expandVertically() + fadeIn(), shrinkVertically() + fadeOut()) {
    Column(Modifier.fillMaxWidth().background(AgPanel)) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), Arrangement.spacedBy(4.dp)) {
        FnKeysRow(FN_ROW_1, ctrlActive, altActive, onToggleCtrl, onToggleAlt, onSendSequence)
        FnKeysRow(FN_ROW_2, ctrlActive, altActive, onToggleCtrl, onToggleAlt, onSendSequence)
      }
      HorizontalDivider(color = AgBorder)
    }
  }
}

@Composable
private fun FnKeysRow(
  keys: List<FnKey>, ctrlActive: Boolean, altActive: Boolean,
  onToggleCtrl: () -> Unit, onToggleAlt: () -> Unit, onSendSequence: (String) -> Unit
) {
  val haptic = LocalHapticFeedback.current
  Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
    keys.forEach { k ->
      val active = (k.ctrl && ctrlActive) || (k.alt && altActive)
      val bg = if (active) AgAccent else if (k.red) Color(0xFF311B24) else AgElevated
      val border = if (active) AgCyan else if (k.red) Color(0xFF7F1D1D) else AgBorder
      val fg = if (active) Color.White else if (k.red) Color(0xFFFCA5A5) else AgText
      Surface(
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          when { k.ctrl -> onToggleCtrl(); k.alt -> onToggleAlt(); else -> onSendSequence(k.seq) }
        },
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.weight(1f).height(31.dp)
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            k.label, color = fg, fontFamily = MonoFontFamily,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 11.sp, maxLines = 1, softWrap = false
          )
        }
      }
    }
  }
}

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
