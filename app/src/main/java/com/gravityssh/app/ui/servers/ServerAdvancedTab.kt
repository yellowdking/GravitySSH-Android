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
    timeoutText: String,
    keepAliveText: String,
    onTimeoutTextChange: (String) -> Unit,
    onKeepAliveTextChange: (String) -> Unit,
    onChange: (ServerConfig) -> Unit,
    onClearHostCache: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgTextField(
            timeoutText,
            onTimeoutTextChange,
            "连接超时 (3-120秒)",
            placeholder = "15",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        AgTextField(
            keepAliveText,
            onKeepAliveTextChange,
            "心跳间隔 (0-600秒)",
            placeholder = "30",
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
