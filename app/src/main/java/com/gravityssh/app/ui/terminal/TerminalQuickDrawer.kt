package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.isSshPortTemplate
import com.gravityssh.app.ui.components.AgCommandConfirmDialog
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgBorder
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.theme.MonoFontFamily

private data class PendingDrawerCommand(
    val title: String,
    val command: String,
    val safetyNote: String = "",
    val requiresSshPort: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalQuickDrawer(
    initialTab: Int,
    shortcuts: List<CommandShortcut>,
    templates: List<BuiltinTemplate>,
    history: List<CommandRecord>,
    onRunCommand: (command: String, autoExecute: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var tabIndex by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var pendingCommand by remember { mutableStateOf<PendingDrawerCommand?>(null) }
    val titles = listOf("快捷命令", "运维模板", "命令历史")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AgPanel
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.68f)) {
            TabRow(selectedTabIndex = tabIndex, containerColor = AgPanel, contentColor = AgAccent) {
                titles.forEachIndexed { i, title ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = { Text(title, color = if (tabIndex == i) AgText else AgMuted) }
                    )
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (tabIndex) {
                    0 -> items(shortcuts, key = { it.id }) { sc ->
                        QuickCmdRow(
                            title = sc.name,
                            subtitle = sc.command,
                            badge = if (sc.autoExecute) "自动执行" else "填充",
                            onClick = { onRunCommand(sc.command, sc.autoExecute) }
                        )
                    }
                    1 -> items(templates, key = { it.id }) { tpl ->
                        QuickCmdRow(
                            title = tpl.name,
                            subtitle = tpl.description.ifBlank { tpl.command },
                            badge = tpl.category,
                            onClick = {
                                pendingCommand = PendingDrawerCommand(
                                    title = "确认执行模板：${tpl.name}",
                                    command = tpl.command,
                                    safetyNote = tpl.safetyNote,
                                    requiresSshPort = isSshPortTemplate(tpl)
                                )
                            }
                        )
                    }
                    else -> items(history.take(80), key = { it.id }) { rec ->
                        QuickCmdRow(
                            title = rec.command,
                            subtitle = rec.serverName,
                            badge = "预览执行",
                            monoTitle = true,
                            onClick = {
                                pendingCommand = PendingDrawerCommand(
                                    title = "确认执行历史命令",
                                    command = rec.command
                                )
                            }
                        )
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
                onRunCommand(resolved, true)
            },
            onDismiss = { pendingCommand = null }
        )
    }
}

@Composable
private fun QuickCmdRow(
    title: String,
    subtitle: String,
    badge: String,
    monoTitle: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AgElevated,
        border = BorderStroke(1.dp, AgBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = title,
                    color = AgText,
                    fontFamily = if (monoTitle) MonoFontFamily else null,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(badge, color = AgCyan, fontSize = 11.sp)
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = AgMuted,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
