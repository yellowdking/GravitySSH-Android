package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
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
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.model.SyncPairedDeviceUi
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.acceptSyncDeviceBind
import com.gravityssh.app.ui.applySyncInboxItem
import com.gravityssh.app.ui.cancelSyncFlow
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgFilterChip
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgSwitchRow
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.dismissSyncInboxItem
import com.gravityssh.app.ui.refreshSyncInbox
import com.gravityssh.app.ui.revokeSyncPairedDevice
import com.gravityssh.app.ui.sendSyncToPairedDevice
import com.gravityssh.app.ui.startSyncDeviceBindOffer
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun SyncPairedCard(
    viewModel: AppViewModel,
    state: SyncUiState,
    actionBusy: Boolean
) {
    var showBindSection by remember { mutableStateOf(false) }
    var showBindScanner by remember { mutableStateOf(false) }
    var overwriteInboxItemId by remember { mutableStateOf<String?>(null) }
    var revokeTargetDevice by remember { mutableStateOf<SyncPairedDeviceUi?>(null) }

    val isPairedFlowActive = (state.flowPurpose == "pair" || state.flowPurpose == "direct") &&
        (state.flowStage == SyncFlowStage.WAITING_PEER || state.flowStage == SyncFlowStage.TRANSFERRING)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SyncInboxSectionCard(
            items = state.inboxItems,
            isRefreshing = state.isRefreshingInbox,
            busy = actionBusy,
            pendingInboxItemId = state.pendingInboxItemId,
            bytesDone = state.bytesDone,
            bytesTotal = state.bytesTotal,
            onRefresh = { viewModel.refreshSyncInbox() },
            onMerge = { itemId -> viewModel.applySyncInboxItem(itemId, "merge") },
            onRequestOverwrite = { itemId -> overwriteInboxItemId = itemId },
            onDismiss = { itemId -> viewModel.dismissSyncInboxItem(itemId) }
        )

        if (isPairedFlowActive) {
            ActivePairedRoomFlowCard(
                state = state,
                onCopyRoomCode = { formatted ->
                    viewModel.showToast("已复制房间码 $formatted")
                },
                onCancel = { viewModel.cancelSyncFlow() }
            )
        }

        AgCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(AgIcons.VerifiedUser, null, tint = AgAccentLight, modifier = Modifier.size(18.dp))
                    Text("已配对信任设备", style = MaterialTheme.typography.titleMedium, color = AgText)
                    AgBadge(text = "${state.pairedDevices.size}", color = AgAccent)
                }
                OutlinedButton(
                    onClick = { showBindSection = !showBindSection },
                    border = BorderStroke(1.dp, AgCyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Add, null, tint = AgCyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (showBindSection) "收起绑定" else "+ 绑定新设备", color = AgCyan, fontSize = 12.sp)
                }
            }

            if (state.pairedDevices.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "尚未绑定常用设备。绑定一次后即可支持 0-RTT 一键加密推送与离线待收配置箱（端到端签名防篡改）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgMuted
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.pairedDevices.forEach { dev ->
                        PairedDeviceItemRow(
                            device = dev,
                            busy = actionBusy || state.flowStage == SyncFlowStage.TRANSFERRING,
                            suggestPipe = state.suggestPipeDeviceId == dev.deviceId,
                            onPushAuto = { viewModel.sendSyncToPairedDevice(dev.deviceId, "auto") },
                            onPushPipe = { viewModel.sendSyncToPairedDevice(dev.deviceId, "pipe") },
                            onRevoke = { revokeTargetDevice = dev }
                        )
                    }
                }
            }

            if (showBindSection && !isPairedFlowActive) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AgBorder)
                Spacer(Modifier.height(12.dp))
                BindNewDevicePanel(
                    isCustomRelay = state.relay.mode == "custom",
                    busy = actionBusy,
                    onStartOffer = { mode, includeRelay ->
                        viewModel.startSyncDeviceBindOffer(mode = mode, includeRelay = includeRelay)
                    },
                    onOpenScanner = { showBindScanner = true },
                    onAcceptCode = { codeOrUri -> viewModel.acceptSyncDeviceBind(codeOrUri) }
                )
            }
        }
    }

    if (showBindScanner) {
        QrScannerDialog(
            qrKind = SyncQrKind.PAIR,
            title = "扫码绑定新设备",
            onResult = { scanned ->
                showBindScanner = false
                showBindSection = false
                viewModel.acceptSyncDeviceBind(scanned)
            },
            onDismiss = { showBindScanner = false }
        )
    }

    overwriteInboxItemId?.let { targetItemId ->
        AgConfirmDialog(
            title = "确认覆盖导入收件箱配置",
            message = "覆盖导入将替换本机现有的主机、SSH 密钥、快捷命令和模板（已配对设备与自定义中继配置将安全保留）。导入成功后将向中继发送签名确认销毁该密文，确定继续？",
            confirmLabel = "确认覆盖导入",
            dangerous = true,
            onConfirm = {
                overwriteInboxItemId = null
                viewModel.applySyncInboxItem(targetItemId, "overwrite")
            },
            onDismiss = { overwriteInboxItemId = null }
        )
    }

    revokeTargetDevice?.let { dev ->
        AgConfirmDialog(
            title = "吊销配对设备",
            message = "确定吊销「${dev.label.ifBlank { dev.deviceId }}」的配对信任并撤销其投递令牌吗？吊销后需重新绑定才能推送。",
            confirmLabel = "确认吊销",
            dangerous = true,
            onConfirm = {
                revokeTargetDevice = null
                viewModel.revokeSyncPairedDevice(dev.deviceId)
            },
            onDismiss = { revokeTargetDevice = null }
        )
    }
}

