package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectState
import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.*
import com.gravityssh.app.terminal.AutocompleteEngine as Ac
import com.gravityssh.app.terminal.AutocompleteParser as Ap
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.theme.*

@Composable
fun TerminalWorkspaceScreen(viewModel: AppViewModel, onJumpToServers: () -> Unit) {
 val vm = viewModel; val vault by vm.vaultState.collectState()
 val tabs by vm.sessionTabs.collectState(); val aid by vm.activeSessionId.collectState()
 val cfg = vault.data.settings; val clip = LocalClipboardManager.current; val mgr = vm.container.sshSessionManager

 var fSp by remember(cfg.terminalFontSize) { mutableFloatStateOf(cfg.terminalFontSize.toFloat()) }
 var cOn by remember { mutableStateOf(false) }; var aOn by remember { mutableStateOf(false) }
 var mk by remember { mutableStateOf(false) }; var sr by remember { mutableStateOf(false) }
 var sq by remember { mutableStateOf("") }; var sf by remember { mutableStateOf(false) }
 var dt by remember { mutableStateOf<Int?>(null) }
 var ps by remember { mutableStateOf<Pair<String, PasteInspection>?>(null) }
 var sel by remember(aid) { mutableStateOf<TextSelection?>(null) }
 var off by remember(aid) { mutableIntStateOf(0) }
 var dis by remember(aid) { mutableStateOf<String?>(null) }

 val tab = tabs.find { it.sessionId == aid } ?: tabs.firstOrNull()
 val ctrl = tab?.let { mgr.getTerminalController(it.sessionId) }
 if (tab == null || ctrl == null) { EmptyTerminalPlaceholder(onJumpToServers); return }
 val sid = tab.sessionId; val cwd = tab.currentDir.trimEnd('/').ifBlank { "/" }
 LaunchedEffect(sid, ctrl) { off = 0; dis = null; sel = null; ctrl.setInputSender { mgr.sendInput(sid, it) } }

 val fv by ctrl.frameVersion.collectState(); val ln by ctrl.currentInputLine.collectState()
 val bk by ctrl.bracketedPaste.collectState(); val alt by ctrl.alternateScreen.collectState()
 val nn by ctrl.nanoDetected.collectState()
 LaunchedEffect(alt) { sel = null }

 val pq = remember(ln, cwd, tab.homeDir) { Ac.resolvePathQuery(ln, cwd, tab.homeDir) }
 val td = pq?.dirToList?.takeIf { it != cwd }
 val ok = !alt && tab.status == SessionStatus.CONNECTED
 var ce by remember(sid, cwd) { mutableStateOf(mgr.peekCachedDirEntries(sid, cwd, true)) }
 var te by remember(sid, td) { mutableStateOf(if (td != null) mgr.peekCachedDirEntries(sid, td, true) else emptyList()) }
 LaunchedEffect(sid, cwd, ok, ln.isEmpty()) { if (ok) ce = mgr.listDirForAutocomplete(sid, cwd, true) }
 LaunchedEffect(sid, td, ok) { if (td != null && ok) te = mgr.listDirForAutocomplete(sid, td, true) }

 val tm = remember(cfg.touchCursorMode) { TouchCursorMode.fromId(cfg.touchCursorMode) }
 val ts = remember(cfg.touchCursorSensitivity) { TouchSensitivity.fromId(cfg.touchCursorSensitivity) }
 val sn = remember(fv, off, sid) { ctrl.snapshot(off) }
 val re = if (td != null) te else ce; val hist = vault.data.history
 val sg = remember(ln, hist, cfg, re, ce, cwd, tab.homeDir, tab.serverId) {
  Ac.suggest(ln, emptyList(), hist, cfg, 3, re, cwd, tab.homeDir, tab.serverId, ce)
 }

 fun send(t: String) { if (sel != null) sel = null; mgr.sendInput(sid, t) }
 fun deliverPaste(t: String) = send(formatPastePayload(t, bk))
 val ac = FloatingAutocompleteState(
  !alt && dis != ln, sg,
  {
   val e = Ac.computeCompletionInsert(ln, it.insertText)
   dis = if (it.insertText.endsWith("/") || it.insertText.endsWith(" ")) null else it.insertText
   send("\u007F".repeat(e.backspaces) + e.insertText)
  },
  { dis = ln }, Ap.activeTokenPrefix(ln, sg.firstOrNull()?.source.orEmpty()), ln
 )

 Column(Modifier.fillMaxSize().background(AgBg).imePadding()) {
  SessionTabsStrip(tabs, sid, mgr::selectSession, mgr::closeSession)
  TerminalToolbarRow(
   tab.viaSummary, sr, tm, ts, alt, vm::cycleTouchCursorMode, vm::cycleTouchCursorSensitivity,
   { sf = true }, { dt = 0 }, { dt = 2 },
   { requestClipboardPaste(clip.getText()?.text.orEmpty(), cfg.pasteGuardEnabled, bk, vm::showToast, ::deliverPaste) { r, i -> ps = r to i } },
   { sr = !sr; if (!sr) sq = "" }, { mgr.reconnectSession(sid) },
   mk, cOn, aOn, { mk = !mk }, { cOn = !cOn }, { aOn = !aOn }, { off = 0; send(it) }
  )
  if (sr) TerminalSearchStrip(sq) { sq = it }
  if (cfg.monitorBarEnabled && tab.metrics != null) MonitorBar(tab.metrics)
  HorizontalDivider(color = AgBorder)
  TerminalCanvas(
   sn, ctrl, tm, ts, cfg.touchCursorFling, fSp, sq, off, ac, sel,
   { off = it }, { fSp = it }, { c, r -> mgr.resizeSession(sid, c, r) },
   { raw -> val o = applyTerminalModifiers(raw, cOn, aOn); cOn = false; aOn = false; off = 0; send(o) },
   { sel = it },
   { t -> if (t.isNotEmpty()) { clip.setText(AnnotatedString(t)); vm.showToast("已复制") } else vm.showToast("选中内容为空") },
   Modifier.weight(1f)
  )
  TerminalBottomAccessory(
   alt, nn, { d -> off = 0; sel = null; ctrl.sendArrow(d, 1) },
   { ch -> off = 0; send((ch.uppercaseChar().code - 64).toChar().toString()) }, { off = 0; send(it) }
  )
 }
 TerminalOverlays(
  vm, tab, vault.data, sf, dt, ps, { sf = false },
  { cmd, ex -> mgr.runScript(sid, cmd, ex); dt = null }, { dt = null }, { deliverPaste(it); ps = null }, { ps = null }
 )
}
