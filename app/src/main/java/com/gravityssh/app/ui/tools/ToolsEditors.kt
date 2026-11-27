package com.gravityssh.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgText

@Composable
fun ShortcutEditorDialog(
    initial: CommandShortcut,
    onSave: (CommandShortcut) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.name) }
    var category by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.category) }
    var command by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.command) }
    var description by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.description) }
    var autoExecute by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.autoExecute) }
    val draft = initial.copy(
        name = name,
        category = category,
        command = command,
        description = description,
        autoExecute = autoExecute
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(if (initial.id.isBlank()) "新建快捷命令" else "编辑快捷命令", color = AgText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgTextField(name, { name = it }, "名称")
                AgTextField(category, { category = it }, "分类", placeholder = "常用 / Docker / 系统")
                AgTextField(command, { command = it }, "命令脚本（支持多行）", mono = true, singleLine = false, maxLines = 6)
                AgTextField(description, { description = it }, "说明（可选）")
                AgSwitchRow("点击后自动回车执行", autoExecute) { autoExecute = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = name.isNotBlank() && command.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) { Text("保存", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) } }
    )
}

@Composable
fun TemplateEditorDialog(
    initial: BuiltinTemplate,
    onSave: (BuiltinTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.name) }
    var category by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.category) }
    var description by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.description) }
    var safetyNote by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.safetyNote) }
    var command by rememberSaveable(initial.id, initial.name, initial.command) { mutableStateOf(initial.command) }
    val draft = initial.copy(
        name = name,
        category = category,
        description = description,
        safetyNote = safetyNote,
        command = command
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(if (initial.id.isBlank()) "新建运维模板" else "编辑运维模板", color = AgText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgTextField(name, { name = it }, "模板名称")
                AgTextField(category, { category = it }, "分类", placeholder = "网络 / 系统 / 容器")
                AgTextField(description, { description = it }, "用途说明")
                AgTextField(safetyNote, { safetyNote = it }, "风险提示（可选）")
                AgTextField(command, { command = it }, "运维脚本", mono = true, singleLine = false, maxLines = 7)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = name.isNotBlank() && command.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) { Text("保存", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) } }
    )
}
