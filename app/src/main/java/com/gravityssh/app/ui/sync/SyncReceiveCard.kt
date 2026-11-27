package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.sync.SyncCrypto
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.applySyncReceive
import com.gravityssh.app.ui.cancelSyncReceiveWait
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.discardSyncReceivePreview
import com.gravityssh.app.ui.refreshSyncReceiverCode
import com.gravityssh.app.ui.startSyncReceive
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSyncReceiveInputCode
import java.util.Locale

@Composable
fun SyncReceiveCard(viewModel: AppViewModel) {
    val state by viewModel.syncReceiveState.collectAsStateWithLifecycle()
    var showScanner by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }

    val currentWait = state.waitSession
    if (currentWait == null) {
        AgCard {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AgInputBg,
                border = BorderStroke(1.dp, AgBorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("本机确认码（用于发送端核对）", style = MaterialTheme.typography.labelSmall, color = AgMuted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = state.receiverCode,
                            fontFamily = MonoFontFamily,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = AgCyan,
                            letterSpacing = 2.sp
                        )
                    }
                    TextButton(
                        onClick = { viewModel.refreshSyncReceiverCode() },
                        enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = AgCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("换一个", color = AgCyan, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("输入或扫描发送端 8 位同步码", style = MaterialTheme.typography.titleMedium, color = AgText)
            Spacer(Modifier.height(8.dp))
            AgTextField(
                value = state.inputCode,
                onValueChange = { viewModel.updateSyncReceiveInputCode(it) },
                label = "同步码或 gssh-sync:// URI",
                placeholder = "例如 K7M9-P4X2",
                mono = true
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showScanner = true },
                    enabled = !state.busy,
                    border = BorderStroke(1.dp, AgCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AgIcons.QrCodeScanner, null, tint = AgCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫码填入", color = AgCyan)
                }
                Button(
                    onClick = { viewModel.startSyncReceive(state.inputCode) },
                    enabled = state.inputCode.isNotBlank() && !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.busy) "提交验证中..." else "发起验证并接收", color = Color.White)
                }
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                AgStatusBanner(message = it, color = AgDanger)
            }
        }
    } else {
        AgCard(borderColor = AgCyan) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("等待发送端确认", style = MaterialTheme.typography.titleMedium, color = AgText)
                Text("本机确认码", style = MaterialTheme.typography.labelMedium, color = AgMuted)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AgInputBg,
                    border = BorderStroke(1.5.dp, AgCyan)
                ) {
                    Text(
                        text = currentWait.receiverCode,
                        fontFamily = MonoFontFamily,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = AgCyan,
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = AgCyan)
                    Text("请在发送端确认此码一致并点击同意...", style = MaterialTheme.typography.bodySmall, color = AgText)
                }
                if (state.consecutivePollErrors >= 3) {
                    AgStatusBanner(
                        message = "网络连接异常（已连续重试 ${state.consecutivePollErrors} 次）：${state.lastPollError ?: "请检查网络或中继服务器"}",
                        color = AgWarning
                    )
                }
                val mm = state.waitRemainingSec / 60
                val ss = state.waitRemainingSec % 60
                Text(
                    if (state.waitRemainingSec > 0L) {
                        "发送端同步码 ${currentWait.sendCode} · 剩余 ${String.format(Locale.US, "%02d:%02d", mm, ss)}"
                    } else {
                        "发送端同步码 ${currentWait.sendCode} · 正在等待中继确认..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AgMuted
                )
                OutlinedButton(
                    onClick = { viewModel.cancelSyncReceiveWait() },
                    border = BorderStroke(1.dp, AgDanger),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消等待", color = AgDanger)
                }
            }
        }
    }

    state.preview?.let { p ->
        SyncPreviewCard(
            preview = p,
            busy = state.busy,
            onMerge = { viewModel.applySyncReceive(SyncImportMode.MERGE) },
            onRequestOverwrite = { confirmOverwrite = true },
            onDiscard = { viewModel.discardSyncReceivePreview() }
        )
    }

    state.summary?.let { SyncSummaryBanner(it) }

    if (showScanner) {
        QrScannerDialog(
            onResult = { scanned ->
                showScanner = false
                val formatted = runCatching { SyncCrypto.formatCode(scanned) }.getOrDefault(scanned)
                viewModel.updateSyncReceiveInputCode(formatted)
                viewModel.startSyncReceive(scanned)
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
            onConfirm = {
                confirmOverwrite = false
                viewModel.applySyncReceive(SyncImportMode.OVERWRITE)
            },
            onDismiss = { confirmOverwrite = false }
        )
    }
}
