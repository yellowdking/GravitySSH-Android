package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
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
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SyncSendCard(viewModel: AppViewModel) {
    var sendSession by remember { mutableStateOf<SyncSendSession?>(null) }
    var sendBusy by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var remainingSec by remember { mutableLongStateOf(0L) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(sendSession) {
        val sess = sendSession ?: return@LaunchedEffect
        while (true) {
            val left = ((sess.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            remainingSec = left
            if (left <= 0L) { sendSession = null; break }
            delay(1000L)
        }
    }

    AgCard {
        val sess = sendSession
        if (sess == null) {
            Text("将本机主机、SSH 密钥、快捷命令与运维模板加密打包，生成一次性同步码供电脑或其他手机接收。", style = MaterialTheme.typography.bodySmall, color = AgMuted)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    sendBusy = true; sendError = null
                    scope.launch {
                        runCatching { viewModel.container.syncService.startSend() }
                            .onSuccess { sendSession = it }
                            .onFailure { sendError = it.message ?: "上传同步数据失败" }
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
                Text(if (sendBusy) "加密上传中..." else "生成同步码", color = Color.White)
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
                Text("剩余有效期 ${String.format(Locale.US, "%02d:%02d", mm, ss)} · 读取一次即焚毁", style = MaterialTheme.typography.labelMedium, color = AgSuccess)
                Text("包含 ${sess.serverCount} 主机 · ${sess.keyCount} 密钥 · ${sess.shortcutCount} 快捷命令 · ${sess.templateCount} 模板", style = MaterialTheme.typography.bodySmall, color = AgMuted)
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
                            val code = sess.code; sendSession = null
                            scope.launch {
                                runCatching { viewModel.container.syncService.cancelSend(code) }
                                viewModel.showToast("已撤销同步码")
                            }
                        },
                        border = BorderStroke(1.dp, AgDanger),
                        modifier = Modifier.weight(1f)
                    ) { Text("撤销", color = AgDanger) }
                }
            }
        }
    }
}
