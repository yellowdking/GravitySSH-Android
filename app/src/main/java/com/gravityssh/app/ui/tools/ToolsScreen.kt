package com.gravityssh.app.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.isSshPortTemplate
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.components.AgCommandConfirmDialog
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private data class PendingToolCommand(
    val title: String,
    val command: String,
    val safetyNote: String = "",
    val requiresSshPort: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(viewModel: AppViewModel, onJumpToTerminal: () -> Unit, onBack: () -> Unit) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }

    val shortcuts by remember(viewModel) {
        viewModel.vaultState.map { it.data.shortcuts }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.vaultState.value.data.shortcuts)
    val templates by remember(viewModel) {
        viewModel.vaultState.map { it.data.templates }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.vaultState.value.data.templates)
    val historyCount by remember(viewModel) {
        viewModel.vaultState.map { it.data.history.size }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.vaultState.value.data.history.size)
    val history by remember(viewModel, tabIndex) {
        if (tabIndex == 2) {
            viewModel.vaultState.map { it.data.history }.distinctUntilChanged()
        } else {
            flowOf(emptyList<CommandRecord>())
        }
    }.collectAsStateWithLifecycle(
        initialValue = if (tabIndex == 2) viewModel.vaultState.value.data.history else emptyList()
    )

    var editingShortcutOpen by rememberSaveable { mutableStateOf(false) }
    var editingShortcutId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingShortcutInitName by rememberSaveable { mutableStateOf("") }
    var editingShortcutInitCommand by rememberSaveable { mutableStateOf("") }
    var editingShortcutInitAutoExec by rememberSaveable { mutableStateOf(false) }

    var editingTemplateOpen by rememberSaveable { mutableStateOf(false) }
    var editingTemplateId by rememberSaveable { mutableStateOf<String?>(null) }

    var deletingShortcut by remember { mutableStateOf<CommandShortcut?>(null) }
    var deletingTemplate by remember { mutableStateOf<BuiltinTemplate?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<PendingToolCommand?>(null) }

    val editingShortcut = if (!editingShortcutOpen) {
        null
    } else {
        val id = editingShortcutId
        if (id.isNullOrBlank()) {
            CommandShortcut(
                name = editingShortcutInitName,
                command = editingShortcutInitCommand,
                autoExecute = editingShortcutInitAutoExec
            )
        } else {
            shortcuts.find { it.id == id }
        }
    }
    val editingTemplate = if (!editingTemplateOpen) {
        null
    } else {
        val id = editingTemplateId
        if (id.isNullOrBlank()) BuiltinTemplate() else templates.find { it.id == id }
    }

    LaunchedEffect(editingShortcutOpen, editingShortcutId, shortcuts) {
        if (editingShortcutOpen && !editingShortcutId.isNullOrBlank() && shortcuts.none { it.id == editingShortcutId }) {
            editingShortcutOpen = false
            editingShortcutId = null
        }
    }
    LaunchedEffect(editingTemplateOpen, editingTemplateId, templates) {
        if (editingTemplateOpen && !editingTemplateId.isNullOrBlank() && templates.none { it.id == editingTemplateId }) {
            editingTemplateOpen = false
            editingTemplateId = null
        }
    }

    fun openShortcutEditor(sc: CommandShortcut) {
        editingShortcutId = sc.id.ifBlank { null }
        editingShortcutInitName = if (sc.id.isBlank()) sc.name else ""
        editingShortcutInitCommand = if (sc.id.isBlank()) sc.command else ""
        editingShortcutInitAutoExec = if (sc.id.isBlank()) sc.autoExecute else false
        editingShortcutOpen = true
    }

    fun closeShortcutEditor() {
        editingShortcutOpen = false
        editingShortcutId = null
        editingShortcutInitName = ""
        editingShortcutInitCommand = ""
        editingShortcutInitAutoExec = false
    }

    fun openTemplateEditor(tpl: BuiltinTemplate) {
        editingTemplateId = tpl.id.ifBlank { null }
        editingTemplateOpen = true
    }

    fun closeTemplateEditor() {
        editingTemplateOpen = false
        editingTemplateId = null
    }

    val q = remember(search) { search.trim().lowercase() }
    val filteredShortcuts = remember(shortcuts, q) {
        shortcuts.filter {
            q.isEmpty() ||
                it.name.lowercase().contains(q) ||
                it.command.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }.sortedByDescending { it.pinned }
    }
    val filteredTemplates = remember(templates, q) {
        templates.filter {
            q.isEmpty() ||
                it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.command.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }
    }
    val filteredHistory = remember(history, q) {
        history.filter {
            q.isEmpty() ||
                it.command.lowercase().contains(q) ||
                it.serverName.lowercase().contains(q)
        }.take(200)
    }

    fun execOrWarn(cmd: String, autoExec: Boolean = true) {
        if (viewModel.runCommandInActive(cmd, autoExec)) onJumpToTerminal()
        else viewModel.showToast("请先连接一台 SSH 主机再执行命令")
    }

    fun requestConfirmRun(pending: PendingToolCommand) {
        if (viewModel.activeSessionId.value == null) {
            viewModel.showToast("请先连接一台 SSH 主机再执行命令")
            return
        }
        pendingCommand = pending
    }

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = AgText)
                    }
                },
                title = { Text("快捷中心", style = MaterialTheme.typography.titleLarge, color = AgText) },
                actions = {
                    when (tabIndex) {
                        0 -> IconButton(onClick = { openShortcutEditor(CommandShortcut()) }) { Icon(Icons.Rounded.Add, "新建", tint = AgAccent) }
                        1 -> {
                            IconButton(onClick = { viewModel.loadPresetTemplates() }) { Icon(AgIcons.Download, "预置", tint = AgCyan) }
                            IconButton(onClick = { openTemplateEditor(BuiltinTemplate()) }) { Icon(Icons.Rounded.Add, "新建", tint = AgAccent) }
                        }
                        2 -> IconButton(onClick = { confirmClearHistory = true }, enabled = historyCount > 0) {
                            Icon(AgIcons.DeleteSweep, "清空", tint = if (historyCount > 0) AgDanger else AgMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            TabRow(selectedTabIndex = tabIndex, containerColor = AgSurface, contentColor = AgAccent) {
                listOf("快捷命令(${shortcuts.size})", "运维模板(${templates.size})", "历史($historyCount)").forEachIndexed { idx, t ->
                    Tab(selected = tabIndex == idx, onClick = { tabIndex = idx }, text = { Text(t, color = if (tabIndex == idx) AgText else AgMuted, fontSize = 12.sp) })
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                AgTextField(search, { search = it }, "筛选关键字", placeholder = "名称 / 命令 / 分类")
            }

            val isEmptyTab = when (tabIndex) {
                0 -> filteredShortcuts.isEmpty()
                1 -> filteredTemplates.isEmpty()
                else -> filteredHistory.isEmpty()
            }
            if (isEmptyTab) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (tabIndex) {
                        0 -> AgEmptyState(
                            icon = AgIcons.Bolt,
                            title = if (shortcuts.isEmpty()) "暂无快捷命令" else "没有匹配的快捷命令",
                            subtitle = if (shortcuts.isEmpty()) "保存常用命令或脚本，在终端中一键触发执行" else "请尝试更改筛选关键字",
                            actionLabel = if (shortcuts.isEmpty()) "新建快捷命令" else null,
                            onAction = if (shortcuts.isEmpty()) { { openShortcutEditor(CommandShortcut()) } } else null
                        )
                        1 -> AgEmptyState(
                            icon = AgIcons.Terminal,
                            title = if (templates.isEmpty()) "暂无运维模板" else "没有匹配的运维模板",
                            subtitle = if (templates.isEmpty()) "可一键加载系统监控、容器、网络排查等预置运维模板" else "请尝试更改筛选关键字",
                            actionLabel = if (templates.isEmpty()) "加载预置模板" else null,
                            onAction = if (templates.isEmpty()) { { viewModel.loadPresetTemplates() } } else null
                        )
                        else -> AgEmptyState(
                            icon = AgIcons.History,
                            title = if (historyCount == 0) "暂无命令执行历史" else "没有匹配的历史记录",
                            subtitle = if (historyCount == 0) "在 SSH 终端执行的命令会自动记录在此，支持搜索与收藏" else "请尝试更改筛选关键字"
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (tabIndex) {
                        0 -> items(filteredShortcuts, key = { it.id }) { sc ->
                            ShortcutItemCard(sc, { viewModel.saveShortcut(sc.copy(pinned = !sc.pinned)) }, {
                                if (viewModel.runShortcutInActive(sc)) onJumpToTerminal() else viewModel.showToast("请先连接 SSH 终端")
                            }, { openShortcutEditor(sc) }, { deletingShortcut = sc })
                        }
                        1 -> items(filteredTemplates, key = { it.id }) { tpl ->
                            TemplateItemCard(
                                tpl = tpl,
                                onRun = {
                                    requestConfirmRun(
                                        PendingToolCommand(
                                            title = "确认执行模板：${tpl.name}",
                                            command = tpl.command,
                                            safetyNote = tpl.safetyNote,
                                            requiresSshPort = isSshPortTemplate(tpl)
                                        )
                                    )
                                },
                                onEdit = { openTemplateEditor(tpl) },
                                onDelete = { deletingTemplate = tpl }
                            )
                        }
                        else -> items(filteredHistory, key = { it.id }) { rec ->
                            HistoryItemCard(
                                rec = rec,
                                onStar = { openShortcutEditor(CommandShortcut(name = rec.command.take(24), command = rec.command, autoExecute = true)) },
                                onRun = {
                                    requestConfirmRun(
                                        PendingToolCommand(
                                            title = "确认执行历史命令",
                                            command = rec.command
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingCommand?.let { pending ->
        AgCommandConfirmDialog(
            title = pending.title,
            command = pending.command,
            safetyNote = pending.safetyNote,
            requiresSshPort = pending.requiresSshPort,
            onConfirm = { resolved ->
                pendingCommand = null
                execOrWarn(resolved, true)
            },
            onDismiss = { pendingCommand = null }
        )
    }

    editingShortcut?.let { ShortcutEditorDialog(it, { s -> viewModel.saveShortcut(s); closeShortcutEditor() }, ::closeShortcutEditor) }
    editingTemplate?.let { TemplateEditorDialog(it, { t -> viewModel.saveTemplate(t); closeTemplateEditor() }, ::closeTemplateEditor) }
    deletingShortcut?.let { sc ->
        AgConfirmDialog(
            title = "删除快捷命令",
            message = "确定删除快捷命令「${sc.name}」？",
            confirmLabel = "删除",
            dangerous = true,
            onConfirm = { viewModel.deleteShortcut(sc.id); deletingShortcut = null },
            onDismiss = { deletingShortcut = null }
        )
    }
    deletingTemplate?.let { tpl ->
        AgConfirmDialog(
            title = "删除运维模板",
            message = "确定删除运维模板「${tpl.name}」？",
            confirmLabel = "删除",
            dangerous = true,
            onConfirm = { viewModel.deleteTemplate(tpl.id); deletingTemplate = null },
            onDismiss = { deletingTemplate = null }
        )
    }
    if (confirmClearHistory) {
        AgConfirmDialog("清空命令历史", "确定清空所有本地命令历史？", "清空", dangerous = true, onConfirm = { viewModel.clearHistory(); confirmClearHistory = false }, onDismiss = { confirmClearHistory = false })
    }
}
