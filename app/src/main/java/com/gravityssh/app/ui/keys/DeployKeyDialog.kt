package com.gravityssh.app.ui.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gravityssh.app.model.KeyDeployResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DeployKeyDialog(
    key: SSHKeyRecord,
    servers: List<ServerConfig>,
    onDeploy: suspend (List<ServerConfig>) -> List<KeyDeployResult>,
    onDismiss: () -> Unit
) {
    val selectedIds = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<KeyDeployResult>>(emptyList()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AgPanel,
        title = { Text("部署公钥 · ${key.name}", color = AgText) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (servers.isEmpty()) {
                    Text("暂无可部署的主机", color = AgMuted)
                } else {
                    Text("选择目标服务器（写入 ~/.ssh/authorized_keys）：", style = MaterialTheme.typography.bodySmall, color = AgMuted)
                    servers.forEach { srv ->
                        val checked = srv.id in selectedIds
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (checked) selectedIds.remove(srv.id) else selectedIds.add(srv.id)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                if (it) selectedIds.add(srv.id) else selectedIds.remove(srv.id)
                            })
                            Text("${srv.name} (${srv.host})", style = MaterialTheme.typography.bodyMedium, color = AgText)
                        }
                    }
                }
                results.forEach { res ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (res.ok) AgSuccess else AgDanger).copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${res.serverName}: ${res.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (res.ok) AgSuccess else AgDanger,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targets = servers.filter { it.id in selectedIds }
                    if (targets.isEmpty()) return@Button
                    busy = true
                    scope.launch { results = onDeploy(targets); busy = false }
                },
                enabled = !busy && selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AgAccent)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "部署中..." else "一键部署 (${selectedIds.size})", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭", color = AgMuted) } }
    )
}
