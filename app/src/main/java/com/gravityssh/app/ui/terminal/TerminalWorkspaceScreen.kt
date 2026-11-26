package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.terminal.AutocompleteEngine
import com.gravityssh.app.terminal.PasteGuard
import com.gravityssh.app.terminal.PasteInspection
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgBorder

@Composable
fun TerminalWorkspaceScreen(viewModel: AppViewModel, onJumpToServers: () -> Unit) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val tabs by viewModel.sessionTabs.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val settings = vaultState.data.settings
    val clipboard = LocalClipboardManager.current
    val mgr = viewModel.container.sshSessionManager

    var fontSizeSp by remember(settings.terminalFontSize) { mutableFloatStateOf(settings.terminalFontSize.toFloat()) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSftp by remember { mutableStateOf(false) }
    var quickDrawerTab by remember { mutableStateOf<Int?>(null) }
    var pendingPaste by remember { mutableStateOf<Pair<String, PasteInspection>?>(null) }
    var scrollOffset by remember(activeId) { mutableIntStateOf(0) }

    val activeTab = tabs.find { it.sessionId == activeId } ?: tabs.firstOrNull()
    val controller = activeTab?.let { mgr.getTerminalController(it.sessionId) }
    if (activeTab == null || controller == null) {
        Box(Modifier.fillMaxSize().background(AgBg), contentAlignment = Alignment.Center) {
            AgEmptyState(Icons.Rounded.Terminal, "暂无活动 SSH 终端", "前往主机列表一键连接服务器", "选择主机连接", onJumpToServers)
        }
        return
    }

    val frameVer by controller.frameVersion.collectAsStateWithLifecycle()
    val inputLine by controller.currentInputLine.collectAsStateWithLifecycle()
    val bracketedPaste by controller.bracketedPaste.collectAsStateWithLifecycle()
    val snapshot = remember(frameVer, scrollOffset, activeTab.sessionId) { controller.snapshot(scrollOffset) }
    val suggestions = remember(inputLine, vaultState.data.shortcuts, vaultState.data.history, settings) {
        AutocompleteEngine.suggest(inputLine, vaultState.data.shortcuts, vaultState.data.history, settings, 8)
    }

    LaunchedEffect(activeTab.sessionId) { scrollOffset = 0 }
    fun deliverPaste(text: String) {
        mgr.sendInput(activeTab.sessionId, formatPastePayload(text, bracketedPaste))
    }

    Column(Modifier.fillMaxSize().background(AgBg).imePadding()) {
        SessionTabsStrip(tabs, activeTab.sessionId, mgr::selectSession, mgr::closeSession)
        TerminalToolbarRow(
            activeTab.viaSummary, showSearch,
            onOpenSftp = { showSftp = true },
            onOpenShortcuts = { quickDrawerTab = 0 },
            onOpenHistory = { quickDrawerTab = 2 },
            onPaste = {
                val clip = clipboard.getText()?.text.orEmpty()
                if (clip.isEmpty()) viewModel.showToast("剪贴板为空")
                else if (settings.pasteGuardEnabled) {
                    val insp = PasteGuard.inspect(clip, bracketedPaste)
                    if (insp.needsConfirm) pendingPaste = clip to insp else deliverPaste(clip)
                } else deliverPaste(clip)
            },
            onToggleSearch = { showSearch = !showSearch; if (!showSearch) searchQuery = "" },
            onReconnect = { mgr.reconnectSession(activeTab.sessionId) }
        )
        if (showSearch) TerminalSearchStrip(searchQuery) { searchQuery = it }
        if (settings.monitorBarEnabled && activeTab.metrics != null) MonitorBar(activeTab.metrics)
        HorizontalDivider(color = AgBorder)

        TerminalCanvas(
            snapshot, fontSizeSp, searchQuery, scrollOffset,
            { scrollOffset = it }, { fontSizeSp = it }, { c, r -> mgr.resizeSession(activeTab.sessionId, c, r) },
            onImeInput = { raw ->
                val out = applyTerminalModifiers(raw, ctrlActive, altActive)
                ctrlActive = false; altActive = false; scrollOffset = 0
                mgr.sendInput(activeTab.sessionId, out)
            },
            onLongPressCopy = {
                clipboard.setText(AnnotatedString(controller.extractPlainText(scrollOffset)))
                viewModel.showToast("已复制当前终端屏幕文本")
            },
            modifier = Modifier.weight(1f)
        )
        AutocompleteBar(suggestions) { item ->
            val e = AutocompleteEngine.computeCompletionInsert(inputLine, item.insertText)
            mgr.sendInput(activeTab.sessionId, "\u007F".repeat(e.backspaces) + e.insertText)
        }
        HorizontalDivider(color = AgBorder)
        ExtraKeysBar(ctrlActive, altActive, { ctrlActive = !ctrlActive }, { altActive = !altActive }) {
            scrollOffset = 0; mgr.sendInput(activeTab.sessionId, it)
        }
    }

    TerminalOverlays(
        viewModel, activeTab, vaultState.data, showSftp, quickDrawerTab, pendingPaste,
        { showSftp = false },
        { cmd, exec -> mgr.runScript(activeTab.sessionId, cmd, exec); quickDrawerTab = null },
        { quickDrawerTab = null },
        { deliverPaste(it); pendingPaste = null },
        { pendingPaste = null }
    )
}
