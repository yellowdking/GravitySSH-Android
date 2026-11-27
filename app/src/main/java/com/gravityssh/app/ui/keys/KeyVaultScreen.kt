package com.gravityssh.app.ui.keys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.ui.*
import com.gravityssh.app.ui.components.AgConfirmDialog
import com.gravityssh.app.ui.components.AgEmptyState
import com.gravityssh.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyVaultScreen(viewModel: AppViewModel) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val keys = vaultState.data.sshKeys
    val servers = vaultState.data.servers
    val clipboard = LocalClipboardManager.current

    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deployingKey by remember { mutableStateOf<SSHKeyRecord?>(null) }
    var deletingKey by remember { mutableStateOf<SSHKeyRecord?>(null) }

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SSH 密钥库", style = MaterialTheme.typography.titleLarge, color = AgText)
                        Text("${keys.size} 把密钥 · 硬件级加密存储", style = MaterialTheme.typography.labelSmall, color = AgMuted)
                    }
                },
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "导入私钥", tint = AgCyan)
                    }
                    IconButton(onClick = { showGenerate = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "生成密钥", tint = AgAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        }
    ) { padding ->
        if (keys.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AgEmptyState(
                    icon = Icons.Rounded.Key,
                    title = "暂无 SSH 密钥",
                    subtitle = "可本地生成 Ed25519 / RSA-4096 密钥对，或导入 PEM/OpenSSH 私钥",
                    actionLabel = "生成新密钥",
                    onAction = { showGenerate = true }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(keys, key = { it.id }) { key ->
                    KeyCardItem(
                        key = key,
                        onCopy = {
                            clipboard.setText(AnnotatedString(key.publicKey))
                            viewModel.showToast("已复制公钥")
                        },
                        onDeploy = { deployingKey = key },
                        onDelete = { deletingKey = key }
                    )
                }
            }
        }
    }

    if (showGenerate) {
        GenerateKeyDialog(
            onGenerate = { t, n, c, done -> viewModel.generateKey(t, n, c, done) },
            onDismiss = { showGenerate = false }
        )
    }
    if (showImport) {
        ImportKeyDialog(
            onImport = { n, p, pass, c, done -> viewModel.importKey(n, p, pass, c, done) },
            onToast = viewModel::showToast,
            onDismiss = { showImport = false }
        )
    }
    deployingKey?.let { key ->
        DeployKeyDialog(key, servers, { viewModel.deployKey(key, it) }, { deployingKey = null })
    }
    deletingKey?.let { key ->
        AgConfirmDialog(
            title = "删除 SSH 密钥",
            message = "确定删除密钥「${key.name}」？",
            confirmLabel = "删除",
            dangerous = true,
            onConfirm = { viewModel.deleteKey(key.id); deletingKey = null },
            onDismiss = { deletingKey = null }
        )
    }
}
