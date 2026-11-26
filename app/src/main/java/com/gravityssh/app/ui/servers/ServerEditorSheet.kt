package com.gravityssh.app.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ServerEditorSheet(
    initial: ServerConfig,
    allServers: List<ServerConfig>,
    allKeys: List<SSHKeyRecord>,
    onSave: (ServerConfig) -> Unit,
    onTest: suspend (ServerConfig) -> Result<String>,
    onClearHostCache: (String, Int) -> Boolean,
    onDismiss: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val tabs = listOf("基础", "代理", "跳板机", "高级")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = AgBg) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (initial.id.isBlank()) "新建主机" else "编辑主机", style = MaterialTheme.typography.titleLarge, color = AgText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("取消", color = AgMuted) }
                        Button(
                            onClick = { if (draft.host.isNotBlank()) onSave(draft) },
                            enabled = draft.host.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AgAccent),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("保存", color = Color.White) }
                    }
                }
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = AgSurface,
                    contentColor = AgAccent,
                    indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tabIndex]), color = AgAccent) }
                ) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title, color = if (tabIndex == i) AgText else AgMuted) })
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    when (tabIndex) {
                        0 -> ServerBasicTab(draft, allKeys) { draft = it }
                        1 -> ServerProxyTab(draft) { draft = it }
                        2 -> ServerJumpTab(draft, allServers, allKeys) { draft = it }
                        3 -> ServerAdvancedTab(draft, { draft = it }) { if (draft.host.isNotBlank()) onClearHostCache(draft.host, draft.port) }
                    }
                    testResult?.let { (ok, msg) ->
                        val c = if (ok) AgSuccess else AgDanger
                        Surface(shape = RoundedCornerShape(10.dp), color = c.copy(alpha = 0.14f), border = BorderStroke(1.dp, c), modifier = Modifier.fillMaxWidth()) {
                            Text(msg, color = c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(11.dp))
                        }
                    }
                }
                HorizontalDivider(color = AgBorder)
                Row(modifier = Modifier.fillMaxWidth().background(AgPanel).padding(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (draft.host.isBlank() || testing) return@OutlinedButton
                            testing = true; testResult = null
                            scope.launch {
                                testResult = onTest(draft).fold({ true to it }, { false to (it.message ?: "连接失败") })
                                testing = false
                            }
                        },
                        enabled = draft.host.isNotBlank() && !testing,
                        border = BorderStroke(1.dp, AgCyan.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AgCyan)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (testing) "测试连接中..." else "测试连接", color = AgCyan)
                    }
                }
            }
        }
    }
}
