package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.theme.*

@Composable
internal fun SessionTabsStrip(tabs: List<SessionTab>, activeId: String, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
  Row(Modifier.fillMaxWidth().background(AgSurface).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp), Arrangement.spacedBy(6.dp)) {
    tabs.forEach { tab ->
      val sel = tab.sessionId == activeId
      val dot = when (tab.status) {
        SessionStatus.CONNECTED -> AgSuccess
        SessionStatus.CONNECTING -> AgWarning
        SessionStatus.DISCONNECTED -> AgMuted
        SessionStatus.ERROR -> AgDanger
      }
      Surface(
        shape = RoundedCornerShape(8.dp), color = if (sel) AgElevated else AgPanel,
        border = BorderStroke(1.dp, if (sel) AgAccent else AgBorder),
        modifier = Modifier.height(32.dp).clickable { onSelect(tab.sessionId) }
      ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
          Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
          Text(tab.serverName, style = MaterialTheme.typography.labelMedium, color = if (sel) AgText else AgMuted, maxLines = 1)
          IconButton(onClick = { onClose(tab.sessionId) }, modifier = Modifier.size(22.dp)) { Icon(Icons.Rounded.Close, "关闭会话", tint = AgMuted, modifier = Modifier.size(13.dp)) }
        }
      }
    }
  }
}

@Composable
internal fun TerminalToolbarRow(
  viaSummary: String, showSearch: Boolean, touchMode: TouchCursorMode, touchSensitivity: TouchSensitivity, isAltScreen: Boolean,
  onCycleTouchMode: () -> Unit, onCycleTouchSensitivity: () -> Unit, onOpenSftp: () -> Unit, onOpenShortcuts: () -> Unit,
  onOpenHistory: () -> Unit, onPaste: () -> Unit, onToggleSearch: () -> Unit, onReconnect: () -> Unit,
  showMoreKeys: Boolean, ctrlActive: Boolean, altActive: Boolean,
  onToggleMoreKeys: () -> Unit, onToggleCtrl: () -> Unit, onToggleAlt: () -> Unit, onSendSequence: (String) -> Unit
) {
  Column(Modifier.fillMaxWidth().background(AgSurface)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Icons.Rounded.FolderOpen, "SFTP", AgCyan, onOpenSftp)
        BarIcon(Icons.Rounded.Bolt, "快捷命令与模板", AgAccent, onOpenShortcuts)
        BarIcon(Icons.Rounded.History, "命令历史", AgMuted, onOpenHistory)
        BarIcon(Icons.Rounded.ContentPaste, "粘贴", AgText, onPaste, 18)
        BarIcon(Icons.Rounded.Search, "搜索", if (showSearch) AgCyan else AgMuted, onToggleSearch)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (viaSummary.isNotBlank()) {
          Text(viaSummary, style = MaterialTheme.typography.labelSmall, color = AgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Spacer(Modifier.width(4.dp))
        }
        BarIcon(Icons.Rounded.Refresh, "重连", AgMuted, onReconnect)
      }
    }
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
      Arrangement.spacedBy(6.dp), Alignment.CenterVertically
    ) {
      TouchModeControlStrip(touchMode, touchSensitivity, isAltScreen, onCycleTouchMode, onCycleTouchSensitivity)
      FnKeysTogglePill(showMoreKeys, ctrlActive, altActive, onToggleMoreKeys)
    }
    ExpandedFunctionKeysPanel(showMoreKeys, ctrlActive, altActive, onToggleCtrl, onToggleAlt, onSendSequence)
  }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit, sz: Int = 19) {
  IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { Icon(icon, desc, tint = tint, modifier = Modifier.size(sz.dp)) }
}

@Composable
private fun FnKeysTogglePill(expanded: Boolean, ctrlActive: Boolean, altActive: Boolean, onToggle: () -> Unit) {
  val haptic = LocalHapticFeedback.current
  val active = expanded || ctrlActive || altActive
  val mod = "${if (ctrlActive) "C" else ""}${if (altActive) "A" else ""}"
  val label = if (mod.isNotEmpty()) "功能键·$mod ${if (expanded) "▴" else "▾"}" else "功能键 ${if (expanded) "▴" else "▾"}"
  val dot = if (ctrlActive || altActive) AgCyan else if (expanded) AgAccentLight else AgMuted
  Surface(
    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onToggle() },
    modifier = Modifier.height(28.dp), shape = RoundedCornerShape(7.dp),
    color = if (active) AgAccent.copy(0.22f) else AgElevated,
    border = BorderStroke(1.dp, if (active) AgAccent.copy(0.75f) else AgBorder)
  ) {
    Row(Modifier.padding(horizontal = 8.dp), Arrangement.spacedBy(5.dp), Alignment.CenterVertically) {
      Box(Modifier.size(6.dp).background(dot, CircleShape))
      Text(label, color = if (active) AgText else AgMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
  }
}
