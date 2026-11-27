package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.sftp.SftpSheet
import com.gravityssh.app.ui.theme.*

internal fun formatPastePayload(text: String, bracketedPaste: Boolean): String {
    val clean = PasteGuard.sanitizePasteText(text)
    return if (bracketedPaste) "\u001B[200~$clean\u001B[201~" else clean.replace("\n", "\r")
}

internal fun requestClipboardPaste(
    clip: String,
    guardEnabled: Boolean,
    bracketedPaste: Boolean,
    onEmptyToast: (String) -> Unit,
    onDeliver: (String) -> Unit,
    onConfirmNeeded: (String, PasteInspection) -> Unit
) {
    if (clip.isEmpty()) {
        onEmptyToast("剪贴板为空")
    } else if (guardEnabled) {
        val insp = PasteGuard.inspect(clip, bracketedPaste)
        if (insp.needsConfirm) onConfirmNeeded(clip, insp) else onDeliver(clip)
    } else {
        onDeliver(clip)
    }
}

@Composable
internal fun EmptyTerminalPlaceholder(onJumpToServers: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AgBg), contentAlignment = Alignment.Center) {
        AgEmptyState(AgIcons.Terminal, "暂无活动 SSH 终端", "前往主机列表一键连接服务器", "选择主机连接", onJumpToServers)
    }
}

@Composable
internal fun TerminalSearchStrip(query: String, onQueryChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().background(AgPanel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        AgTextField(query, onQueryChange, "搜索屏幕内容", mono = true)
    }
}

@Composable
internal fun TerminalBottomAccessory(
    isAltScreen: Boolean,
    isNano: Boolean,
    onSendArrow: (CursorDir) -> Unit,
    onSendControl: (Char) -> Unit,
    onSendSequence: (String) -> Unit
) {
    NanoQuickActionBar(visible = isNano || isAltScreen, onSendControl = onSendControl, onSendRaw = onSendSequence)
    HorizontalDivider(color = AgBorder)
    ExtraKeysBar(onSendArrow = onSendArrow, onSendSequence = onSendSequence)
}

@Composable
internal fun TerminalOverlays(
    viewModel: AppViewModel,
    activeSessionId: String,
    showSftp: Boolean,
    quickDrawerTab: Int?,
    pendingPaste: Pair<String, PasteInspection>?,
    onDismissSftp: () -> Unit,
    onRunScript: (String, Boolean) -> Unit,
    onDismissDrawer: () -> Unit,
    onConfirmPaste: (String) -> Unit,
    onDismissPaste: () -> Unit
) {
    if (showSftp) {
        val tabs by viewModel.sessionTabs.collectAsStateWithLifecycle()
        val activeTab = tabs.find { it.sessionId == activeSessionId }
        if (activeTab != null) {
            SftpSheet(viewModel = viewModel, session = activeTab, onDismiss = onDismissSftp)
        }
    }
    quickDrawerTab?.let { startTab ->
        val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
        val vaultData = vaultState.data
        TerminalQuickDrawer(startTab, vaultData.shortcuts, vaultData.templates, vaultData.history, onRunScript, onDismissDrawer)
    }
    pendingPaste?.let { (raw, insp) -> PasteGuardDialog(raw, insp, onConfirmPaste, onDismissPaste) }
}
