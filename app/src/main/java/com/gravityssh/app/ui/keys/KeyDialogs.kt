package com.gravityssh.app.ui.keys

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gravityssh.app.ui.components.AgFilterChip
import com.gravityssh.app.ui.components.AgPasswordField
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_KEY_FILE_BYTES = 256 * 1024

@Composable
fun GenerateKeyDialog(
    onGenerate: (type: String, name: String, comment: String, onResult: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var keyType by rememberSaveable { mutableStateOf("ed25519") }
    var name by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("gravityssh-android") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AgPanel,
        title = { Text("生成 SSH 密钥对", color = AgText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgFilterChip(
                        selected = keyType == "ed25519",
                        onClick = { if (!busy) keyType = "ed25519" },
                        label = "Ed25519 (推荐)",
                        enabled = !busy
                    )
                    AgFilterChip(
                        selected = keyType == "rsa",
                        onClick = { if (!busy) keyType = "rsa" },
                        label = "RSA-4096",
                        enabled = !busy
                    )
                }
                AgTextField(name, { name = it }, "密钥名称", placeholder = "例如 生产主密钥", enabled = !busy)
                AgTextField(comment, { comment = it }, "公钥注释", mono = true, enabled = !busy)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    onGenerate(keyType, name.ifBlank { "${keyType.uppercase()} Key" }, comment) { ok ->
                        busy = false
                        if (ok) onDismiss()
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "生成中..." else "生成", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = AgMuted) } }
    )
}

@Composable
fun ImportKeyDialog(
    onImport: (name: String, pem: String, passphrase: String, comment: String, onResult: (Boolean) -> Unit) -> Unit,
    onToast: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    // Keep private key PEM and passphrase in-memory only (never persisted to SavedInstanceState Bundle).
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var readingFile by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || readingFile || busy) return@rememberLauncherForActivityResult
        readingFile = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法打开所选私钥文件")
                    stream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_KEY_FILE_BYTES) {
                                throw IllegalArgumentException("私钥文件过大（上限 256 KB）")
                            }
                            out.write(buf, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }
            }.onSuccess { text ->
                pem = text
            }.onFailure { err ->
                onToast("读取私钥文件失败: ${err.message ?: "未知错误"}")
            }
            readingFile = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !readingFile) onDismiss() },
        containerColor = AgPanel,
        title = { Text("导入 SSH 私钥", color = AgText) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !busy && !readingFile,
                    border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (readingFile) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AgCyan)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (readingFile) "读取文件中..." else "从本地文件读取私钥 (id_ed25519 / .pem)", color = AgCyan)
                }
                AgTextField(name, { name = it }, "密钥名称", placeholder = "例如 阿里云密钥", enabled = !busy)
                AgTextField(pem, { pem = it }, "私钥内容 (PEM / OpenSSH)", mono = true, singleLine = false, maxLines = 6, enabled = !busy)
                AgPasswordField(passphrase, { passphrase = it }, "私钥口令（未加密可留空）", enabled = !busy)
                AgTextField(comment, { comment = it }, "备注 / 注释（可选）", mono = true, enabled = !busy)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (busy || readingFile) return@Button
                    busy = true
                    onImport(name.ifBlank { "Imported Key" }, pem, passphrase, comment) { ok ->
                        busy = false
                        if (ok) onDismiss()
                    }
                },
                enabled = pem.isNotBlank() && !busy && !readingFile,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "导入中..." else "导入", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy && !readingFile) { Text("取消", color = AgMuted) }
        }
    )
}
