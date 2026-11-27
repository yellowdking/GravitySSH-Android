package com.gravityssh.app.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.sync.SyncConfig
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.components.AgCard
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.components.AgTextField
import com.gravityssh.app.ui.saveSyncRelayUrl
import com.gravityssh.app.ui.testSyncRelay
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun SyncRelayCard(viewModel: AppViewModel) {
    val savedUrl by remember(viewModel) {
        viewModel.vaultState
            .map { it.data.settings.syncRelayUrl }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.vaultState.value.data.settings.syncRelayUrl)
    var input by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }
    var testing by remember { mutableStateOf(false) }
    var pingStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AgCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AgIcons.Dns, contentDescription = null, tint = AgAccentLight, modifier = Modifier.size(18.dp))
                Text("同步中继服务器 (IP:端口)", style = MaterialTheme.typography.titleSmall, color = AgText)
            }
            if (savedUrl.isNotBlank()) {
                TextButton(
                    onClick = {
                        input = ""
                        pingStatus = null
                        viewModel.saveSyncRelayUrl("")
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("恢复默认", color = AgCyan, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AgTextField(
            value = input,
            onValueChange = { input = it; pingStatus = null },
            label = "中继地址 (留空使用默认)",
            placeholder = "默认中继，或输入新 VPS 如 1.2.3.4:54843",
            mono = true
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    pingStatus = null
                    viewModel.saveSyncRelayUrl(input)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1f).height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("保存", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    if (testing) return@OutlinedButton
                    testing = true
                    pingStatus = null
                    val target = input.trim().ifEmpty { SyncConfig.DEFAULT_RELAY_URL }
                    viewModel.testSyncRelay(target) { res ->
                        testing = false
                        pingStatus = res.fold(
                            onSuccess = { ms -> true to "✓ 已连通 ${ms}ms" },
                            onFailure = { err -> false to (err.message ?: "连接失败") }
                        )
                    }
                },
                enabled = !testing,
                border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1.2f).height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AgCyan)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (testing) "测试中..." else "测试连接", color = AgCyan, fontSize = 12.sp)
            }
        }
        pingStatus?.let { (ok, msg) ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) AgSuccess else AgDanger
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "新 VPS 部署: docker run -d --read-only --cap-drop=ALL --security-opt=no-new-privileges --memory=96m --pids-limit=64 -p 54843:54843 bbplin/sshsync",
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            color = AgMuted
        )
    }
}
