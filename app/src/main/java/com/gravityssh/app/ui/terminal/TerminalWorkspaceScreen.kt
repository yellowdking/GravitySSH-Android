package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cycleTouchCursorMode
import com.gravityssh.app.ui.cycleTouchCursorSensitivity
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
        EmptyTerminalPlaceholder(onJumpToServers)
        return
    }

    LaunchedEffect(activeTab.sessionId, controller) {
        scrollOffset = 0
        controller.setInputSender { seq -> mgr.sendInput(activeTab.sessionId, seq) }
    }

    val frameVer by controller.frameVersion.collectAsStateWithLifecycle()
    val inputLine by controller.currentInputLine.collectAsStateWithLifecycle()
    val bracketedPaste by controller.bracketedPaste.collectAsStateWithLifecycle()
    val isAlt by controller.alternateScreen.collectAsStateWithLifecycle()
    val isNano by controller.nanoDetected.collectAsStateWithLifecycle()
    val touchMode = remember(settings.touchCursorMode) { TouchCursorMode.fromId(settings.touchCursorMode) }
    val touchSens = remember(settings.touchCursorSensitivity) { TouchSensitivity.fromId(settings.touchCursorSensitivity) }
    val snapshot = remember(frameVer, scrollOffset, activeTab.sessionId) { controller.snapshot(scrollOffset) }
    val suggestions = remember(inputLine, vaultState.data.shortcuts, vaultState.data.history, settings) {
        AutocompleteEngine.suggest(inputLine, vaultState.data.shortcuts, vaultState.data.history, settings, 8)
    }

    fun send(text: String) = mgr.sendInput(activeTab.sessionId, text)
    fun deliverPaste(text: String) = send(formatPastePayload(text, bracketedPaste))

    Column(Modifier.fillMaxSize().background(AgBg).imePadding()) {
        SessionTabsStrip(tabs, activeTab.sessionId, mgr::selectSession, mgr::closeSession)
        TerminalToolbarRow(
            activeTab.viaSummary, showSearch, touchMode, touchSens, isAlt,
            { viewModel.cycleTouchCursorMode() }, { viewModel.cycleTouchCursorSensitivity() },
            { showSftp = true }, { quickDrawerTab = 0 }, { quickDrawerTab = 2 },
            {
                requestClipboardPaste(
                    clipboard.getText()?.text.orEmpty(), settings.pasteGuardEnabled, bracketedPaste,
                    viewModel::showToast, ::deliverPaste
                ) { raw, insp -> pendingPaste = raw to insp }
            },
            { showSearch = !showSearch; if (!showSearch) searchQuery = "" },
            { mgr.reconnectSession(activeTab.sessionId) }
        )
        if (showSearch) TerminalSearchStrip(searchQuery) { searchQuery = it }
        if (settings.monitorBarEnabled && activeTab.metrics != null) MonitorBar(activeTab.metrics)
        HorizontalDivider(color = AgBorder)

        TerminalCanvas(
            snapshot, controller, touchMode, touchSens, settings.touchCursorFling, fontSizeSp, searchQuery, scrollOffset,
            { scrollOffset = it }, { fontSizeSp = it }, { c, r -> mgr.resizeSession(activeTab.sessionId, c, r) },
            { raw ->
                val out = applyTerminalModifiers(raw, ctrlActive, altActive)
                ctrlActive = false; altActive = false; scrollOffset = 0; send(out)
            },
            {
                clipboard.setText(AnnotatedString(controller.extractPlainText(scrollOffset)))
                viewModel.showToast("已复制当前终端屏幕文本")
            },
            Modifier.weight(1f)
        )
        TerminalBottomAccessory(
            isAlt, isNano, suggestions, ctrlActive, altActive,
            { item ->
                val e = AutocompleteEngine.computeCompletionInsert(inputLine, item.insertText)
                send("\u007F".repeat(e.backspaces) + e.insertText)
            },
            { ctrlActive = !ctrlActive }, { altActive = !altActive },
            { dir -> scrollOffset = 0; controller.sendArrow(dir, 1) },
            { ch -> scrollOffset = 0; send((ch.uppercaseChar().code - 64).toChar().toString()) },
            { scrollOffset = 0; send(it) }
        )
    }

    TerminalOverlays(
        viewModel, activeTab, vaultState.data, showSftp, quickDrawerTab, pendingPaste,
        { showSftp = false }, { cmd, exec -> mgr.runScript(activeTab.sessionId, cmd, exec); quickDrawerTab = null },
        { quickDrawerTab = null }, { deliverPaste(it); pendingPaste = null }, { pendingPaste = null }
    )
}
