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
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePollState
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncReceiveWaitSession
import com.gravityssh.app.sync.SyncConfig
import com.gravityssh.app.sync.SyncCrypto
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SyncReceiveCard(viewModel: AppViewModel) {
    var inputCode by remember { mutableStateOf("") }
    var recvCode by remember { mutableStateOf(viewModel.container.syncService.generateReceiverCode()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var waitSession by remember { mutableStateOf<SyncReceiveWaitSession?>(null) }
    var waitRemainingSec by remember { mutableLongStateOf(0L) }
    var consecutivePollErrors by remember { mutableIntStateOf(0) }
    var lastPollError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SyncReceivePreview?>(null) }
    var summary by remember { mutableStateOf<SyncImportSummary?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val activePreviewId = preview?.previewId
    DisposableEffect(activePreviewId) {
        onDispose {
            if (activePreviewId != null) {
                viewModel.container.syncService.discardPreview(activePreviewId)
            }
        }
    }

    val activeWaitId = waitSession?.waitId
    DisposableEffect(activeWaitId) {
        onDispose {
            if (activeWaitId != null) {
                viewModel.container.syncService.cancelReceiveWait(activeWaitId)
            }
        }
    }

    LaunchedEffect(waitSession) {
        val wait = waitSession ?: return@LaunchedEffect
        while (true) {
            val left = ((wait.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            waitRemainingSec = left
            // Do not halt polling on local clock reaching 0 (tolerates ±120s client/relay clock skew).
            // Authoritative relay status (approved/rejected/expired) terminates polling below.
            if (left <= 0L) break
            delay(1000L)
        }
    }

    LaunchedEffect(waitSession?.waitId) {
        val wait = waitSession ?: return@LaunchedEffect
        consecutivePollErrors = 0
        lastPollError = null
        while (true) {
            try {
                val res = viewModel.container.syncService.pollReceiveStatus(wait.waitId)
                consecutivePollErrors = 0
                lastPollError = null
                when (res.state) {
                    SyncReceivePollState.PENDING -> Unit
                    SyncReceivePollState.APPROVED -> {
                        preview = res.preview
                        waitSession = null
                        break
                    }
                    SyncReceivePollState.REJECTED -> {
                        waitSession = null
                        errorMsg = "发送端已拒绝此次同步请求"
                        break
                    }
                    SyncReceivePollState.EXPIRED -> {
                        waitSession = null
                        errorMsg = "同步请求已过期或被发送端撤销"
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                waitSession = null
                errorMsg = e.message ?: "解密或校验失败"
                break
            } catch (e: IllegalStateException) {
                waitSession = null
                errorMsg = e.message ?: "中继状态异常"
                break
            } catch (e: Exception) {
                consecutivePollErrors++
                lastPollError = e.message ?: "网络异常"
                if (consecutivePollErrors >= 5 && System.currentTimeMillis() > wait.expiresAtEpochMs + 150_000L) {
                    viewModel.container.syncService.cancelReceiveWait(wait.waitId)
                    waitSession = null
                    errorMsg = "等待发送端确认已超时且网络不可达，请重试"
                    break
                }
            }
            delay(SyncConfig.PAIR_POLL_INTERVAL_MS)
        }
    }

    fun startVerification(codeOrUri: String) {
        if (codeOrUri.isBlank() || busy || waitSession != null) return
        busy = true
        errorMsg = null
        summary = null
        consecutivePollErrors = 0
        lastPollError = null
        scope.launch {
            runCatching {
                viewModel.container.syncService.startReceiveRequest(codeOrUri, recvCode)
            }.onSuccess { wait ->
                preview = null
                waitSession = wait
            }.onFailure {
                errorMsg = it.message ?: "发起验证失败，请确认同步码有效"
            }
            busy = false
        }
    }

    fun applyImport(mode: SyncImportMode) {
        val p = preview ?: return
        if (busy) return
        busy = true; errorMsg = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    viewModel.container.syncService.applyReceive(p.previewId, mode)
                }
            }.onSuccess {
                summary = it
                preview = null
                inputCode = ""
                recvCode = viewModel.container.syncService.generateReceiverCode()
                viewModel.showToast("同步导入完成")
            }.onFailure {
                errorMsg = it.message ?: "导入失败"
            }
            busy = false
        }
    }

    val currentWait = waitSession
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
                            text = recvCode,
                            fontFamily = MonoFontFamily,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = AgCyan,
                            letterSpacing = 2.sp
                        )
                    }
                    TextButton(
                        onClick = { recvCode = viewModel.container.syncService.generateReceiverCode() },
                        enabled = !busy,
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
            AgTextField(inputCode, { inputCode = it; errorMsg = null }, "同步码或 gssh-sync:// URI", placeholder = "例如 K7M9-P4X2", mono = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showScanner = true },
                    enabled = !busy,
                    border = BorderStroke(1.dp, AgCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AgIcons.QrCodeScanner, null, tint = AgCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫码填入", color = AgCyan)
                }
                Button(
                    onClick = { startVerification(inputCode) },
                    enabled = inputCode.isNotBlank() && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (busy) "提交验证中..." else "发起验证并接收", color = Color.White)
                }
            }
            errorMsg?.let {
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
                if (consecutivePollErrors >= 3) {
                    AgStatusBanner(
                        message = "网络连接异常（已连续重试 $consecutivePollErrors 次）：${lastPollError ?: "请检查网络或中继服务器"}",
                        color = AgWarning
                    )
                }
                val mm = waitRemainingSec / 60
                val ss = waitRemainingSec % 60
                Text(
                    if (waitRemainingSec > 0L) {
                        "发送端同步码 ${currentWait.sendCode} · 剩余 ${String.format(Locale.US, "%02d:%02d", mm, ss)}"
                    } else {
                        "发送端同步码 ${currentWait.sendCode} · 正在等待中继确认..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AgMuted
                )
                OutlinedButton(
                    onClick = {
                        val wid = currentWait.waitId
                        waitSession = null
                        consecutivePollErrors = 0
                        lastPollError = null
                        viewModel.container.syncService.cancelReceiveWait(wid)
                    },
                    border = BorderStroke(1.dp, AgDanger),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消等待", color = AgDanger)
                }
            }
        }
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
                startVerification(scanned)
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
