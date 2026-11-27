package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.VerifiedUser
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
import com.gravityssh.app.model.SyncSendPollResult
import com.gravityssh.app.model.SyncSendPollState
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.sync.SyncConfig
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.cancelSyncSend
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.rejectSyncSend
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SyncSendCard(viewModel: AppViewModel) {
    var sendSession by remember { mutableStateOf<SyncSendSession?>(null) }
    var pollResult by remember { mutableStateOf<SyncSendPollResult?>(null) }
    var sendBusy by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sendConsumedSuccess by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableLongStateOf(0L) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(sendSession) {
        val sess = sendSession ?: return@LaunchedEffect
        while (true) {
            val left = ((sess.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            remainingSec = left
            if (left <= 0L) {
                sendSession = null
                pollResult = null
                sendError = "同步码已过期，请重新生成"
                break
            }
            delay(1000L)
        }
    }

    val awaitingApproval = pollResult?.state == SyncSendPollState.REQUESTED
    LaunchedEffect(sendSession?.sessionId, awaitingApproval) {
        val sess = sendSession ?: return@LaunchedEffect
        if (sess.sessionId.isBlank() || awaitingApproval) return@LaunchedEffect
        while (true) {
            try {
                val res = viewModel.container.syncService.pollSend(sess.sessionId)
                when (res.state) {
                    SyncSendPollState.WAITING -> {
                        pollResult = res
                    }
                    SyncSendPollState.REQUESTED -> {
                        pollResult = res
                        break
                    }
                    SyncSendPollState.APPROVED -> {
                        pollResult = res
                    }
                    SyncSendPollState.CONSUMED -> {
                        sendConsumedSuccess = true
                        sendSession = null
                        pollResult = null
                        viewModel.showToast("对方已成功接收配置")
                        break
                    }
                    SyncSendPollState.REJECTED -> {
                        sendError = "已拒绝配对请求"
                        sendSession = null
                        pollResult = null
                        break
                    }
                    SyncSendPollState.EXPIRED -> {
                        sendError = "同步码已过期或已失效，请重新生成"
                        sendSession = null
                        pollResult = null
                        break
                    }
                    SyncSendPollState.ERROR -> {
                        sendError = res.errorMessage ?: "验证请求异常，已终止同步"
                        sendSession = null
                        pollResult = null
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                sendError = e.message ?: "保险库已锁定"
                sendSession = null
                pollResult = null
                break
            } catch (_: Exception) {
                // Transient network error: retry on next poll tick
            }
            delay(SyncConfig.PAIR_POLL_INTERVAL_MS)
        }
    }

    AgCard {
        val sess = sendSession
        if (sess == null) {
            if (sendConsumedSuccess) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AgSuccess.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AgSuccess),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = AgSuccess, modifier = Modifier.size(18.dp))
                        Text(
                            "对方已成功接收配置，中继密文已焚毁",
                            style = MaterialTheme.typography.titleSmall,
                            color = AgSuccess
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                "将本机主机、SSH 密钥、快捷命令与运维模板加密打包。双方核对确认码一致后再执行双密钥加密上传（确认前不上传任何配置数据）。",
                style = MaterialTheme.typography.bodySmall,
                color = AgMuted
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    sendBusy = true
                    sendError = null
                    sendConsumedSuccess = false
                    pollResult = null
                    scope.launch {
                        runCatching { viewModel.container.syncService.startSend() }
                            .onSuccess { sendSession = it }
                            .onFailure { sendError = it.message ?: "初始化同步通道失败" }
                        sendBusy = false
                    }
                },
                enabled = !sendBusy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                if (sendBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (sendBusy) "初始化同步通道..." else "生成同步码", color = Color.White)
            }
            sendError?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = AgDanger) }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("一次性同步码", style = MaterialTheme.typography.labelMedium, color = AgMuted)
                Text(sess.code, fontFamily = MonoFontFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AgCyan, letterSpacing = 3.sp)
                QrCodeCanvas(content = sess.qrText, size = 196.dp)
                val mm = remainingSec / 60
                val ss = remainingSec % 60
                Text(
                    "剩余有效期 ${String.format(Locale.US, "%02d:%02d", mm, ss)} · 双向确认后阅后即焚",
                    style = MaterialTheme.typography.labelMedium,
                    color = AgSuccess
                )
                Text(
                    "包含 ${sess.serverCount} 主机 · ${sess.keyCount} 密钥 · ${sess.shortcutCount} 快捷命令 · ${sess.templateCount} 模板",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgMuted
                )

                val currentPoll = pollResult
                when (currentPoll?.state) {
                    SyncSendPollState.REQUESTED -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AgElevated,
                            border = BorderStroke(1.5.dp, AgWarning),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Rounded.VerifiedUser, null, tint = AgWarning, modifier = Modifier.size(18.dp))
                                    Text("收到接收端配对请求，请核对确认码", style = MaterialTheme.typography.titleSmall, color = AgWarning)
                                }
                                Text(
                                    "接收设备：${currentPoll.receiverDevice} · ${currentPoll.receiverPlatform}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AgText
                                )
                                Text("对方确认码", style = MaterialTheme.typography.labelMedium, color = AgMuted)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = AgInputBg,
                                    border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = currentPoll.receiverCode,
                                        fontFamily = MonoFontFamily,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AgCyan,
                                        letterSpacing = 3.sp,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                                    )
                                }
                                Text(
                                    "请确认对方屏幕上显示的「本机确认码」与此完全一致，再同意发送。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AgMuted
                                )
                                Spacer(Modifier.height(2.dp))
                                Button(
                                    onClick = {
                                        if (sendBusy) return@Button
                                        sendBusy = true
                                        sendError = null
                                        scope.launch {
                                            runCatching {
                                                viewModel.container.syncService.approveSend(
                                                    sessionId = sess.sessionId,
                                                    requestId = currentPoll.requestId,
                                                    receiverCode = currentPoll.receiverCode
                                                )
                                            }.onSuccess {
                                                pollResult = currentPoll.copy(state = SyncSendPollState.APPROVED)
                                            }.onFailure {
                                                sendError = it.message ?: "同意发送失败"
                                            }
                                            sendBusy = false
                                        }
                                    },
                                    enabled = !sendBusy,
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AgSuccess)
                                ) {
                                    if (sendBusy) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (sendBusy) "双密钥加密上传中..." else "核对一致，同意发送", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val sid = sess.sessionId
                                        val rid = currentPoll.requestId
                                        sendSession = null
                                        pollResult = null
                                        viewModel.rejectSyncSend(sid, rid)
                                    },
                                    enabled = !sendBusy,
                                    border = BorderStroke(1.dp, AgDanger),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("拒绝并撤销", color = AgDanger)
                                }
                            }
                        }
                    }

                    SyncSendPollState.APPROVED -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AgSuccess.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AgSuccess),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AgSuccess)
                                Text("✓ 已同意并上传加密数据，等待接收端拉取...", style = MaterialTheme.typography.bodySmall, color = AgSuccess)
                            }
                        }
                    }

                    else -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AgCyan)
                            Text("等待接收端输入同步码并提交确认...", style = MaterialTheme.typography.bodySmall, color = AgMuted)
                        }
                    }
                }

                sendError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AgDanger) }

                if (currentPoll?.state != SyncSendPollState.REQUESTED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(sess.code))
                                viewModel.showToast("已复制同步码 ${sess.code}")
                            },
                            border = BorderStroke(1.dp, AgCyan),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = AgCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制同步码", color = AgCyan)
                        }
                        OutlinedButton(
                            onClick = {
                                val sid = sess.sessionId.ifBlank { sess.code }
                                sendSession = null
                                pollResult = null
                                viewModel.cancelSyncSend(sid)
                            },
                            border = BorderStroke(1.dp, AgDanger),
                            modifier = Modifier.weight(1f)
                        ) { Text("撤销", color = AgDanger) }
                    }
                }
            }
        }
    }
}
