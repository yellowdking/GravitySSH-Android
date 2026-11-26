package com.gravityssh.app.ui.sftp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgDanger
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgText

@Composable
fun SftpNameInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(title, color = AgText) },
        text = { AgTextField(value = text, onValueChange = { text = it }, label = label, mono = true) },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) { Text(confirmLabel, color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) } }
    )
}

@Composable
fun SftpDeleteConfirmDialog(
    entry: SFTPFileEntry,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AgPanel,
        title = { Text("确认删除", color = AgText) },
        text = {
            Text(
                text = if (entry.isDir) "将递归删除目录「${entry.name}」及全部子文件，操作不可恢复。"
                else "确定删除文件「${entry.name}」？",
                color = AgMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AgDanger)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "删除中..." else "确认删除", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = AgMuted) }
        }
    )
}

@Composable
fun SftpEntryActionDialog(
    entry: SFTPFileEntry,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onAlignPerms: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val isArchive = !entry.isDir && listOf(".tar.gz", ".tgz", ".zip", ".tar", ".gz").any {
        entry.name.lowercase().endsWith(it)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(entry.name, color = AgText, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                if (!entry.isDir) {
                    ActionItem("在线编辑文本 (<= 1MB)", AgCyan, onEdit)
                    ActionItem("下载到本地存储", AgText, onDownload)
                }
                ActionItem("重命名", AgText, onRename)
                ActionItem("压缩为 .tar.gz", AgText, onCompress)
                if (isArchive) ActionItem("解压到当前目录", AgCyan, onExtract)
                ActionItem("权限对齐 (同级目录模式)", AgText, onAlignPerms)
                ActionItem("删除", AgDanger, onDelete)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭", color = AgMuted) } }
    )
}

@Composable
private fun ActionItem(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
