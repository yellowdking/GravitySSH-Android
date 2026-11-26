package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.model.VaultData
import com.gravityssh.app.terminal.PasteGuard
import com.gravityssh.app.terminal.PasteInspection
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.sftp.SftpSheet
import com.gravityssh.app.ui.theme.AgPanel

internal fun formatPastePayload(text: String, bracketedPaste: Boolean): String {
    val clean = PasteGuard.sanitizePasteText(text)
    return if (bracketedPaste) "\u001B[200~$clean\u001B[201~" else clean.replace("\n", "\r")
}

@Composable
internal fun TerminalSearchStrip(query: String, onQueryChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().background(AgPanel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        AgTextField(query, onQueryChange, "搜索屏幕内容", mono = true)
    }
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
    if (showSftp) {
        SftpSheet(viewModel = viewModel, session = activeTab, onDismiss = onDismissSftp)
    }
    quickDrawerTab?.let { startTab ->
        TerminalQuickDrawer(
            initialTab = startTab,
            shortcuts = vaultData.shortcuts,
            templates = vaultData.templates,
            history = vaultData.history,
            onRunCommand = onRunScript,
            onDismiss = onDismissDrawer
        )
    }
    pendingPaste?.let { (raw, insp) ->
        PasteGuardDialog(
            rawText = raw,
            inspection = insp,
            onConfirmPaste = onConfirmPaste,
            onDismiss = onDismissPaste
        )
    }
}
