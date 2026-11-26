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
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.theme.*

@Composable
fun TerminalWorkspaceScreen(viewModel: AppViewModel, onJumpToServers: () -> Unit) {
  val vault by viewModel.vaultState.collectAsStateWithLifecycle()
  val tabs by viewModel.sessionTabs.collectAsStateWithLifecycle()
  val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
  val cfg = vault.data.settings
  val clipboard = LocalClipboardManager.current
  val mgr = viewModel.container.sshSessionManager

  var fontSp by remember(cfg.terminalFontSize) { mutableFloatStateOf(cfg.terminalFontSize.toFloat()) }
  var ctrlOn by remember { mutableStateOf(false) }
  var altOn by remember { mutableStateOf(false) }
  var showMoreKeys by remember { mutableStateOf(false) }
  var showSearch by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var showSftp by remember { mutableStateOf(false) }
  var drawerTab by remember { mutableStateOf<Int?>(null) }
  var pendingPaste by remember { mutableStateOf<Pair<String, PasteInspection>?>(null) }
  var copyPayload by remember(activeId) { mutableStateOf<Pair<String, String>?>(null) }
  var scrollOffset by remember(activeId) { mutableIntStateOf(0) }
  var dismissedLine by remember(activeId) { mutableStateOf<String?>(null) }

  val tab = tabs.find { it.sessionId == activeId } ?: tabs.firstOrNull()
  val ctrl = tab?.let { mgr.getTerminalController(it.sessionId) }
  if (tab == null || ctrl == null) { EmptyTerminalPlaceholder(onJumpToServers); return }

  LaunchedEffect(tab.sessionId, ctrl) {
    scrollOffset = 0; dismissedLine = null
    ctrl.setInputSender { seq -> mgr.sendInput(tab.sessionId, seq) }
  }

  val frameVer by ctrl.frameVersion.collectAsStateWithLifecycle()
  val inputLine by ctrl.currentInputLine.collectAsStateWithLifecycle()
  val bracketed by ctrl.bracketedPaste.collectAsStateWithLifecycle()
  val isAlt by ctrl.alternateScreen.collectAsStateWithLifecycle()
  val isNano by ctrl.nanoDetected.collectAsStateWithLifecycle()
  val touchMode = remember(cfg.touchCursorMode) { TouchCursorMode.fromId(cfg.touchCursorMode) }
  val touchSens = remember(cfg.touchCursorSensitivity) { TouchSensitivity.fromId(cfg.touchCursorSensitivity) }
  val snapshot = remember(frameVer, scrollOffset, tab.sessionId) { ctrl.snapshot(scrollOffset) }
  val suggestions = remember(inputLine, vault.data.shortcuts, vault.data.history, cfg) {
    AutocompleteEngine.suggest(inputLine, vault.data.shortcuts, vault.data.history, cfg, 3)
  }

  fun send(text: String) = mgr.sendInput(tab.sessionId, text)
  fun deliverPaste(text: String) = send(formatPastePayload(text, bracketed))
  val acState = FloatingAutocompleteState(!isAlt && dismissedLine != inputLine, suggestions, {
    val e = AutocompleteEngine.computeCompletionInsert(inputLine, it.insertText)
    send("\u007F".repeat(e.backspaces) + e.insertText)
  }, { dismissedLine = inputLine })

  Column(Modifier.fillMaxSize().background(AgBg).imePadding()) {
    SessionTabsStrip(tabs, tab.sessionId, mgr::selectSession, mgr::closeSession)
    TerminalToolbarRow(
      tab.viaSummary, showSearch, touchMode, touchSens, isAlt,
      { viewModel.cycleTouchCursorMode() }, { viewModel.cycleTouchCursorSensitivity() },
      { showSftp = true }, { drawerTab = 0 }, { drawerTab = 2 },
      { requestClipboardPaste(clipboard.getText()?.text.orEmpty(), cfg.pasteGuardEnabled, bracketed, viewModel::showToast, ::deliverPaste) { r, i -> pendingPaste = r to i } },
      { showSearch = !showSearch; if (!showSearch) searchQuery = "" },
      { mgr.reconnectSession(tab.sessionId) },
      showMoreKeys, ctrlOn, altOn,
      { showMoreKeys = !showMoreKeys }, { ctrlOn = !ctrlOn }, { altOn = !altOn },
      { scrollOffset = 0; send(it) }
    )
    if (showSearch) TerminalSearchStrip(searchQuery) { searchQuery = it }
    if (cfg.monitorBarEnabled && tab.metrics != null) MonitorBar(tab.metrics)
    HorizontalDivider(color = AgBorder)

    TerminalCanvas(
      snapshot, ctrl, touchMode, touchSens, cfg.touchCursorFling, fontSp, searchQuery, scrollOffset,
      acState, { scrollOffset = it }, { fontSp = it }, { c, r -> mgr.resizeSession(tab.sessionId, c, r) },
      { raw -> val out = applyTerminalModifiers(raw, ctrlOn, altOn); ctrlOn = false; altOn = false; scrollOffset = 0; send(out) },
      { row, _ -> copyPayload = extractSnapshotLine(snapshot, row) to ctrl.extractPlainText(scrollOffset) },
      Modifier.weight(1f)
    )
    TerminalBottomAccessory(
      isAlt, isNano,
      { dir -> scrollOffset = 0; ctrl.sendArrow(dir, 1) },
      { ch -> scrollOffset = 0; send((ch.uppercaseChar().code - 64).toChar().toString()) },
      { scrollOffset = 0; send(it) }
    )
  }

  TerminalOverlays(
    viewModel, tab, vault.data, showSftp, drawerTab, pendingPaste, copyPayload,
    { showSftp = false }, { cmd, exec -> mgr.runScript(tab.sessionId, cmd, exec); drawerTab = null },
    { drawerTab = null }, { deliverPaste(it); pendingPaste = null }, { pendingPaste = null },
    { text, msg -> clipboard.setText(AnnotatedString(text)); viewModel.showToast(msg); copyPayload = null },
    { copyPayload = null }
  )
}
