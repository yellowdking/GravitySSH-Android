package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.*

@Composable
fun SyncPreviewCard(
    preview: SyncReceivePreview,
    busy: Boolean = false,
    onMerge: () -> Unit,
    onOverwrite: () -> Unit,
    onDiscard: () -> Unit
) {
    var confirmOverwrite by remember { mutableStateOf(false) }

    AgCard(borderColor = AgCyan) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(AgIcons.VerifiedUser, contentDescription = null, tint = AgCyan, modifier = Modifier.size(20.dp))
            Text("待导入配置", style = MaterialTheme.typography.titleMedium, color = AgCyan)
        }
        Spacer(Modifier.height(8.dp))
        val srcName = preview.sourceDevice.ifBlank { preview.sourcePlatform.ifBlank { "远端设备" } }
        val platPart = if (preview.sourcePlatform.isNotBlank()) " (${preview.sourcePlatform})" else ""
        Text(
            text = "来源：$srcName$platPart",
            style = MaterialTheme.typography.bodyMedium,
            color = AgText
        )
        if (preview.exportedAt.isNotBlank()) {
            Text(
                text = "时间：${preview.exportedAt}",
                style = MaterialTheme.typography.labelSmall,
                color = AgMuted
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${preview.serverCount} 主机 · ${preview.keyCount} 密钥 · ${preview.shortcutCount} 快捷命令 · ${preview.templateCount} 模板",
            style = MaterialTheme.typography.bodySmall,
            color = AgCyan
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onMerge,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "正在导入..." else "合并导入", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { confirmOverwrite = true },
                enabled = !busy,
                modifier = Modifier.weight(1.2f).height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AgDanger)
            ) {
                Text("覆盖导入", color = AgDanger)
            }
            OutlinedButton(
                onClick = onDiscard,
                enabled = !busy,
                modifier = Modifier.weight(0.8f).height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AgBorderLight)
            ) {
                Text("放弃", color = AgMuted)
            }
        }
    }

    if (confirmOverwrite) {
        AgConfirmDialog(
            title = "确认覆盖导入",
            message = "覆盖导入将替换本机现有的所有主机、密钥、快捷命令和模板，确定继续？",
            confirmLabel = "确认覆盖",
            dangerous = true,
            onConfirm = {
                confirmOverwrite = false
                onOverwrite()
            },
            onDismiss = { confirmOverwrite = false }
        )
    }
}

@Composable
fun SyncSummaryBanner(
    summary: SyncImportSummary,
    onDismiss: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AgSuccess.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, AgSuccess),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已导入 (${if (summary.mode == SyncImportMode.MERGE) "合并" else "覆盖"})",
                    style = MaterialTheme.typography.titleSmall,
                    color = AgSuccess
                )
                Text(
                    text = "主机 +${summary.serversAdded}/${summary.serversUpdated}更新 · 密钥 +${summary.keysAdded}/${summary.keysUpdated}更新 · 快捷命令 +${summary.shortcutsAdded} · 模板 +${summary.templatesAdded}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgText
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭提示", tint = AgMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
