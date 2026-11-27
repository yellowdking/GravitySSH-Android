package com.gravityssh.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gravityssh.app.ui.components.AgPasswordField
import com.gravityssh.app.ui.theme.*

@Composable
internal fun MasterPasswordDialog(
    hasExisting: Boolean,
    onSave: (current: String, next: String, onDone: (Result<Unit>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        if (!hasExisting && next.isBlank()) {
            err = if (next.isEmpty()) "请输入新主密码" else "主密码不能仅包含空白字符"
            return
        }
        if (hasExisting && next.isNotEmpty() && next.isBlank()) {
            err = "主密码不能仅包含空白字符"
            return
        }
        if (next != confirm) {
            err = "两次输入的新密码不一致"
            return
        }
        busy = true
        err = null
        onSave(current, next) { res ->
            busy = false
            res.onFailure { err = it.message ?: "操作失败" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AgPanel,
        title = { Text(if (hasExisting) "修改或移除主密码" else "设置保险库主密码", color = AgText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasExisting) {
                    AgPasswordField(current, { current = it; err = null }, "当前主密码", enabled = !busy)
                }
                AgPasswordField(
                    next,
                    { next = it; err = null },
                    if (hasExisting) "新主密码（留空则移除密码）" else "新主密码",
                    enabled = !busy
                )
                AgPasswordField(
                    confirm,
                    { confirm = it; err = null },
                    "确认新主密码",
                    enabled = !busy,
                    imeAction = ImeAction.Done,
                    onDone = ::submit
                )
                err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AgDanger) }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submit,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "加密保存中..." else "确认保存", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = AgMuted) } }
    )
}
