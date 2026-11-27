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
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyType
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
    var portText by remember(initial) { mutableStateOf(initial.port.toString()) }
    var proxyPortText by remember(initial) { mutableStateOf(if (initial.proxy.port > 0) initial.proxy.port.toString() else "") }
    var jumpPortText by remember(initial) { mutableStateOf(initial.jumpHost.port.toString()) }
    var timeoutText by remember(initial) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var keepAliveText by remember(initial) { mutableStateOf(initial.keepAliveSeconds.toString()) }

    var tabIndex by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tabs = listOf("基础", "代理", "跳板机", "高级")

    LaunchedEffect(tabIndex) {
        scrollState.scrollTo(0)
    }

    fun updateDraft(next: ServerConfig) {
        draft = next
        testResult = null
        validationError = null
    }

    fun buildValidatedServer(): ServerConfig? {
        if (draft.host.isBlank()) {
            validationError = "请输入主机地址"
            return null
        }
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            validationError = "主机端口必须在 1–65535 之间"
            return null
        }
        val proxyPort = if (draft.proxy.type != ProxyType.NONE) {
            val pp = proxyPortText.trim().toIntOrNull()
            if (pp == null || pp !in 1..65535) {
                validationError = "代理端口必须在 1–65535 之间"
                return null
            }
            pp
        } else {
            proxyPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
        }
        val jumpPort = if (draft.jumpHost.mode == JumpMode.CUSTOM) {
            val jp = jumpPortText.trim().toIntOrNull()
            if (jp == null || jp !in 1..65535) {
                validationError = "跳板机端口必须在 1–65535 之间"
                return null
            }
            jp
        } else {
            jumpPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        }
        val timeout = timeoutText.trim().toIntOrNull()?.coerceIn(1, 300) ?: 15
        val keepAlive = keepAliveText.trim().toIntOrNull()?.coerceIn(0, 600) ?: 30
        validationError = null
        return draft.copy(
            host = draft.host.trim(),
            port = port,
            proxy = draft.proxy.copy(port = proxyPort),
            jumpHost = draft.jumpHost.copy(port = jumpPort),
            timeoutSeconds = timeout,
            keepAliveSeconds = keepAlive
        )
    }

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
                            onClick = { buildValidatedServer()?.let(onSave) },
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
                    modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    when (tabIndex) {
                        0 -> ServerBasicTab(
                            draft = draft,
                            portText = portText,
                            keys = allKeys,
                            onPortTextChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(5)
                                portText = digits
                                testResult = null
                                validationError = null
                                digits.toIntOrNull()?.takeIf { it in 1..65535 }?.let { draft = draft.copy(port = it) }
                            },
                            onChange = ::updateDraft
                        )
                        1 -> ServerProxyTab(
                            draft = draft,
                            proxyPortText = proxyPortText,
                            onProxyPortTextChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(5)
                                proxyPortText = digits
                                testResult = null
                                validationError = null
                                draft = draft.copy(proxy = draft.proxy.copy(port = digits.toIntOrNull() ?: 0))
                            },
                            onChange = ::updateDraft
                        )
                        2 -> ServerJumpTab(
                            draft = draft,
                            jumpPortText = jumpPortText,
                            allServers = allServers,
                            keys = allKeys,
                            onJumpPortTextChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(5)
                                jumpPortText = digits
                                testResult = null
                                validationError = null
                                digits.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                                    draft = draft.copy(jumpHost = draft.jumpHost.copy(port = it))
                                }
                            },
                            onChange = ::updateDraft
                        )
                        3 -> ServerAdvancedTab(
                            draft = draft,
                            timeoutText = timeoutText,
                            keepAliveText = keepAliveText,
                            onTimeoutTextChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(3)
                                timeoutText = digits
                                testResult = null
                                validationError = null
                                digits.toIntOrNull()?.let { draft = draft.copy(timeoutSeconds = it) }
                            },
                            onKeepAliveTextChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(3)
                                keepAliveText = digits
                                testResult = null
                                validationError = null
                                digits.toIntOrNull()?.let { draft = draft.copy(keepAliveSeconds = it) }
                            },
                            onChange = ::updateDraft,
                            onClearHostCache = {
                                if (draft.host.isNotBlank()) {
                                    val p = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: draft.port
                                    onClearHostCache(draft.host.trim(), p)
                                }
                            }
                        )
                    }
                    validationError?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AgDanger.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AgDanger),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(msg, color = AgDanger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(11.dp))
                        }
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
                            if (testing) return@OutlinedButton
                            val valid = buildValidatedServer() ?: return@OutlinedButton
                            testing = true; testResult = null
                            scope.launch {
                                testResult = onTest(valid).fold({ true to it }, { false to (it.message ?: "连接失败") })
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
