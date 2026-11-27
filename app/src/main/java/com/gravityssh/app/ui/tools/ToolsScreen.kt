package com.gravityssh.app.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(viewModel: AppViewModel, onJumpToTerminal: () -> Unit) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val shortcuts = vaultState.data.shortcuts
    val templates = vaultState.data.templates
    val history = vaultState.data.history

    var tabIndex by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var editingShortcut by remember { mutableStateOf<CommandShortcut?>(null) }
    var editingTemplate by remember { mutableStateOf<BuiltinTemplate?>(null) }
    var deletingShortcut by remember { mutableStateOf<CommandShortcut?>(null) }
    var deletingTemplate by remember { mutableStateOf<BuiltinTemplate?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }

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

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                title = { Text("运维效率中心", style = MaterialTheme.typography.titleLarge, color = AgText) },
                actions = {
                    when (tabIndex) {
                        0 -> IconButton(onClick = { editingShortcut = CommandShortcut() }) { Icon(Icons.Rounded.Add, "新建", tint = AgAccent) }
                        1 -> {
                            IconButton(onClick = { viewModel.loadPresetTemplates() }) { Icon(Icons.Rounded.Download, "预置", tint = AgCyan) }
                            IconButton(onClick = { editingTemplate = BuiltinTemplate() }) { Icon(Icons.Rounded.Add, "新建", tint = AgAccent) }
                        }
                        2 -> IconButton(onClick = { confirmClearHistory = true }) { Icon(Icons.Rounded.DeleteSweep, "清空", tint = AgDanger) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tabIndex, containerColor = AgSurface, contentColor = AgAccent) {
                listOf("快捷命令(${shortcuts.size})", "运维模板(${templates.size})", "历史(${history.size})").forEachIndexed { idx, t ->
                    Tab(selected = tabIndex == idx, onClick = { tabIndex = idx }, text = { Text(t, color = if (tabIndex == idx) AgText else AgMuted, fontSize = 12.sp) })
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                AgTextField(search, { search = it }, "筛选关键字", placeholder = "名称 / 命令 / 分类")
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tabIndex) {
                    0 -> items(filteredShortcuts, key = { it.id }) { sc ->
                        ShortcutItemCard(sc, { viewModel.saveShortcut(sc.copy(pinned = !sc.pinned)) }, {
                            if (viewModel.runShortcutInActive(sc)) onJumpToTerminal() else viewModel.showToast("请先连接 SSH 终端")
                        }, { editingShortcut = sc }, { deletingShortcut = sc })
                    }
                    1 -> items(filteredTemplates, key = { it.id }) { tpl ->
                        TemplateItemCard(tpl, { execOrWarn(tpl.command) }, { editingTemplate = tpl }, { deletingTemplate = tpl })
                    }
                    else -> items(filteredHistory, key = { it.id }) { rec ->
                        HistoryItemCard(rec, { editingShortcut = CommandShortcut(name = rec.command.take(24), command = rec.command, autoExecute = true) }, { execOrWarn(rec.command) })
                    }
                }
            }
        }
    }

    editingShortcut?.let { ShortcutEditorDialog(it, { s -> viewModel.saveShortcut(s); editingShortcut = null }, { editingShortcut = null }) }
    editingTemplate?.let { TemplateEditorDialog(it, { t -> viewModel.saveTemplate(t); editingTemplate = null }, { editingTemplate = null }) }
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
