package com.gravityssh.app.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.*
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*

@Composable
fun ServerProxyTab(
    draft: ServerConfig,
    proxyPortText: String,
    onProxyPortTextChange: (String) -> Unit,
    onChange: (ServerConfig) -> Unit
) {
    val p = draft.proxy
    Text("代理协议", style = MaterialTheme.typography.labelMedium, color = AgMuted)
    ServerChipRow(
        listOf(ProxyType.NONE to "直连", ProxyType.SOCKS5 to "SOCKS5", ProxyType.HTTP to "HTTP"),
        p.type
    ) { onChange(draft.copy(proxy = p.copy(type = it))) }
    if (p.type != ProxyType.NONE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgTextField(p.host, { onChange(draft.copy(proxy = p.copy(host = it))) }, "代理地址", mono = true, modifier = Modifier.weight(2f))
            AgTextField(
                proxyPortText,
                onProxyPortTextChange,
                "端口",
                placeholder = "1080",
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        AgTextField(p.username, { onChange(draft.copy(proxy = p.copy(username = it))) }, "代理用户名（可选）", mono = true)
        AgTextField(p.password, { onChange(draft.copy(proxy = p.copy(password = it))) }, "代理密码（可选）", visualTransformation = PasswordVisualTransformation())
    }
}

@Composable
fun ServerJumpTab(
    draft: ServerConfig,
    jumpPortText: String,
    allServers: List<ServerConfig>,
    keys: List<SSHKeyRecord>,
    onJumpPortTextChange: (String) -> Unit,
    onChange: (ServerConfig) -> Unit
) {
    val j = draft.jumpHost
    Text("跳板机模式", style = MaterialTheme.typography.labelMedium, color = AgMuted)
    ServerChipRow(
        listOf(JumpMode.NONE to "不使用", JumpMode.SERVER to "已有主机", JumpMode.CUSTOM to "自定义跳板"),
        j.mode
    ) { onChange(draft.copy(jumpHost = j.copy(mode = it))) }
    when (j.mode) {
        JumpMode.SERVER -> {
            val candidates = allServers.filter { it.id != draft.id }
            Text("选择堡垒机", style = MaterialTheme.typography.labelMedium, color = AgMuted)
            if (candidates.isEmpty()) Text("暂无其他可选主机", style = MaterialTheme.typography.bodySmall, color = AgMuted)
            else ServerChipRow(candidates.map { it.id to "${it.name} (${it.host})" }, j.serverId) {
                onChange(draft.copy(jumpHost = j.copy(serverId = it)))
            }
        }
        JumpMode.CUSTOM -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgTextField(j.host, { onChange(draft.copy(jumpHost = j.copy(host = it))) }, "跳板地址", mono = true, modifier = Modifier.weight(2f))
                AgTextField(
                    jumpPortText,
                    onJumpPortTextChange,
                    "端口",
                    placeholder = "22",
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            AgTextField(j.username, { onChange(draft.copy(jumpHost = j.copy(username = it))) }, "跳板用户名", mono = true)
            ServerChipRow(listOf(AuthType.PASSWORD to "密码", AuthType.KEY to "密钥", AuthType.BOTH to "两者"), j.authType) {
                onChange(draft.copy(jumpHost = j.copy(authType = it)))
            }
            if (j.authType != AuthType.KEY) {
                AgTextField(j.password, { onChange(draft.copy(jumpHost = j.copy(password = it))) }, "跳板密码", visualTransformation = PasswordVisualTransformation())
            }
            if (j.authType != AuthType.PASSWORD) {
                KeySelectorRow("跳板 SSH 密钥", keys, j.sshKeyId) { onChange(draft.copy(jumpHost = j.copy(sshKeyId = it))) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("跳板机复用主代理配置", style = MaterialTheme.typography.bodyMedium, color = AgText)
                Switch(checked = j.useMainProxy, onCheckedChange = { onChange(draft.copy(jumpHost = j.copy(useMainProxy = it))) })
            }
        }
    }
}
