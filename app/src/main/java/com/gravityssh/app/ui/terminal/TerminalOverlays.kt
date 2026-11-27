package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.sftp.SftpSheet
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class QuickDrawerVaultSlice(
    val shortcuts: List<CommandShortcut>,
    val templates: List<BuiltinTemplate>,
    val history: List<CommandRecord>
)

private val BRACKETED_PASTE_SEQ_RE = Regex("(?:\\u001B\\[|\\u009B)20[01]~")

internal fun formatPastePayload(text: String, bracketedPaste: Boolean): String {
    var clean = PasteGuard.sanitizePasteText(text)
    // Defense-in-depth loop-strip against nested ESC/C1 bracketed-paste breakout payloads
    while (BRACKETED_PASTE_SEQ_RE.containsMatchIn(clean)) {
        clean = clean.replace(BRACKETED_PASTE_SEQ_RE, "")
    }
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
    bracketedPaste: Boolean,
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
        val activeTab by remember(viewModel, activeSessionId) {
            viewModel.sessionTabs
                .map { tabs -> tabs.find { it.sessionId == activeSessionId }?.copy(metrics = null) }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(
            initialValue = viewModel.sessionTabs.value.find { it.sessionId == activeSessionId }?.copy(metrics = null)
        )
        activeTab?.let { tab ->
            SftpSheet(viewModel = viewModel, session = tab, onDismiss = onDismissSftp)
        }
    }
    quickDrawerTab?.let { startTab ->
        val drawerSlice by remember(viewModel) {
            viewModel.vaultState
                .map { QuickDrawerVaultSlice(it.data.shortcuts, it.data.templates, it.data.history) }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(
            initialValue = viewModel.vaultState.value.data.let {
                QuickDrawerVaultSlice(it.shortcuts, it.templates, it.history)
            }
        )
        TerminalQuickDrawer(startTab, drawerSlice.shortcuts, drawerSlice.templates, drawerSlice.history, onRunScript, onDismissDrawer)
    }
    pendingPaste?.let { (raw, insp) ->
        PasteGuardDialog(
            rawText = raw,
            inspection = insp,
            bracketedPaste = bracketedPaste,
            onConfirmPaste = onConfirmPaste,
            onDismiss = onDismissPaste
        )
    }
}
