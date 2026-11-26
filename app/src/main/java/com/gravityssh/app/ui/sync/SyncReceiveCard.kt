package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.sync.SyncCrypto
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SyncReceiveCard(viewModel: AppViewModel) {
    var inputCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SyncReceivePreview?>(null) }
    var summary by remember { mutableStateOf<SyncImportSummary?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetchPreview(codeOrUri: String) {
        if (codeOrUri.isBlank() || busy) return
        busy = true; errorMsg = null; summary = null
        scope.launch {
            runCatching { viewModel.container.syncService.previewReceive(codeOrUri) }
                .onSuccess { preview = it }
                .onFailure { errorMsg = it.message ?: "获取或解密失败，请确认同步码有效" }
            busy = false
        }
    }

    fun applyImport(mode: SyncImportMode) {
        val p = preview ?: return
        runCatching { viewModel.container.syncService.applyReceive(p.previewId, mode) }
            .onSuccess { summary = it; preview = null; inputCode = ""; viewModel.showToast("同步导入完成") }
            .onFailure { errorMsg = it.message ?: "导入失败" }
    }

    AgCard {
        Text("输入或扫描 8 位同步码", style = MaterialTheme.typography.titleMedium, color = AgText)
        Spacer(Modifier.height(8.dp))
        AgTextField(inputCode, { inputCode = it; errorMsg = null }, "同步码或 gssh-sync:// URI", placeholder = "例如 K7M9-P4X2", mono = true)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showScanner = true },
                border = BorderStroke(1.dp, AgCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = AgCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("扫码填入", color = AgCyan)
            }
            Button(
                onClick = { fetchPreview(inputCode) },
                enabled = inputCode.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.2f)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "解密中..." else "获取并解密", color = Color.White)
            }
        }
        errorMsg?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = AgDanger) }
    }

    preview?.let { p ->
        SyncPreviewCard(
            preview = p,
            onMerge = { applyImport(SyncImportMode.MERGE) },
            onRequestOverwrite = { confirmOverwrite = true }
        )
    }

    summary?.let { SyncSummaryBanner(it) }

    if (showScanner) {
        QrScannerDialog(
            onResult = { scanned ->
                showScanner = false
                inputCode = runCatching { SyncCrypto.formatCode(scanned) }.getOrDefault(scanned)
                fetchPreview(scanned)
            },
            onDismiss = { showScanner = false }
        )
    }

    if (confirmOverwrite) {
        AgConfirmDialog(
            title = "确认完全覆盖本地",
            message = "完全覆盖将替换本机现有的所有主机、密钥、快捷命令和模板（不可恢复）。确定继续？",
            confirmLabel = "确认覆盖",
            dangerous = true,
            onConfirm = { confirmOverwrite = false; applyImport(SyncImportMode.OVERWRITE) },
            onDismiss = { confirmOverwrite = false }
        )
    }
}
