package com.gravityssh.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*

@Composable
internal fun MasterPasswordDialog(
    hasExisting: Boolean,
    onSave: (current: String, next: String) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgPanel,
        title = { Text(if (hasExisting) "修改或移除主密码" else "设置保险库主密码", color = AgText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasExisting) {
                    AgTextField(current, { current = it; err = null }, "当前主密码", visualTransformation = PasswordVisualTransformation())
                }
                AgTextField(
                    next,
                    { next = it; err = null },
                    if (hasExisting) "新主密码（留空则移除密码）" else "新主密码",
                    visualTransformation = PasswordVisualTransformation()
                )
                AgTextField(confirm, { confirm = it; err = null }, "确认新主密码", visualTransformation = PasswordVisualTransformation())
                err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AgDanger) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (next != confirm) err = "两次输入的新密码不一致" else onSave(current, next)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) { Text("确认保存", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) } }
    )
}
