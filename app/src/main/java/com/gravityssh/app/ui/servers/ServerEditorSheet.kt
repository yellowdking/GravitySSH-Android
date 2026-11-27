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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.JumpHostConfig
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.theme.*
import com.gravityssh.app.vault.VaultCrudOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ServerEditorSheet(
    initial: ServerConfig,
    allServers: List<ServerConfig>,
    allKeys: List<SSHKeyRecord>,
    onSave: (ServerConfig) -> Unit,
    onTest: suspend (ServerConfig) -> Result<String>,
    onClearHostCache: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val draftKey = initial.id
    val initPort = remember(initial) { initial.port.toString() }
    val initProxyPort = remember(initial) { if (initial.proxy.port > 0) initial.proxy.port.toString() else "" }
    val initJumpPort = remember(initial) { initial.jumpHost.port.toString() }
    val initTimeout = remember(initial) { initial.timeoutSeconds.toString() }
    val initKeepAlive = remember(initial) { initial.keepAliveSeconds.toString() }

    // Bundle-safe primitive/String draft fields survive process death & config changes; secrets stay in-memory only.
    var name by rememberSaveable(draftKey) { mutableStateOf(initial.name) }
    var group by rememberSaveable(draftKey) { mutableStateOf(initial.group) }
    var color by rememberSaveable(draftKey) { mutableStateOf(initial.color) }
    var host by rememberSaveable(draftKey) { mutableStateOf(initial.host) }
    var port by rememberSaveable(draftKey) { mutableIntStateOf(initial.port) }
    var username by rememberSaveable(draftKey) { mutableStateOf(initial.username) }
    var authType by rememberSaveable(draftKey) { mutableStateOf(initial.authType) }
    var password by remember(draftKey) { mutableStateOf(initial.password) }
    var sshKeyId by rememberSaveable(draftKey) { mutableStateOf(initial.sshKeyId) }

    var proxyType by rememberSaveable(draftKey) { mutableStateOf(initial.proxy.type) }
    var proxyHost by rememberSaveable(draftKey) { mutableStateOf(initial.proxy.host) }
    var proxyPort by rememberSaveable(draftKey) { mutableIntStateOf(initial.proxy.port) }
    var proxyUsername by rememberSaveable(draftKey) { mutableStateOf(initial.proxy.username) }
    var proxyPassword by remember(draftKey) { mutableStateOf(initial.proxy.password) }

    var jumpMode by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.mode) }
    var jumpServerId by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.serverId) }
    var jumpHost by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.host) }
    var jumpPort by rememberSaveable(draftKey) { mutableIntStateOf(initial.jumpHost.port) }
    var jumpUsername by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.username) }
    var jumpAuthType by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.authType) }
    var jumpPassword by remember(draftKey) { mutableStateOf(initial.jumpHost.password) }
    var jumpSshKeyId by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.sshKeyId) }
    var jumpUseMainProxy by rememberSaveable(draftKey) { mutableStateOf(initial.jumpHost.useMainProxy) }

    var timeoutSeconds by rememberSaveable(draftKey) { mutableIntStateOf(initial.timeoutSeconds) }
    var keepAliveSeconds by rememberSaveable(draftKey) { mutableIntStateOf(initial.keepAliveSeconds) }
    var defaultRemotePath by rememberSaveable(draftKey) { mutableStateOf(initial.defaultRemotePath) }
    var startupCommand by rememberSaveable(draftKey) { mutableStateOf(initial.startupCommand) }
    var notes by rememberSaveable(draftKey) { mutableStateOf(initial.notes) }

    var portText by rememberSaveable(draftKey) { mutableStateOf(initPort) }
    var proxyPortText by rememberSaveable(draftKey) { mutableStateOf(initProxyPort) }
    var jumpPortText by rememberSaveable(draftKey) { mutableStateOf(initJumpPort) }
    var timeoutText by rememberSaveable(draftKey) { mutableStateOf(initTimeout) }
    var keepAliveText by rememberSaveable(draftKey) { mutableStateOf(initKeepAlive) }

    val draft = ServerConfig(
        id = initial.id,
        name = name,
        group = group,
        color = color,
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = password,
        sshKeyId = sshKeyId,
        proxy = ProxyConfig(
            type = proxyType,
            host = proxyHost,
            port = proxyPort,
            username = proxyUsername,
            password = proxyPassword
        ),
        jumpHost = JumpHostConfig(
            mode = jumpMode,
            serverId = jumpServerId,
            host = jumpHost,
            port = jumpPort,
            username = jumpUsername,
            authType = jumpAuthType,
            password = jumpPassword,
            sshKeyId = jumpSshKeyId,
            useMainProxy = jumpUseMainProxy
        ),
        timeoutSeconds = timeoutSeconds,
        keepAliveSeconds = keepAliveSeconds,
        defaultRemotePath = defaultRemotePath,
        startupCommand = startupCommand,
        notes = notes,
        createdAt = initial.createdAt,
        updatedAt = initial.updatedAt,
        lastConnectedAt = initial.lastConnectedAt
    )

    var tabIndex by rememberSaveable(draftKey) { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tabs = listOf("基础", "代理", "跳板机", "高级")

    val hasChanges = draft != initial ||
        portText != initPort ||
        proxyPortText != initProxyPort ||
        jumpPortText != initJumpPort ||
        timeoutText != initTimeout ||
        keepAliveText != initKeepAlive

    fun requestDismiss() {
        if (hasChanges) confirmDiscard = true else onDismiss()
    }

    LaunchedEffect(tabIndex) {
        scrollState.scrollTo(0)
    }

    fun updateDraft(next: ServerConfig) {
        name = next.name
        group = next.group
        color = next.color
        host = next.host
        port = next.port
        username = next.username
        authType = next.authType
        password = next.password
        sshKeyId = next.sshKeyId
        proxyType = next.proxy.type
        proxyHost = next.proxy.host
        proxyPort = next.proxy.port
        proxyUsername = next.proxy.username
        proxyPassword = next.proxy.password
        jumpMode = next.jumpHost.mode
        jumpServerId = next.jumpHost.serverId
        jumpHost = next.jumpHost.host
        jumpPort = next.jumpHost.port
        jumpUsername = next.jumpHost.username
        jumpAuthType = next.jumpHost.authType
        jumpPassword = next.jumpHost.password
        jumpSshKeyId = next.jumpHost.sshKeyId
        jumpUseMainProxy = next.jumpHost.useMainProxy
        timeoutSeconds = next.timeoutSeconds
        keepAliveSeconds = next.keepAliveSeconds
        defaultRemotePath = next.defaultRemotePath
        startupCommand = next.startupCommand
        notes = next.notes
        testResult = null
        validationError = null
    }

    fun buildValidatedServer(): ServerConfig? {
        val trimmedHost = draft.host.trim()
        if (trimmedHost.isEmpty()) {
            validationError = "请输入主机地址"
            return null
        }
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            validationError = "主机端口必须在 1–65535 之间"
            return null
        }
        val trimmedKeyId = draft.sshKeyId.trim()
        if (draft.authType == AuthType.KEY || draft.authType == AuthType.BOTH) {
            if (trimmedKeyId.isEmpty() || allKeys.none { it.id == trimmedKeyId }) {
                validationError = "请选择有效的 SSH 密钥"
                return null
            }
        }
        val trimmedProxyHost = draft.proxy.host.trim()
        val proxyPort = if (draft.proxy.type != ProxyType.NONE) {
            if (trimmedProxyHost.isEmpty()) {
                validationError = "请输入代理地址"
                return null
            }
            val pp = proxyPortText.trim().toIntOrNull()
            if (pp == null || pp !in 1..65535) {
                validationError = "代理端口必须在 1–65535 之间"
                return null
            }
            pp
        } else {
            proxyPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
        }
        val trimmedJumpServerId = draft.jumpHost.serverId.trim()
        val trimmedJumpHost = draft.jumpHost.host.trim()
        val trimmedJumpKeyId = draft.jumpHost.sshKeyId.trim()
        val jumpPort = when (draft.jumpHost.mode) {
            JumpMode.SERVER -> {
                if (trimmedJumpServerId.isEmpty() || allServers.none { it.id == trimmedJumpServerId }) {
                    validationError = "请选择有效的跳板主机"
                    return null
                }
                if (VaultCrudOps.wouldCreateJumpCycle(draft.id, trimmedJumpServerId, allServers)) {
                    validationError = "跳板机链路存在循环引用，请重新选择"
                    return null
                }
                jumpPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
            }
            JumpMode.CUSTOM -> {
                if (trimmedJumpHost.isEmpty()) {
                    validationError = "请输入跳板机地址"
                    return null
                }
                val jp = jumpPortText.trim().toIntOrNull()
                if (jp == null || jp !in 1..65535) {
                    validationError = "跳板机端口必须在 1–65535 之间"
                    return null
                }
                if (draft.jumpHost.authType == AuthType.KEY || draft.jumpHost.authType == AuthType.BOTH) {
                    if (trimmedJumpKeyId.isEmpty() || allKeys.none { it.id == trimmedJumpKeyId }) {
                        validationError = "请选择有效的跳板机 SSH 密钥"
                        return null
                    }
                }
                jp
            }
            else -> jumpPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        }
        val timeoutRaw = timeoutText.trim()
        val timeout = if (timeoutRaw.isEmpty()) {
            15
        } else {
            val t = timeoutRaw.toIntOrNull()
            if (t == null || t !in VaultCrudOps.MIN_TIMEOUT_SECONDS..VaultCrudOps.MAX_TIMEOUT_SECONDS) {
                validationError = "连接超时必须在 ${VaultCrudOps.MIN_TIMEOUT_SECONDS}–${VaultCrudOps.MAX_TIMEOUT_SECONDS} 秒之间"
                return null
            }
            t
        }
        val keepAliveRaw = keepAliveText.trim()
        val keepAlive = if (keepAliveRaw.isEmpty()) {
            30
        } else {
            val k = keepAliveRaw.toIntOrNull()
            if (k == null || k !in VaultCrudOps.MIN_KEEPALIVE_SECONDS..VaultCrudOps.MAX_KEEPALIVE_SECONDS) {
                validationError = "心跳间隔必须在 ${VaultCrudOps.MIN_KEEPALIVE_SECONDS}–${VaultCrudOps.MAX_KEEPALIVE_SECONDS} 秒之间"
                return null
            }
            k
        }
        validationError = null
        return draft.copy(
            host = trimmedHost,
            port = port,
            username = draft.username.trim().ifBlank { "root" },
            sshKeyId = trimmedKeyId,
            proxy = draft.proxy.copy(
                host = trimmedProxyHost,
                port = proxyPort,
                username = draft.proxy.username.trim()
            ),
            jumpHost = draft.jumpHost.copy(
                serverId = trimmedJumpServerId,
                host = trimmedJumpHost,
                port = jumpPort,
                username = draft.jumpHost.username.trim(),
                sshKeyId = trimmedJumpKeyId
            ),
            timeoutSeconds = timeout,
            keepAliveSeconds = keepAlive
        )
    }

    Dialog(onDismissRequest = ::requestDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = AgBg) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AgSurface).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (initial.id.isBlank()) "新建主机" else "编辑主机", style = MaterialTheme.typography.titleLarge, color = AgText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = ::requestDismiss) { Text("取消", color = AgMuted) }
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
                                digits.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }
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
                                proxyPort = digits.toIntOrNull() ?: 0
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
                                    jumpPort = it
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
                                digits.toIntOrNull()?.let { timeoutSeconds = it }
                            },
                            onKeepAliveTextChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(3)
                                keepAliveText = digits
                                testResult = null
                                validationError = null
                                digits.toIntOrNull()?.let { keepAliveSeconds = it }
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
                }
                if (validationError != null || testResult != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(AgPanel).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        validationError?.let { AgStatusBanner(message = it, color = AgDanger) }
                        testResult?.let { (ok, msg) ->
                            AgStatusBanner(message = msg, color = if (ok) AgSuccess else AgDanger)
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
                                try {
                                    testResult = onTest(valid).fold({ true to it }, { false to (it.message ?: "连接失败") })
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    testResult = false to (e.message ?: "连接失败")
                                } finally {
                                    testing = false
                                }
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

    if (confirmDiscard) {
        AgConfirmDialog(
            title = "放弃未保存更改",
            message = "当前主机配置有未保存的修改，确定放弃并关闭？",
            confirmLabel = "放弃修改",
            dangerous = true,
            onConfirm = { confirmDiscard = false; onDismiss() },
            onDismiss = { confirmDiscard = false }
        )
    }
}
