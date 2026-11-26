package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SessionStatus
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.ui.theme.*

@Composable
internal fun SessionTabsStrip(
    tabs: List<SessionTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(AgSurface).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEach { tab ->
            val selected = tab.sessionId == activeId
            val dotColor = when (tab.status) {
                SessionStatus.CONNECTED -> AgSuccess
                SessionStatus.CONNECTING -> AgWarning
                SessionStatus.DISCONNECTED -> AgMuted
                SessionStatus.ERROR -> AgDanger
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) AgElevated else AgPanel,
                border = BorderStroke(1.dp, if (selected) AgAccent else AgBorder),
                modifier = Modifier.height(32.dp).clickable { onSelect(tab.sessionId) }
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                    Text(tab.serverName, style = MaterialTheme.typography.labelMedium, color = if (selected) AgText else AgMuted, maxLines = 1)
                    IconButton(onClick = { onClose(tab.sessionId) }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Rounded.Close, "关闭会话", tint = AgMuted, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TerminalToolbarRow(
    viaSummary: String,
    showSearch: Boolean,
    onOpenSftp: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onOpenHistory: () -> Unit,
    onPaste: () -> Unit,
    onToggleSearch: () -> Unit,
    onReconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenSftp, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.FolderOpen, "SFTP", tint = AgCyan, modifier = Modifier.size(19.dp)) }
            IconButton(onClick = onOpenShortcuts, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Bolt, "快捷命令与模板", tint = AgAccent, modifier = Modifier.size(19.dp)) }
            IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.History, "命令历史", tint = AgMuted, modifier = Modifier.size(19.dp)) }
            IconButton(onClick = onPaste, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.ContentPaste, "粘贴", tint = AgText, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onToggleSearch, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Search, "搜索", tint = if (showSearch) AgCyan else AgMuted, modifier = Modifier.size(19.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (viaSummary.isNotBlank()) {
                Text(viaSummary, style = MaterialTheme.typography.labelSmall, color = AgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onReconnect, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Refresh, "重连", tint = AgMuted, modifier = Modifier.size(19.dp)) }
        }
    }
}
