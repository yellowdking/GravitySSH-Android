package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cancelSyncFlow
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgFilterChip
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.startSyncOneShotSend
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

private data class LocalVaultCounts(
    val servers: Int,
    val keys: Int,
    val shortcuts: Int,
    val templates: Int
)

@Composable
fun SyncSendCard(
    viewModel: AppViewModel,
    state: SyncUiState
) {
    val clipboard = LocalClipboardManager.current
    val counts by remember(viewModel) {
        viewModel.vaultState
            .map { d ->
                LocalVaultCounts(
                    servers = d.data.servers.size,
                    keys = d.data.sshKeys.size,
                    shortcuts = d.data.shortcuts.size,
                    templates = d.data.templates.size
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = viewModel.vaultState.value.data.let {
            LocalVaultCounts(it.servers.size, it.sshKeys.size, it.shortcuts.size, it.templates.size)
        }
    )

    val isCustomRelay = state.relay.mode == "custom"
    var sendMode by remember { mutableIntStateOf(1) }
    var includeCustomRelay by remember(isCustomRelay) { mutableStateOf(isCustomRelay) }

    val isOneShotSendActive = state.flowPurpose == "oneshot" &&
        (state.flowRole.isEmpty() || state.flowRole == "initiator" || state.flowRole == "offerer" || state.flowRole == "sender") &&
        (state.flowStage == SyncFlowStage.WAITING_PEER ||
            state.flowStage == SyncFlowStage.AWAITING_SAS_INPUT ||
            state.flowStage == SyncFlowStage.TRANSFERRING)

    val anyFlowBusy = state.flowStage == SyncFlowStage.WAITING_PEER ||
        state.flowStage == SyncFlowStage.AWAITING_SAS_INPUT ||
        state.flowStage == SyncFlowStage.AWAITING_SAS_CONFIRM ||
        state.flowStage == SyncFlowStage.TRANSFERRING

    AgCard {
        if (!isOneShotSendActive) {
            Text("一次性端到端发送", style = MaterialTheme.typography.titleMedium, color = AgText)
            Spacer(Modifier.height(4.dp))
            Text(
                "无需长期绑定设备，通过阅后即焚房间将本机配置加密直传至对端（接收确认后中继立即销毁房间）。",
                style = MaterialTheme.typography.bodySmall,
                color = AgMuted
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgFilterChip(
                    selected = sendMode == 1,
                    onClick = { sendMode = 1 },
                    label = "扫码速传 (QR)",
                    selectedColor = AgCyan
                )
                AgFilterChip(
                    selected = sendMode == 2,
                    onClick = { sendMode = 2 },
                    label = "8 位码 + 6 位安全码",
                    selectedColor = AgAccent
                )
            }

            Spacer(Modifier.height(8.dp))
            if (sendMode == 1) {
                Text(
                    "二维码内含 128-bit 带外随机密钥，对端扫码即可完成 X25519 零知识握手并自动流式传输。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgMuted
                )
                if (isCustomRelay) {
                    Spacer(Modifier.height(6.dp))
                    AgSwitchRow(
                        label = "携带自定义中继（新手机免配置一码直传）",
                        checked = includeCustomRelay,
                        onCheckedChange = { includeCustomRelay = it }
                    )
                }
            } else {
                AgStatusBanner(
                    message = "无摄像头或跨网远程发送：对端输入 8 位房间码后，双方通过语音或当面核对 6 位安全码。切勿将房间码与安全码发在同一聊天窗口！",
                    color = AgWarning
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "待发送：${counts.servers} 主机 · ${counts.keys} 密钥 · ${counts.shortcuts} 快捷命令 · ${counts.templates} 模板",
                style = MaterialTheme.typography.labelMedium,
                color = AgCyan
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.startSyncOneShotSend(
                        mode = sendMode,
                        includeRelay = sendMode == 1 && isCustomRelay && includeCustomRelay
                    )
                },
                enabled = !anyFlowBusy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                Text(
                    text = if (sendMode == 1) "生成扫码速传二维码" else "生成 8 位房间码",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            val remainingSec = rememberCountdownSec(state.expiresAtEpochSec)
            val formattedRoom = remember(state.roomCode) { formatSyncRoomCode(state.roomCode) }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (state.flowMode == 1) "请用接收端扫描下方二维码" else "请让接收端输入 8 位房间码",
                    style = MaterialTheme.typography.titleSmall,
                    color = AgText
                )

                if (state.flowMode == 1 && state.pairUri.isNotBlank()) {
                    QrCodeCanvas(content = state.pairUri, size = 216.dp)
                }

                if (formattedRoom.isNotBlank()) {
                    Text(
                        text = formattedRoom,
                        fontFamily = MonoFontFamily,
                        fontSize = if (state.flowMode == 2) 30.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AgCyan,
                        letterSpacing = 3.sp
                    )
                }

                if (remainingSec > 0L && state.flowStage == SyncFlowStage.WAITING_PEER) {
                    Text(
                        text = "剩余有效期 ${String.format(Locale.US, "%02d:%02d", remainingSec / 60, remainingSec % 60)} · 阅后即焚",
                        style = MaterialTheme.typography.labelMedium,
                        color = AgSuccess
                    )
                }

                if (state.flowStage == SyncFlowStage.TRANSFERRING) {
                    SyncTransferProgressRow(
                        bytesDone = state.bytesDone,
                        bytesTotal = state.bytesTotal,
                        label = state.statusMessage.ifBlank { "正在端到端分块加密传输..." }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = AgCyan)
                        Text(
                            text = state.statusMessage.ifBlank { "等待接收端加入房间..." },
                            style = MaterialTheme.typography.bodySmall,
                            color = AgMuted
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (state.flowMode == 2 && formattedRoom.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(formattedRoom))
                                viewModel.showToast("已复制 8 位房间码 $formattedRoom")
                            },
                            border = BorderStroke(1.dp, AgCyan),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(AgIcons.ContentCopy, null, tint = AgCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制房间码", color = AgCyan)
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelSyncFlow() },
                        border = BorderStroke(1.dp, AgDanger),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消并销毁", color = AgDanger)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SyncTransferProgressRow(
    bytesDone: Long,
    bytesTotal: Long,
    label: String
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = AgCyan)
            if (bytesTotal > 0L) {
                Text(
                    text = "${formatSyncBytes(bytesDone)} / ${formatSyncBytes(bytesTotal)}",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = AgMuted
                )
            }
        }
        if (bytesTotal > 0L) {
            val fraction = (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AgCyan,
                trackColor = AgElevated
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AgCyan,
                trackColor = AgElevated
            )
        }
    }
}
