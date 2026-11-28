package com.gravityssh.app.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.components.*
import com.gravityssh.app.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class ServersVaultSlice(
    val servers: List<ServerConfig>,
    val keys: List<SSHKeyRecord>,
    val canLock: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: AppViewModel,
    onConnectAndOpenTerminal: (ServerConfig) -> Unit
) {
    val slice by remember(viewModel) {
        viewModel.vaultState
            .map {
                ServersVaultSlice(
                    servers = it.data.servers,
                    keys = it.data.sshKeys,
                    canLock = it.lockState.hasPassword || it.lockState.biometricEnabled
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = viewModel.vaultState.value.let {
            ServersVaultSlice(
                servers = it.data.servers,
                keys = it.data.sshKeys,
                canLock = it.lockState.hasPassword || it.lockState.biometricEnabled
            )
        }
    )
    val servers = slice.servers
    val keys = slice.keys

    var query by rememberSaveable { mutableStateOf("") }
    var editingServerOpen by rememberSaveable { mutableStateOf(false) }
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingServer by remember { mutableStateOf<ServerConfig?>(null) }

    val editingServer = if (!editingServerOpen) {
        null
    } else {
        val id = editingServerId
        if (id.isNullOrBlank()) ServerConfig() else servers.find { it.id == id }
    }
    LaunchedEffect(editingServerOpen, editingServerId, servers) {
        if (editingServerOpen && !editingServerId.isNullOrBlank() && servers.none { it.id == editingServerId }) {
            editingServerOpen = false
            editingServerId = null
        }
    }

    val filtered = remember(servers, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) servers
        else servers.filter {
            it.name.lowercase().contains(q) || it.host.lowercase().contains(q) ||
                it.group.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.group.ifBlank { "默认分组" } }.toSortedMap() }

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GravitySSH", style = MaterialTheme.typography.titleLarge, color = AgText)
                        Text("${servers.size} 台主机", style = MaterialTheme.typography.labelSmall, color = AgMuted)
                    }
                },
                actions = {
                    if (slice.canLock) {
                        IconButton(onClick = { viewModel.lockVault() }) { Icon(Icons.Rounded.Lock, "锁定", tint = AgMuted) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingServerId = null
                    editingServerOpen = true
                },
                containerColor = AgAccent,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) { Icon(Icons.Rounded.Add, "新建主机") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(10.dp))
            AgTextField(query, { query = it }, "搜索主机", placeholder = "名称 / IP / 分组 / 用户名", trailingIcon = { Icon(Icons.Rounded.Search, null, tint = AgMuted) })
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                AgEmptyState(
                    icon = AgIcons.Dns,
                    title = if (servers.isEmpty()) "还没有配置任何主机" else "没有匹配的主机",
                    subtitle = if (servers.isEmpty()) "点击右下角新建主机，或在设置中从电脑端同步" else "尝试更改搜索关键字",
                    actionLabel = if (servers.isEmpty()) "添加第一台主机" else null,
                    onAction = if (servers.isEmpty()) {
                        {
                            editingServerId = null
                            editingServerOpen = true
                        }
                    } else null
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
                    grouped.forEach { (groupName, list) ->
                        item(key = "hdr_$groupName") {
                            Text("$groupName (${list.size})", style = MaterialTheme.typography.labelMedium, color = AgMuted, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        }
                        items(list, key = { it.id }) { server ->
                            ServerCardItem(
                                server = server,
                                onConnect = { onConnectAndOpenTerminal(server) },
                                onEdit = {
                                    editingServerId = server.id
                                    editingServerOpen = true
                                },
                                onDelete = { deletingServer = server }
                            )
                        }
                    }
                }
            }
        }
    }

    editingServer?.let { server ->
        ServerEditorSheet(
            server, servers, keys,
            onSave = {
                viewModel.saveServer(it)
                editingServerOpen = false
                editingServerId = null
            },
            onTest = { viewModel.testServerConnection(it) },
            onClearHostCache = { h, p -> viewModel.clearHostKnownKey(h, p) },
            onDismiss = {
                editingServerOpen = false
                editingServerId = null
            }
        )
    }
    deletingServer?.let { t ->
        AgConfirmDialog("删除主机", "确定删除「${t.name} (${t.host}:${t.port})」？", "删除", dangerous = true, onConfirm = {
            viewModel.deleteServer(t.id); deletingServer = null
        }, onDismiss = { deletingServer = null })
    }
}
