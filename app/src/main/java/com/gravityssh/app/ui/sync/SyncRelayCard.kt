package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.SyncPingResultUi
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.saveCustomSyncRelay
import com.gravityssh.app.ui.testSyncRelay
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.ui.updateSyncDeviceLabel

@Composable
fun SyncRelayCard(
    viewModel: AppViewModel,
    state: SyncUiState
) {
    val relay = state.relay
    val isCustom = relay.mode == "custom"
    val safeRedactedDisplay = remember(relay) {
        if (isCustom) {
            relay.redactedUri.ifBlank { relay.endpoint }.substringBefore('#').ifBlank { "自定义中继" }
        } else {
            "官方默认中继 (TLS 1.3 + SPKI Pin)"
        }
    }

    // Secret-bearing synctool://relay?...#k=... input MUST stay in volatile remember (NEVER rememberSaveable!)
    var customUriInput by remember { mutableStateOf("") }
    var uriVisible by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showRelayScanner by remember { mutableStateOf(false) }
    var showLabelDialog by remember { mutableStateOf(false) }
    var localPingError by remember { mutableStateOf<String?>(null) }
    var localPingResult by remember { mutableStateOf<SyncPingResultUi?>(null) }

    val effectivePing = localPingResult ?: state.pingResult
    val rotateWarn = relay.certRotateRecommended || (effectivePing?.certRotateRecommended == true)

    AgCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(AgIcons.Dns, contentDescription = null, tint = AgCyan, modifier = Modifier.size(18.dp))
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("同步中继与设备身份", style = MaterialTheme.typography.titleSmall, color = AgText)
                        AgBadge(
                            text = if (isCustom) "自定义" else "官方默认",
                            color = if (isCustom) AgAccentLight else AgCyan
                        )
                    }
                    Text(
                        text = safeRedactedDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = AgMuted
                    )
                }
            }
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(if (expanded) "收起" else "配置", color = AgCyan, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val devLabel = state.localDeviceLabel.ifBlank { "Android 设备" }
                val devIdShort = state.localDeviceId.take(8)
                Text(
                    text = if (devIdShort.isNotEmpty()) "本机：$devLabel · ID $devIdShort" else "本机：$devLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgText
                )
                if (relay.spkiPinShort.isNotBlank()) {
                    Text(
                        text = "TLS: ${relay.tlsMode} · Pin ${relay.spkiPinShort}",
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = AgSubtle
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showLabelDialog = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "修改本机名称", tint = AgMuted, modifier = Modifier.size(16.dp))
                }
                OutlinedButton(
                    onClick = {
                        if (state.isPinging) return@OutlinedButton
                        localPingError = null
                        viewModel.testSyncRelay(customUriInput) { res ->
                            res.onSuccess { ping ->
                                localPingResult = ping
                                localPingError = null
                            }.onFailure { err ->
                                localPingResult = null
                                localPingError = (err.message ?: "连接测试失败").substringBefore('#')
                            }
                        }
                    },
                    enabled = !state.isPinging,
                    border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    if (state.isPinging) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = AgCyan)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.isPinging) "测试中..." else "连接测试", color = AgCyan, fontSize = 12.sp)
                }
            }
        }

        localPingError?.let { errMsg ->
            Spacer(Modifier.height(8.dp))
            AgStatusBanner(message = errMsg, color = AgDanger)
        }

        effectivePing?.let { ping ->
            Spacer(Modifier.height(8.dp))
            val skewText = if (ping.clockOffsetSec >= 0) "+${ping.clockOffsetSec}s" else "${ping.clockOffsetSec}s"
            val certPart = if (ping.certExpiresAt.isNotBlank()) " · 证书到期 ${ping.certExpiresAt.take(10)}" else ""
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = AgSuccess.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, AgSuccess.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✓ 已连通 ${ping.rttMs}ms · 规格 ${ping.profile} · 暂存 ${ping.spoolMode} · 时钟偏差 $skewText$certPart",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgSuccess,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        if (rotateWarn) {
            Spacer(Modifier.height(8.dp))
            AgStatusBanner(
                message = "中继证书即将到期，建议管理员执行 synctool-relay cert-rotate --stage-next 无缝轮换证书",
                color = AgWarning,
                icon = Icons.Rounded.Warning
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = AgBorder)
            Spacer(Modifier.height(10.dp))

            AgTextField(
                value = customUriInput,
                onValueChange = {
                    customUriInput = it
                    localPingError = null
                },
                label = "自定义中继 synctool://relay URI",
                placeholder = if (isCustom) safeRedactedDisplay else "synctool://relay?v=1&u=https%3A%2F%2F...&pin=...#k=...",
                mono = true,
                visualTransformation = if (uriVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { uriVisible = !uriVisible }) {
                        Icon(
                            imageVector = if (uriVisible) AgIcons.VisibilityOff else AgIcons.Visibility,
                            contentDescription = if (uriVisible) "隐藏密钥" else "显示 URI",
                            tint = AgMuted
                        )
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showRelayScanner = true },
                    border = BorderStroke(1.dp, AgCyan),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(AgIcons.QrCodeScanner, null, tint = AgCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("扫码填入中继 URI", color = AgCyan, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val raw = customUriInput.trim()
                        if (raw.isEmpty()) return@Button
                        localPingError = null
                        localPingResult = null
                        viewModel.saveCustomSyncRelay(raw) { res ->
                            if (res.isSuccess) {
                                customUriInput = ""
                                uriVisible = false
                            }
                        }
                    },
                    enabled = customUriInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("保存自定义中继", color = Color.White, fontSize = 12.sp)
                }
            }
            if (isCustom || customUriInput.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        customUriInput = ""
                        uriVisible = false
                        localPingError = null
                        localPingResult = null
                        if (isCustom) viewModel.saveCustomSyncRelay("")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("恢复官方默认中继", color = AgWarning, fontSize = 12.sp)
                }
            }
        }
    }

    if (showRelayScanner) {
        QrScannerDialog(
            qrKind = SyncQrKind.RELAY,
            onResult = { scanned ->
                showRelayScanner = false
                localPingError = null
                localPingResult = null
                viewModel.saveCustomSyncRelay(scanned) { res ->
                    if (res.isFailure) {
                        customUriInput = scanned
                        expanded = true
                    } else {
                        customUriInput = ""
                    }
                }
            },
            onDismiss = { showRelayScanner = false }
        )
    }

    if (showLabelDialog) {
        DeviceLabelEditDialog(
            initialLabel = state.localDeviceLabel,
            onConfirm = { nextLabel ->
                showLabelDialog = false
                viewModel.updateSyncDeviceLabel(nextLabel)
            },
            onDismiss = { showLabelDialog = false }
        )
    }
}

@Composable
private fun DeviceLabelEditDialog(
    initialLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initialLabel) { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text("设置本机同步名称", color = AgText) },
        text = {
            AgTextField(
                value = text,
                onValueChange = { text = it.take(48) },
                label = "设备名称",
                placeholder = "例如 Pixel 9 Pro"
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                Text("保存", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) }
        }
    )
}
