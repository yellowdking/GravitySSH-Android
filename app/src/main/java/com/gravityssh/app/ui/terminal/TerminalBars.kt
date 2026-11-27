package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import com.gravityssh.app.ui.components.AgHapticPill
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*

internal data class SessionTabHeader(
    val sessionId: String,
    val serverName: String,
    val status: SessionStatus
)

@Composable
internal fun SessionTabsStrip(tabs: List<SessionTabHeader>, activeId: String, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
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
    imeVisible: Boolean, onToggleKeyboard: () -> Unit,
    onCycleTouchMode: () -> Unit, onCycleTouchSensitivity: () -> Unit, onOpenSftp: () -> Unit, onOpenShortcuts: () -> Unit,
    onOpenHistory: () -> Unit, onPaste: () -> Unit, onToggleSearch: () -> Unit, onReconnect: () -> Unit,
    showMoreKeys: Boolean, ctrlActive: Boolean, altActive: Boolean,
    onToggleMoreKeys: () -> Unit, onToggleCtrl: () -> Unit, onToggleAlt: () -> Unit, onSendSequence: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().background(AgSurface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarIcon(AgIcons.FolderOpen, "SFTP", AgCyan, onOpenSftp)
                BarIcon(AgIcons.Bolt, "快捷命令与模板", AgAccent, onOpenShortcuts)
                BarIcon(AgIcons.History, "命令历史", AgMuted, onOpenHistory)
                BarIcon(AgIcons.ContentPaste, "粘贴", AgText, onPaste, 18)
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
            KeyboardTogglePill(imeVisible = imeVisible, onToggle = onToggleKeyboard)
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
private fun KeyboardTogglePill(imeVisible: Boolean, onToggle: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onToggle()
        },
        modifier = Modifier.height(28.dp),
        shape = RoundedCornerShape(7.dp),
        color = if (imeVisible) AgCyan.copy(0.20f) else AgElevated,
        border = BorderStroke(1.dp, if (imeVisible) AgCyan.copy(0.75f) else AgBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                AgIcons.Keyboard,
                contentDescription = if (imeVisible) "隐藏键盘" else "显示键盘",
                tint = if (imeVisible) AgCyan else AgText,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "键盘",
                color = if (imeVisible) AgCyan else AgText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun FnKeysTogglePill(expanded: Boolean, ctrlActive: Boolean, altActive: Boolean, onToggle: () -> Unit) {
    val active = expanded || ctrlActive || altActive
    val mod = "${if (ctrlActive) "C" else ""}${if (altActive) "A" else ""}"
    val label = if (mod.isNotEmpty()) "功能键·$mod ${if (expanded) "▴" else "▾"}" else "功能键 ${if (expanded) "▴" else "▾"}"
    val dot = if (ctrlActive || altActive) AgCyan else if (expanded) AgAccentLight else AgMuted
    AgHapticPill(
        dotColor = dot,
        text = label,
        textColor = if (active) AgText else AgMuted,
        bgColor = if (active) AgAccent.copy(0.22f) else AgElevated,
        borderColor = if (active) AgAccent.copy(0.75f) else AgBorder,
        onClick = onToggle
    )
}