@Composable
private fun PairedDeviceItemRow(
    device: SyncPairedDeviceUi,
    busy: Boolean,
    suggestPipe: Boolean,
    onPushAuto: () -> Unit,
    onPushPipe: () -> Unit,
    onRevoke: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AgElevated,
        border = BorderStroke(1.dp, if (suggestPipe) AgWarning else AgBorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = device.label.ifBlank { "未命名设备" },
                            style = MaterialTheme.typography.titleSmall,
                            color = AgText
                        )
                        if (device.platform.isNotBlank()) {
                            AgBadge(text = device.platform, color = AgCyan)
                        }
                    }
                    Text(
                        text = "ID: ${device.deviceId.take(12)} · 绑定于 ${formatEpochSecShort(device.issuedAt)}",
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = AgMuted
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPushAuto,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("一键推送", color = Color.White, fontSize = 12.sp)
                    }
                    IconButton(onClick = onRevoke, enabled = !busy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Delete, contentDescription = "吊销", tint = AgDanger, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (suggestPipe) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AgWarning.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AgWarning),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "当前中继离线暂存不可用或配额已满，可在对端在线时直接实时传输：",
                            style = MaterialTheme.typography.bodySmall,
                            color = AgWarning,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Button(
                            onClick = onPushPipe,
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = AgWarning),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("改用实时直传", color = AgBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BindNewDevicePanel(
    isCustomRelay: Boolean,
    busy: Boolean,
    onStartOffer: (mode: Int, includeRelay: Boolean) -> Unit,
    onOpenScanner: () -> Unit,
    onAcceptCode: (String) -> Unit
) {
    var offerMode by remember { mutableIntStateOf(1) }
    var includeRelay by remember(isCustomRelay) { mutableStateOf(isCustomRelay) }
    // Stored in remember (NEVER rememberSaveable) in case user pastes a synctool://pair URI containing #s=
    var joinInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("方式 A：由本机发起绑定邀请", style = MaterialTheme.typography.titleSmall, color = AgText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgFilterChip(
                selected = offerMode == 1,
                onClick = { offerMode = 1 },
                label = "展示二维码 (m=1)",
                selectedColor = AgCyan
            )
            AgFilterChip(
                selected = offerMode == 2,
                onClick = { offerMode = 2 },
                label = "8 位码 + 安全码 (m=2)",
                selectedColor = AgAccent
            )
        }
        if (offerMode == 1 && isCustomRelay) {
            AgSwitchRow(
                label = "二维码携带自定义中继配置 (新设备一码绑定)",
                checked = includeRelay,
                onCheckedChange = { includeRelay = it }
            )
        }
        Button(
            onClick = { onStartOffer(offerMode, offerMode == 1 && isCustomRelay && includeRelay) },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth().height(40.dp)
        ) {
            Text(if (offerMode == 1) "生成绑定二维码" else "生成 8 位绑定房间码", color = Color.White)
        }

        HorizontalDivider(color = AgBorder)

        Text("方式 B：响应对端发起的绑定", style = MaterialTheme.typography.titleSmall, color = AgText)
        AgTextField(
            value = joinInput,
            onValueChange = { joinInput = it },
            label = "对端 8 位房间码或 synctool://pair URI",
            placeholder = "例如 K7M9-P4X2",
            mono = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onOpenScanner,
                enabled = !busy,
                border = BorderStroke(1.dp, AgCyan),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Icon(AgIcons.QrCodeScanner, null, tint = AgCyan, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("扫码绑定", color = AgCyan)
            }
            Button(
                onClick = {
                    val clean = joinInput.trim()
                    if (clean.isNotEmpty()) {
                        joinInput = ""
                        onAcceptCode(clean)
                    }
                },
                enabled = joinInput.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Text("加入绑定", color = Color.White)
            }
        }
    }
}

