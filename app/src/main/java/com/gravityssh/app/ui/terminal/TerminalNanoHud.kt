package com.gravityssh.app.ui.terminal

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.gravityssh.app.terminal.TouchCursorMode
import com.gravityssh.app.terminal.TouchSensitivity
import com.gravityssh.app.ui.theme.*
import java.util.Locale

private val HudShape = RoundedCornerShape(7.dp)

@Composable
fun TouchVelocityBadge(
  visible: Boolean,
  zoneLabel: String,
  gain: Float,
  modifier: Modifier = Modifier
) {
  val accent = when (zoneLabel) {
    "精准" -> AgCyan
    "标准" -> AgAccent
    "加速" -> AgWarning
    else -> AgSuccess
  }
  val gainColor = if (accent == AgAccent) AgAccentLight else accent
  AnimatedVisibility(visible, modifier, fadeIn(), fadeOut()) {
    Surface(
      shape = CircleShape,
      color = AgPanel.copy(0.88f),
      border = BorderStroke(1.dp, accent.copy(0.65f))
    ) {
      Row(Modifier.padding(10.dp, 5.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Text(zoneLabel, color = AgText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
          String.format(Locale.US, "%.1fx", gain),
          color = gainColor,
          fontFamily = MonoFontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }
  }
}

@Composable
fun TouchModeControlStrip(
  mode: TouchCursorMode,
  sensitivity: TouchSensitivity,
  isAltScreen: Boolean,
  onCycleMode: () -> Unit,
  onCycleSensitivity: () -> Unit,
  modifier: Modifier = Modifier
) {
  val cursorOn = mode == TouchCursorMode.CURSOR || (mode == TouchCursorMode.AUTO && isAltScreen)
  val modeText = if (mode == TouchCursorMode.AUTO) {
    if (isAltScreen) "触控: 自动(光标)" else "触控: 自动(滚屏)"
  } else "触控: ${mode.label}"
  val sensColor = when (sensitivity) {
    TouchSensitivity.PRECISE -> AgCyan
    TouchSensitivity.BALANCED -> AgAccentLight
    TouchSensitivity.FAST -> AgWarning
  }
  Row(modifier, Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
    HudPill(
      if (cursorOn) AgCyan else AgMuted,
      modeText,
      if (cursorOn) AgText else AgMuted,
      if (cursorOn) AgAccent.copy(0.22f) else AgElevated,
      if (cursorOn) AgAccent.copy(0.75f) else AgBorder,
      onCycleMode
    )
    HudPill(
      sensColor,
      "速度: ${sensitivity.label}",
      AgText,
      AgElevated,
      sensColor.copy(0.45f),
      onCycleSensitivity
    )
  }
}

@Composable
private fun HudPill(
  dot: Color,
  text: String,
  textColor: Color,
  bg: Color,
  border: Color,
  onClick: () -> Unit
) {
  val haptic = LocalHapticFeedback.current
  Surface(
    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
    modifier = Modifier.height(28.dp),
    shape = HudShape,
    color = bg,
    border = BorderStroke(1.dp, border)
  ) {
    Row(Modifier.padding(horizontal = 8.dp), Arrangement.spacedBy(5.dp), Alignment.CenterVertically) {
      Box(Modifier.size(6.dp).background(dot, CircleShape))
      Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
  }
}

private data class NanoKey(val key: String, val label: String, val ctrl: Char? = null, val raw: String? = null, val accent: Color? = null)

private val NANO_KEYS = listOf(
  NanoKey("^O", "保存", ctrl = 'O', accent = AgSuccess),
  NanoKey("^X", "退出", ctrl = 'X', accent = AgDanger),
  NanoKey("^W", "搜索", ctrl = 'W'),
  NanoKey("^K", "剪行", ctrl = 'K'),
  NanoKey("^U", "粘贴", ctrl = 'U'),
  NanoKey("^A", "行首", ctrl = 'A'),
  NanoKey("^E", "行尾", ctrl = 'E'),
  NanoKey("^_", "跳行", raw = "\u001f")
)

@Composable
fun NanoQuickActionBar(
  visible: Boolean,
  onSendControl: (Char) -> Unit,
  onSendRaw: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val haptic = LocalHapticFeedback.current
  AnimatedVisibility(visible, modifier, fadeIn() + expandVertically(), fadeOut() + shrinkVertically()) {
    Row(
      Modifier.fillMaxWidth().background(AgSurface).horizontalScroll(rememberScrollState()).padding(6.dp, 4.dp),
      Arrangement.spacedBy(5.dp),
      Alignment.CenterVertically
    ) {
      NANO_KEYS.forEach { k ->
        val t = k.accent
        Surface(
          onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); k.ctrl?.let(onSendControl) ?: k.raw?.let(onSendRaw) },
          modifier = Modifier.height(30.dp),
          shape = HudShape,
          color = t?.copy(0.16f) ?: AgElevated,
          border = BorderStroke(1.dp, t?.copy(0.55f) ?: AgBorder)
        ) {
          Row(Modifier.padding(horizontal = 8.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
            Text(k.key, color = t ?: AgCyan, fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(k.label, color = AgText, fontWeight = FontWeight.Medium, fontSize = 11.sp)
          }
        }
      }
    }
  }
}
