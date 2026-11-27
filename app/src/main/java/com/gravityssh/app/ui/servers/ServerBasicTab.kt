package com.gravityssh.app.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.components.AgFilterChip
import com.gravityssh.app.ui.components.AgPasswordField
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.theme.*

@Composable
fun ServerBasicTab(
    draft: ServerConfig,
    portText: String,
    keys: List<SSHKeyRecord>,
    onPortTextChange: (String) -> Unit,
    onChange: (ServerConfig) -> Unit
) {
    AgTextField(draft.name, { onChange(draft.copy(name = it)) }, "主机名称", placeholder = "例如 生产节点-01")
    AgTextField(draft.group, { onChange(draft.copy(group = it)) }, "分组", placeholder = "默认分组")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgTextField(draft.host, { onChange(draft.copy(host = it)) }, "主机地址 (IP / 域名)", mono = true, modifier = Modifier.weight(2.2f))
        AgTextField(
            portText,
            onPortTextChange,
            "端口",
            placeholder = "22",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
    AgTextField(draft.username, { onChange(draft.copy(username = it)) }, "用户名", mono = true)

    Text("颜色标识", style = MaterialTheme.typography.labelMedium, color = AgMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ServerColorOptions.forEach { hex ->
            val selected = draft.color.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(hex))
                    .border(if (selected) 2.5.dp else 0.dp, Color.White, CircleShape)
                    .clickable { onChange(draft.copy(color = hex)) }
            )
        }
    }

    Text("认证方式", style = MaterialTheme.typography.labelMedium, color = AgMuted)
    ServerChipRow(
        options = listOf(AuthType.PASSWORD to "密码", AuthType.KEY to "SSH 密钥", AuthType.BOTH to "密钥+密码"),
        selected = draft.authType,
        onSelect = { onChange(draft.copy(authType = it)) }
    )

    if (draft.authType == AuthType.PASSWORD || draft.authType == AuthType.BOTH) {
        AgPasswordField(
            value = draft.password,
            onValueChange = { onChange(draft.copy(password = it)) },
            label = "登录密码"
        )
    }
    if (draft.authType == AuthType.KEY || draft.authType == AuthType.BOTH) {
        KeySelectorRow("选择 SSH 密钥", keys, draft.sshKeyId) { onChange(draft.copy(sshKeyId = it)) }
    }
}

@Composable
internal fun KeySelectorRow(label: String, keys: List<SSHKeyRecord>, selectedId: String, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = AgMuted)
    if (keys.isEmpty()) {
        Text("尚未添加密钥，请前往「密钥」页生成或导入", style = MaterialTheme.typography.bodySmall, color = AgMuted)
    } else {
        ServerChipRow(keys.map { it.id to "${it.name} (${it.keyType})" }, selectedId, onSelect)
    }
}

@Composable
internal fun ServerChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (id, label) ->
            AgFilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = label
            )
        }
    }
}
