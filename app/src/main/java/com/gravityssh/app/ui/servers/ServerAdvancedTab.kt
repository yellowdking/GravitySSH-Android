package com.gravityssh.app.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.AgDanger

@Composable
fun ServerAdvancedTab(
    draft: ServerConfig,
    onChange: (ServerConfig) -> Unit,
    onClearHostCache: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgTextField(
            draft.timeoutSeconds.toString(),
            { onChange(draft.copy(timeoutSeconds = it.filter(Char::isDigit).toIntOrNull() ?: 15)) },
            "连接超时 (秒)",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        AgTextField(
            draft.keepAliveSeconds.toString(),
            { onChange(draft.copy(keepAliveSeconds = it.filter(Char::isDigit).toIntOrNull() ?: 30)) },
            "心跳间隔 (秒)",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
    AgTextField(draft.defaultRemotePath, { onChange(draft.copy(defaultRemotePath = it)) }, "默认 SFTP 路径", placeholder = "/root 或 /var/www", mono = true)
    AgTextField(draft.startupCommand, { onChange(draft.copy(startupCommand = it)) }, "登录后自动执行命令", mono = true, singleLine = false, maxLines = 4)
    AgTextField(draft.notes, { onChange(draft.copy(notes = it)) }, "备注", singleLine = false, maxLines = 3)
    if (draft.host.isNotBlank()) {
        OutlinedButton(
            onClick = onClearHostCache,
            border = BorderStroke(1.dp, AgDanger.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("清除此主机 SSH 指纹缓存 (known_hosts)", color = AgDanger) }
    }
}
