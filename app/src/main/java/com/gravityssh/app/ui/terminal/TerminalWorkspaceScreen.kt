package com.gravityssh.app.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectState
import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.*
import com.gravityssh.app.terminal.AutocompleteEngine as Ac
import com.gravityssh.app.terminal.AutocompleteParser as Ap
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private data class ActiveTabMeta(
    val sessionId: String,
    val serverId: String,
    val serverName: String,
    val homeDir: String,
    val currentDir: String,
    val viaSummary: String,
    val status: SessionStatus
)

private fun resolveActiveTabMeta(tabs: List<SessionTab>, activeId: String?): ActiveTabMeta? =
    (tabs.find { it.sessionId == activeId } ?: tabs.firstOrNull())?.let {
        ActiveTabMeta(
            sessionId = it.sessionId,
            serverId = it.serverId,
            serverName = it.serverName,
            homeDir = it.homeDir,
            currentDir = it.currentDir,
            viaSummary = it.viaSummary,
            status = it.status
        )
    }

@Composable
fun TerminalWorkspaceScreen(viewModel: AppViewModel, onJumpToServers: () -> Unit) {
    val vm = viewModel
    val cfg by remember(vm) {
        vm.vaultState.map { it.data.settings }.distinctUntilChanged()
    }.collectState(initialValue = vm.vaultState.value.data.settings)
    val hist by remember(vm, cfg.autocompleteEnabled, cfg.autocompleteFromHistory) {
        if (cfg.autocompleteEnabled && cfg.autocompleteFromHistory) {
            vm.vaultState.map { it.data.history }.distinctUntilChanged()
        } else {
            flowOf(emptyList<CommandRecord>())
        }
    }.collectState(
        initialValue = if (cfg.autocompleteEnabled && cfg.autocompleteFromHistory) {
            vm.vaultState.value.data.history
        } else {
            emptyList()
        }
    )
    val initialTabMeta = remember(vm) {
        resolveActiveTabMeta(vm.sessionTabs.value, vm.activeSessionId.value)
    }
    val tab by remember(vm) {
        combine(vm.sessionTabs, vm.activeSessionId, ::resolveActiveTabMeta)
            .distinctUntilChanged()
    }.collectAsState(initial = initialTabMeta)

    val clip = LocalClipboardManager.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeFocusRequester = remember { FocusRequester() }
    val mgr = vm.container.sshSessionManager

    val sid = tab?.sessionId
    var fSp by remember(cfg.terminalFontSize) { mutableFloatStateOf(cfg.terminalFontSize.toFloat()) }
    var cOn by remember(sid) { mutableStateOf(false) }
    var aOn by remember(sid) { mutableStateOf(false) }
    var mk by remember(sid) { mutableStateOf(false) }
    var sr by remember(sid) { mutableStateOf(false) }
    var sq by remember(sid) { mutableStateOf("") }
    var sf by remember(sid) { mutableStateOf(false) }
    var dt by remember(sid) { mutableStateOf<Int?>(null) }
    var ps by remember(sid) { mutableStateOf<Pair<String, PasteInspection>?>(null) }
    var sel by remember(sid) { mutableStateOf<TextSelection?>(null) }
    var off by remember(sid) { mutableIntStateOf(0) }
    var dis by remember(sid) { mutableStateOf<String?>(null) }

    val ctrl = tab?.let { mgr.getTerminalController(it.sessionId) }
    if (tab == null || ctrl == null) {
        EmptyTerminalPlaceholder(onJumpToServers)
        return
    }
    val activeTab = tab!!
    val activeSid = activeTab.sessionId
    val cwd = activeTab.currentDir.trimEnd('/').ifBlank { "/" }
    LaunchedEffect(activeSid, ctrl) {
        off = 0; dis = null; sel = null
    }

    val ln by ctrl.currentInputLine.collectState()
    val bk by ctrl.bracketedPaste.collectState()
    val alt by ctrl.alternateScreen.collectState()
    val nn by ctrl.nanoDetected.collectState()
    LaunchedEffect(alt) { sel = null }

    val pq = remember(ln, cwd, activeTab.homeDir) { Ac.resolvePathQuery(ln, cwd, activeTab.homeDir) }
    val td = pq?.dirToList?.takeIf { it != cwd }
    val ok = !alt && activeTab.status == SessionStatus.CONNECTED
    var ce by remember(activeSid, cwd) { mutableStateOf(mgr.peekCachedDirEntries(activeSid, cwd, true)) }
    var te by remember(activeSid, td) { mutableStateOf(if (td != null) mgr.peekCachedDirEntries(activeSid, td, true) else emptyList()) }
    LaunchedEffect(activeSid, cwd, ok, ln.isEmpty()) { if (ok) ce = mgr.listDirForAutocomplete(activeSid, cwd, true) }
    LaunchedEffect(activeSid, td, ok) { if (td != null && ok) te = mgr.listDirForAutocomplete(activeSid, td, true) }

    val tm = remember(cfg.touchCursorMode) { TouchCursorMode.fromId(cfg.touchCursorMode) }
    val ts = remember(cfg.touchCursorSensitivity) { TouchSensitivity.fromId(cfg.touchCursorSensitivity) }
    val re = if (td != null) te else ce
    var sg by remember(activeSid) { mutableStateOf<List<CompletionItem>>(emptyList()) }
    LaunchedEffect(
        activeSid,
        ln,
        hist,
        cfg.autocompleteEnabled,
        cfg.autocompleteFromHistory,
        alt,
        re,
        ce,
        cwd,
        activeTab.homeDir,
        activeTab.serverId
    ) {
        if (alt || !cfg.autocompleteEnabled || ln.isBlank()) {
            sg = emptyList()
            return@LaunchedEffect
        }
        sg = withContext(Dispatchers.Default) {
            Ac.suggest(ln, emptyList(), hist, cfg, 3, re, cwd, activeTab.homeDir, activeTab.serverId, ce)
        }
    }

    fun send(t: String) {
        if (sel != null) sel = null
        mgr.sendInput(activeSid, t)
    }

    fun sendWithModifiers(raw: String) {
        val out = applyTerminalModifiers(raw, cOn, aOn)
        cOn = false
        aOn = false
        off = 0
        send(out)
    }

    fun sendArrowWithModifiers(dir: CursorDir, hwCtrl: Boolean = false, hwAlt: Boolean = false) {
        val useCtrl = hwCtrl || cOn
        val useAlt = hwAlt || aOn
        cOn = false
        aOn = false
        off = 0
        sel = null
        if (useCtrl || useAlt) {
            send(applyArrowModifiers(dir, useCtrl, useAlt))
        } else {
            ctrl.sendArrow(dir, 1)
        }
    }

    fun toggleKeyboard() {
        if (imeVisible) {
            keyboard?.hide()
        } else {
            runCatching { imeFocusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    fun deliverPaste(t: String) = send(formatPastePayload(t, bk))
    val onPickSuggestion = remember(activeSid, ln, mgr) {
        { item: CompletionItem ->
            val e = Ac.computeCompletionInsert(ln, item.insertText)
            dis = if (item.insertText.endsWith("/") || item.insertText.endsWith(" ")) null else item.insertText
            if (sel != null) sel = null
            mgr.sendInput(activeSid, "\u007F".repeat(e.backspaces) + e.insertText)
        }
    }
    val onDismissSuggestion = remember(ln) { { dis = ln } }
    val activePrefix = remember(ln, sg) { Ap.activeTokenPrefix(ln, sg.firstOrNull()?.source.orEmpty()) }
    val ac = remember(alt, dis, ln, sg, onPickSuggestion, onDismissSuggestion, activePrefix) {
        FloatingAutocompleteState(!alt && dis != ln, sg, onPickSuggestion, onDismissSuggestion, activePrefix, ln)
    }

    val acPopupOpen = ac.visible && ac.suggestions.isNotEmpty() && off == 0 && sel == null
    BackHandler(enabled = sel != null || sr || mk || acPopupOpen) {
        when {
            sel != null -> sel = null
            sr -> { sr = false; sq = "" }
            mk -> mk = false
            acPopupOpen -> dis = ln
        }
    }

    Column(Modifier.fillMaxSize().background(AgBg).imePadding()) {
        SessionTabsStripObserver(vm, activeSid, mgr::selectSession, mgr::closeSession)
        TerminalToolbarRow(
            viaSummary = activeTab.viaSummary,
            showSearch = sr,
            touchMode = tm,
            touchSensitivity = ts,
            isAltScreen = alt,
            imeVisible = imeVisible,
            onToggleKeyboard = ::toggleKeyboard,
            onCycleTouchMode = vm::cycleTouchCursorMode,
            onCycleTouchSensitivity = vm::cycleTouchCursorSensitivity,
            onOpenSftp = { sf = true },
            onOpenShortcuts = { dt = 0 },
            onOpenHistory = { dt = 2 },
            onPaste = { requestClipboardPaste(clip.getText()?.text.orEmpty(), cfg.pasteGuardEnabled, bk, vm::showToast, ::deliverPaste) { r, i -> ps = r to i } },
            onToggleSearch = { sr = !sr; if (!sr) sq = "" },
            onReconnect = { mgr.reconnectSession(activeSid) },
            showMoreKeys = mk,
            ctrlActive = cOn,
            altActive = aOn,
            onToggleMoreKeys = { mk = !mk },
            onToggleCtrl = { cOn = !cOn },
            onToggleAlt = { aOn = !aOn },
            onSendSequence = { sendWithModifiers(it) }
        )
        if (sr) TerminalSearchStrip(sq) { sq = it }
        MonitorBarObserver(vm, activeSid, cfg.monitorBarEnabled)
        HorizontalDivider(color = AgBorder)
        TerminalCanvas(
            controller = ctrl,
            touchMode = tm,
            touchSensitivity = ts,
            touchFlingEnabled = cfg.touchCursorFling,
            fontSizeSp = fSp,
            searchQuery = sq,
            scrollOffset = off,
            autocomplete = ac,
            selection = sel,
            onScrollOffsetChange = { off = it },
            onFontSizeChange = { fSp = it },
            onFontSizeCommit = { sz ->
                if (sz != cfg.terminalFontSize) vm.updateSettings { it.copy(terminalFontSize = sz) }
            },
            onResize = { c, r -> mgr.resizeSession(activeSid, c, r) },
            onImeInput = { raw -> sendWithModifiers(raw) },
            onHardwareArrow = { d, hwCtrl, hwAlt -> sendArrowWithModifiers(d, hwCtrl, hwAlt) },
            imeFocusRequester = imeFocusRequester,
            onSelectionChange = { sel = it },
            onCopySelection = { t -> if (t.isNotEmpty()) { clip.setText(AnnotatedString(t)); vm.showToast("已复制") } else vm.showToast("选中内容为空") },
            modifier = Modifier.weight(1f)
        )
        TerminalBottomAccessory(
            isAltScreen = alt,
            isNano = nn,
            onSendArrow = { d -> sendArrowWithModifiers(d) },
            onSendControl = { ch -> cOn = false; aOn = false; off = 0; send((ch.uppercaseChar().code - 64).toChar().toString()) },
            onSendSequence = { sendWithModifiers(it) }
        )
    }

    TerminalOverlays(
        viewModel = vm,
        activeSessionId = activeSid,
        bracketedPaste = bk,
        showSftp = sf,
        quickDrawerTab = dt,
        pendingPaste = ps,
        onDismissSftp = { sf = false },
        onRunScript = { cmd, ex -> mgr.runScript(activeSid, cmd, ex); dt = null },
        onDismissDrawer = { dt = null },
        onConfirmPaste = { deliverPaste(it); ps = null },
        onDismissPaste = { ps = null }
    )
}

@Composable
private fun SessionTabsStripObserver(
    viewModel: AppViewModel,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit
) {
    val initialHeaders = remember(viewModel) {
        viewModel.sessionTabs.value.map { SessionTabHeader(it.sessionId, it.serverName, it.status) }
    }
    val tabs by remember(viewModel) {
        viewModel.sessionTabs.map { list ->
            list.map { SessionTabHeader(it.sessionId, it.serverName, it.status) }
        }.distinctUntilChanged()
    }.collectState(initialValue = initialHeaders)
    SessionTabsStrip(tabs, activeId, onSelect, onClose)
}

@Composable
private fun MonitorBarObserver(
    viewModel: AppViewModel,
    sessionId: String,
    enabled: Boolean
) {
    if (!enabled) return
    val initialMetrics = remember(viewModel, sessionId) {
        viewModel.sessionTabs.value.find { it.sessionId == sessionId }?.metrics
    }
    val metrics by remember(viewModel, sessionId) {
        viewModel.sessionTabs.map { tabs ->
            tabs.find { it.sessionId == sessionId }?.metrics
        }.distinctUntilChanged()
    }.collectState(initialValue = initialMetrics)
    metrics?.let { MonitorBar(it) }
}
