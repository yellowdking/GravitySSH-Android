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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cancelSyncFlow
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.startSyncReceive
import com.gravityssh.app.ui.theme.*

@Composable
fun SyncReceiveCard(
    viewModel: AppViewModel,
    state: SyncUiState
) {
    val clipboard = LocalClipboardManager.current
    // Stored in remember (NEVER rememberSaveable) in case user pastes a synctool://pair URI containing #s=
    var roomOrUriInput by remember { mutableStateOf("") }
    var showPairScanner by remember { mutableStateOf(false) }

    val isReceiveFlowActive = state.flowPurpose == "oneshot" &&
        (state.flowRole == "responder" || state.flowRole == "receiver") &&
        (state.flowStage == SyncFlowStage.WAITING_PEER ||
            state.flowStage == SyncFlowStage.AWAITING_SAS_CONFIRM ||
            state.flowStage == SyncFlowStage.TRANSFERRING)

    val anyFlowBusy = state.flowStage == SyncFlowStage.WAITING_PEER ||
        state.flowStage == SyncFlowStage.AWAITING_SAS_INPUT ||
        state.flowStage == SyncFlowStage.AWAITING_SAS_CONFIRM ||
        state.flowStage == SyncFlowStage.TRANSFERRING

    AgCard {
        if (!isReceiveFlowActive) {
            Text("一次性安全接收", style = MaterialTheme.typography.titleMedium, color = AgText)
            Spacer(Modifier.height(4.dp))
            Text(
                "直接扫描发送端二维码（若二维码携带自定义中继将自动切换连接），或输入发送端显示的 8 位房间码。",
                style = MaterialTheme.typography.bodySmall,
                color = AgMuted
            )
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = { showPairScanner = true },
                enabled = !anyFlowBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AgCyan)
            ) {
                Icon(AgIcons.QrCodeScanner, contentDescription = null, tint = AgBg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("扫码立即接收", color = AgBg, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = AgBorder)
            Spacer(Modifier.height(12.dp))

            Text("手动输入 8 位房间码接收", style = MaterialTheme.typography.titleSmall, color = AgText)
            Spacer(Modifier.height(8.dp))
            AgTextField(
                value = roomOrUriInput,
                onValueChange = { roomOrUriInput = it },
                label = "XXXX-XXXX 房间码或 synctool://pair URI",
                placeholder = "例如 K7M9-P4X2",
                mono = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val clipText = clipboard.getText()?.text?.trim().orEmpty()
                            if (clipText.isNotEmpty()) roomOrUriInput = clipText
                        }
                    ) {
                        Icon(AgIcons.ContentPaste, contentDescription = "粘贴", tint = AgMuted, modifier = Modifier.size(18.dp))
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val target = roomOrUriInput.trim()
                    if (target.isNotEmpty()) {
                        roomOrUriInput = ""
                        viewModel.startSyncReceive(target)
                    }
                },
                enabled = roomOrUriInput.isNotBlank() && !anyFlowBusy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                Text("加入房间并验证接收", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("正在接收端到端加密配置", style = MaterialTheme.typography.titleMedium, color = AgCyan)

                if (state.roomCode.isNotBlank()) {
                    Text(
                        text = "房间码：${formatSyncRoomCode(state.roomCode)}",
                        fontFamily = MonoFontFamily,
                        style = MaterialTheme.typography.labelLarge,
                        color = AgText
                    )
                }

                if (state.flowStage == SyncFlowStage.TRANSFERRING) {
                    SyncTransferProgressRow(
                        bytesDone = state.bytesDone,
                        bytesTotal = state.bytesTotal,
                        label = state.statusMessage.ifBlank { "正在接收并校验 64 KiB 加密分块..." }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AgCyan)
                        Text(
                            text = state.statusMessage.ifBlank { "正在与发送端建立加密通道..." },
                            style = MaterialTheme.typography.bodySmall,
                            color = AgMuted
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.cancelSyncFlow() },
                    border = BorderStroke(1.dp, AgDanger),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消接收", color = AgDanger)
                }
            }
        }
    }

    if (showPairScanner) {
        QrScannerDialog(
            qrKind = SyncQrKind.PAIR,
            title = "扫码立即接收",
            onResult = { scanned ->
                showPairScanner = false
                roomOrUriInput = ""
                viewModel.startSyncReceive(scanned)
            },
            onDismiss = { showPairScanner = false }
        )
    }
}
