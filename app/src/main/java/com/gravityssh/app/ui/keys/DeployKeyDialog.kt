package com.gravityssh.app.ui.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.CancellationException
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
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(results) {
        if (results.isNotEmpty()) {
            scrollState.animateScrollTo(0)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AgPanel,
        title = { Text("部署公钥 · ${key.name}", color = AgText) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (results.isNotEmpty()) {
                    Text("部署结果：", style = MaterialTheme.typography.labelMedium, color = AgCyan)
                    results.forEach { res ->
                        AgStatusBanner(
                            message = "${res.serverName}: ${res.message}",
                            color = if (res.ok) AgSuccess else AgDanger
                        )
                    }
                    HorizontalDivider(color = AgBorder, modifier = Modifier.padding(vertical = 4.dp))
                }
                if (servers.isEmpty()) {
                    Text("暂无可部署的主机", color = AgMuted)
                } else {
                    Text("选择目标服务器（写入 ~/.ssh/authorized_keys）：", style = MaterialTheme.typography.bodySmall, color = AgMuted)
                    servers.forEach { srv ->
                        val checked = srv.id in selectedIds
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                if (checked) selectedIds.remove(srv.id) else selectedIds.add(srv.id)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = !busy,
                                onCheckedChange = {
                                    if (it) selectedIds.add(srv.id) else selectedIds.remove(srv.id)
                                }
                            )
                            Text("${srv.name} (${srv.host})", style = MaterialTheme.typography.bodyMedium, color = AgText)
                        }
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
                    results = emptyList()
                    scope.launch {
                        try {
                            results = onDeploy(targets)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            results = targets.map {
                                KeyDeployResult(it.id, it.name, ok = false, message = e.message ?: "部署异常")
                            }
                        } finally {
                            busy = false
                        }
                    }
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
