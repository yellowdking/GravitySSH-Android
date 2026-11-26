package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.CompletionItem
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.model.VaultData
import com.gravityssh.app.terminal.CursorDir
import com.gravityssh.app.terminal.PasteGuard
import com.gravityssh.app.terminal.PasteInspection
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.sftp.SftpSheet
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgBorder
import com.gravityssh.app.ui.theme.AgPanel

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
        AgEmptyState(Icons.Rounded.Terminal, "暂无活动 SSH 终端", "前往主机列表一键连接服务器", "选择主机连接", onJumpToServers)
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
    suggestions: List<CompletionItem>,
    ctrlActive: Boolean,
    altActive: Boolean,
    onPickCompletion: (CompletionItem) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendArrow: (CursorDir) -> Unit,
    onSendControl: (Char) -> Unit,
    onSendSequence: (String) -> Unit
) {
    if (!isAltScreen) {
        AutocompleteBar(suggestions = suggestions, onPick = onPickCompletion)
    }
    NanoQuickActionBar(visible = isNano || isAltScreen, onSendControl = onSendControl, onSendRaw = onSendSequence)
    HorizontalDivider(color = AgBorder)
    ExtraKeysBar(
        ctrlActive = ctrlActive,
        altActive = altActive,
        onToggleCtrl = onToggleCtrl,
        onToggleAlt = onToggleAlt,
        onSendArrow = onSendArrow,
        onSendSequence = onSendSequence
    )
}

@Composable
internal fun TerminalOverlays(
    viewModel: AppViewModel,
    activeTab: SessionTab,
    vaultData: VaultData,
    showSftp: Boolean,
    quickDrawerTab: Int?,
    pendingPaste: Pair<String, PasteInspection>?,
    onDismissSftp: () -> Unit,
    onRunScript: (String, Boolean) -> Unit,
    onDismissDrawer: () -> Unit,
    onConfirmPaste: (String) -> Unit,
    onDismissPaste: () -> Unit
) {
    if (showSftp) SftpSheet(viewModel = viewModel, session = activeTab, onDismiss = onDismissSftp)
    quickDrawerTab?.let { startTab ->
        TerminalQuickDrawer(startTab, vaultData.shortcuts, vaultData.templates, vaultData.history, onRunScript, onDismissDrawer)
    }
    pendingPaste?.let { (raw, insp) -> PasteGuardDialog(raw, insp, onConfirmPaste, onDismissPaste) }
}
