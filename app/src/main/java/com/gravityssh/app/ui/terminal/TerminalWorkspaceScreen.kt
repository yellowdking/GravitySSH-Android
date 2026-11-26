package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.*
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.theme.*

@Composable
fun TerminalWorkspaceScreen(viewModel: AppViewModel, onJumpToServers: () -> Unit) {
  val vault by viewModel.vaultState.collectAsStateWithLifecycle()
  val tabs by viewModel.sessionTabs.collectAsStateWithLifecycle()
  val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
  val cfg = vault.data.settings; val clip = LocalClipboardManager.current; val mgr = viewModel.container.sshSessionManager

  var fontSp by remember(cfg.terminalFontSize) { mutableFloatStateOf(cfg.terminalFontSize.toFloat()) }
  var ctrlOn by remember { mutableStateOf(false) }; var altOn by remember { mutableStateOf(false) }
  var moreKeys by remember { mutableStateOf(false) }; var showSrch by remember { mutableStateOf(false) }
  var srchQ by remember { mutableStateOf("") }; var showSftp by remember { mutableStateOf(false) }
  var dTab by remember { mutableStateOf<Int?>(null) }
  var paste by remember { mutableStateOf<Pair<String, PasteInspection>?>(null) }
  var sel by remember(activeId) { mutableStateOf<TextSelection?>(null) }
  var scrollOff by remember(activeId) { mutableIntStateOf(0) }
  var dismissed by remember(activeId) { mutableStateOf<String?>(null) }

  val tab = tabs.find { it.sessionId == activeId } ?: tabs.firstOrNull()
  val ctrl = tab?.let { mgr.getTerminalController(it.sessionId) }
  if (tab == null || ctrl == null) { EmptyTerminalPlaceholder(onJumpToServers); return }
  val sid = tab.sessionId

  LaunchedEffect(sid, ctrl) {
    scrollOff = 0; dismissed = null; sel = null
    ctrl.setInputSender { mgr.sendInput(sid, it) }
  }

  val fVer by ctrl.frameVersion.collectAsStateWithLifecycle()
  val inLine by ctrl.currentInputLine.collectAsStateWithLifecycle()
  val brkt by ctrl.bracketedPaste.collectAsStateWithLifecycle()
  val isAlt by ctrl.alternateScreen.collectAsStateWithLifecycle()
  val isNano by ctrl.nanoDetected.collectAsStateWithLifecycle()
  LaunchedEffect(isAlt) { sel = null }

  val pq = remember(inLine, tab.currentDir) { AutocompleteEngine.resolvePathQuery(inLine, tab.currentDir) }
  val hidden = pq?.namePrefix?.startsWith(".") == true
  var entries by remember(sid) { mutableStateOf<List<SFTPFileEntry>>(emptyList()) }
  LaunchedEffect(sid, tab.currentDir, tab.status, pq?.dirToList, hidden, isAlt) {
    if (!isAlt && tab.status == SessionStatus.CONNECTED) {
      entries = mgr.listDirForAutocomplete(sid, pq?.dirToList ?: tab.currentDir.ifBlank { "/" }, hidden)
    }
  }

  val tMode = remember(cfg.touchCursorMode) { TouchCursorMode.fromId(cfg.touchCursorMode) }
  val tSens = remember(cfg.touchCursorSensitivity) { TouchSensitivity.fromId(cfg.touchCursorSensitivity) }
  val snap = remember(fVer, scrollOff, sid) { ctrl.snapshot(scrollOff) }
  val suggs = remember(inLine, vault.data.history, cfg, entries, tab.currentDir) {
    AutocompleteEngine.suggest(input = inLine, history = vault.data.history, settings = cfg, limit = 3, remoteEntries = entries, currentDir = tab.currentDir)
  }

  fun send(text: String) { if (sel != null) sel = null; mgr.sendInput(sid, text) }
  fun deliverPaste(text: String) = send(formatPastePayload(text, brkt))
  val acState = FloatingAutocompleteState(!isAlt && dismissed != inLine, suggs, {
    val e = AutocompleteEngine.computeCompletionInsert(inLine, it.insertText)
    send("\u007F".repeat(e.backspaces) + e.insertText)
  }, { dismissed = inLine })

  Column(Modifier.fillMaxSize().background(AgBg).imePadding()) {
    SessionTabsStrip(tabs, sid, mgr::selectSession, mgr::closeSession)
    TerminalToolbarRow(
      tab.viaSummary, showSrch, tMode, tSens, isAlt,
      { viewModel.cycleTouchCursorMode() }, { viewModel.cycleTouchCursorSensitivity() },
      { showSftp = true }, { dTab = 0 }, { dTab = 2 },
      { requestClipboardPaste(clip.getText()?.text.orEmpty(), cfg.pasteGuardEnabled, brkt, viewModel::showToast, ::deliverPaste) { r, i -> paste = r to i } },
      { showSrch = !showSrch; if (!showSrch) srchQ = "" }, { mgr.reconnectSession(sid) },
      moreKeys, ctrlOn, altOn, { moreKeys = !moreKeys }, { ctrlOn = !ctrlOn }, { altOn = !altOn },
      { scrollOff = 0; send(it) }
    )
    if (showSrch) TerminalSearchStrip(srchQ) { srchQ = it }
    if (cfg.monitorBarEnabled && tab.metrics != null) MonitorBar(tab.metrics)
    HorizontalDivider(color = AgBorder)

    TerminalCanvas(
      snap, ctrl, tMode, tSens, cfg.touchCursorFling, fontSp, srchQ, scrollOff,
      acState, sel, { scrollOff = it }, { fontSp = it }, { c, r -> mgr.resizeSession(sid, c, r) },
      { raw -> val out = applyTerminalModifiers(raw, ctrlOn, altOn); ctrlOn = false; altOn = false; scrollOff = 0; send(out) },
      { sel = it },
      { t -> if (t.isNotEmpty()) { clip.setText(AnnotatedString(t)); viewModel.showToast("已复制") } else viewModel.showToast("选中内容为空") },
      Modifier.weight(1f)
    )
    TerminalBottomAccessory(
      isAlt, isNano,
      { dir -> scrollOff = 0; sel = null; ctrl.sendArrow(dir, 1) },
      { ch -> scrollOff = 0; send((ch.uppercaseChar().code - 64).toChar().toString()) },
      { scrollOff = 0; send(it) }
    )
  }

  TerminalOverlays(
    viewModel, tab, vault.data, showSftp, dTab, paste,
    { showSftp = false }, { cmd, exec -> mgr.runScript(sid, cmd, exec); dTab = null },
    { dTab = null }, { deliverPaste(it); paste = null }, { paste = null }
  )
}