@Composable
private fun ActivePairedRoomFlowCard(
    state: SyncUiState,
    onCopyRoomCode: (String) -> Unit,
    onCancel: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val remainingSec = rememberCountdownSec(state.expiresAtEpochSec)
    val formattedRoom = remember(state.roomCode) { formatSyncRoomCode(state.roomCode) }
    val isDirectPipe = state.flowPurpose == "direct"

    AgCard(borderColor = AgCyan) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (isDirectPipe) "已配对设备实时直传通道" else "正在绑定新设备",
                style = MaterialTheme.typography.titleMedium,
                color = AgCyan
            )
            if (state.pairUri.isNotBlank()) {
                QrCodeCanvas(content = state.pairUri, size = 216.dp)
            }
            if (formattedRoom.isNotBlank()) {
                Text(
                    text = formattedRoom,
                    fontFamily = MonoFontFamily,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = AgCyan,
                    letterSpacing = 3.sp
                )
            }
            if (remainingSec > 0L && state.flowStage == SyncFlowStage.WAITING_PEER) {
                Text(
                    text = "房间有效期 ${String.format(Locale.US, "%02d:%02d", remainingSec / 60, remainingSec % 60)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AgSuccess
                )
            }
            if (state.flowStage == SyncFlowStage.TRANSFERRING) {
                SyncTransferProgressRow(
                    bytesDone = state.bytesDone,
                    bytesTotal = state.bytesTotal,
                    label = state.statusMessage.ifBlank { "正在通过端到端加密通道传输..." }
                )
            } else if (state.statusMessage.isNotBlank()) {
                Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = AgMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (formattedRoom.isNotBlank() && state.flowMode != 1) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(formattedRoom))
                            onCopyRoomCode(formattedRoom)
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
                    onClick = onCancel,
                    border = BorderStroke(1.dp, AgDanger),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isDirectPipe) "取消直传" else "取消绑定", color = AgDanger)
                }
            }
        }
    }
}

@Composable
internal fun rememberCountdownSec(expiresAtEpochSec: Long): Long {
    var nowSec by remember(expiresAtEpochSec) { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(expiresAtEpochSec) {
        if (expiresAtEpochSec <= 0L) return@LaunchedEffect
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            if (expiresAtEpochSec - nowSec <= 0L) break
            delay(1000L)
        }
    }
    return if (expiresAtEpochSec > 0L) (expiresAtEpochSec - nowSec).coerceAtLeast(0L) else 0L
}
