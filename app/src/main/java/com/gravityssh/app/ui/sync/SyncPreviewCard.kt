package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.theme.*

@Composable
fun SyncPreviewCard(
    preview: SyncReceivePreview,
    onMerge: () -> Unit,
    onRequestOverwrite: () -> Unit
) {
    AgCard(borderColor = AgAccent) {
        Text("解密成功 · 待导入数据预览", style = MaterialTheme.typography.titleMedium, color = AgCyan)
        Spacer(Modifier.height(6.dp))
        Text("来源设备：${preview.sourceDevice.ifBlank { preview.sourcePlatform }} (${preview.sourcePlatform})", style = MaterialTheme.typography.bodySmall, color = AgText)
        Text("包含：${preview.serverCount} 台主机 · ${preview.keyCount} 把密钥 · ${preview.shortcutCount} 快捷命令 · ${preview.templateCount} 模板", style = MaterialTheme.typography.bodySmall, color = AgMuted)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onMerge,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
        ) { Text("安全合并（推荐）", color = Color.White) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRequestOverwrite,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AgDanger)
        ) { Text("完全覆盖本地数据", color = AgDanger) }
    }
}

@Composable
fun SyncSummaryBanner(summary: SyncImportSummary) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AgSuccess.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, AgSuccess),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "同步已完成 (${if (summary.mode == SyncImportMode.MERGE) "安全合并" else "完全覆盖"})",
                style = MaterialTheme.typography.titleSmall,
                color = AgSuccess
            )
            Text(
                "主机 +${summary.serversAdded}/${summary.serversUpdated}更新 · 密钥 +${summary.keysAdded}/${summary.keysUpdated}更新 · 快捷命令 +${summary.shortcutsAdded} · 模板 +${summary.templatesAdded}",
                style = MaterialTheme.typography.bodySmall,
                color = AgText
            )
        }
    }
}
