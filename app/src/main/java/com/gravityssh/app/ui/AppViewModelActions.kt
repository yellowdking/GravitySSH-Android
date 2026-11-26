package com.gravityssh.app.ui

import androidx.lifecycle.viewModelScope
import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.KeyDeployResult
import com.gravityssh.app.model.PresetLoadResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.ssh.SshKeyGenerator
import com.gravityssh.app.sync.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun AppViewModel.saveServer(server: ServerConfig) {
    runCatching { container.vaultRepository.saveServer(server) }
        .onSuccess { showToast("已保存主机 ${it.name}") }
        .onFailure { showToast("保存失败: ${it.message}") }
}

fun AppViewModel.deleteServer(id: String) {
    runCatching { container.vaultRepository.deleteServer(id) }
        .onSuccess { showToast("已删除主机") }
        .onFailure { showToast("删除失败: ${it.message}") }
}

fun AppViewModel.generateKey(type: String, name: String, comment: String, onDone: () -> Unit) {
    viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.Default) {
                if (type == "rsa") SshKeyGenerator.generateRsa4096(name, comment)
                else SshKeyGenerator.generateEd25519(name, comment)
            }
        }.onSuccess { key ->
            container.vaultRepository.saveSshKey(key)
            showToast("已生成 ${key.keyType.uppercase()} 密钥 ${key.name}")
            onDone()
        }.onFailure { showToast("生成密钥失败: ${it.message}") }
    }
}

fun AppViewModel.importKey(name: String, pem: String, passphrase: String, comment: String, onDone: () -> Unit) {
    viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.Default) { SshKeyGenerator.importKey(name, pem, passphrase, comment) }
        }.onSuccess { key ->
            container.vaultRepository.saveSshKey(key)
            showToast("已导入密钥 ${key.name}")
            onDone()
        }.onFailure { showToast("导入失败: ${it.message}") }
    }
}

fun AppViewModel.deleteKey(id: String) {
    runCatching { container.vaultRepository.deleteSshKey(id) }
        .onSuccess { showToast("已删除 SSH 密钥") }
        .onFailure { showToast("删除失败: ${it.message}") }
}

suspend fun AppViewModel.deployKey(key: SSHKeyRecord, targets: List<ServerConfig>): List<KeyDeployResult> =
    vaultState.value.data.let { container.keyDeployer.deployToServers(key, targets, it.servers, it.sshKeys) }

fun AppViewModel.saveShortcut(sc: CommandShortcut) {
    runCatching { container.vaultRepository.saveShortcut(sc) }
        .onSuccess { showToast("已保存快捷命令 ${it.name}") }
        .onFailure { showToast("保存失败: ${it.message}") }
}

fun AppViewModel.deleteShortcut(id: String) {
    runCatching { container.vaultRepository.deleteShortcut(id) }.onSuccess { showToast("已删除快捷命令") }
}

fun AppViewModel.runShortcutInActive(sc: CommandShortcut): Boolean {
    val sid = activeSessionId.value ?: return false
    container.vaultRepository.incrementShortcutUse(sc.id)
    container.sshSessionManager.runScript(sid, sc.command, sc.autoExecute)
    return true
}

fun AppViewModel.runCommandInActive(command: String, autoExecute: Boolean = true): Boolean {
    val sid = activeSessionId.value ?: return false
    container.sshSessionManager.runScript(sid, command, autoExecute)
    return true
}

fun AppViewModel.saveTemplate(tpl: BuiltinTemplate) {
    runCatching { container.vaultRepository.saveTemplate(tpl) }
        .onSuccess { showToast("已保存运维模板 ${it.name}") }
        .onFailure { showToast("保存失败: ${it.message}") }
}

fun AppViewModel.deleteTemplate(id: String) {
    runCatching { container.vaultRepository.deleteTemplate(id) }.onSuccess { showToast("已删除运维模板") }
}

fun AppViewModel.loadPresetTemplates(): PresetLoadResult? =
    runCatching { container.vaultRepository.loadPresetTemplates() }
        .onSuccess { showToast("预置模板加载完成：新增 ${it.added} 项，跳过 ${it.skipped} 项") }
        .onFailure { showToast("加载失败: ${it.message}") }
        .getOrNull()

fun AppViewModel.clearHistory() {
    runCatching { container.vaultRepository.clearHistory() }.onSuccess { showToast("已清空命令历史") }
}

fun AppViewModel.updateSettings(transform: (AppSettings) -> AppSettings) {
    runCatching { container.vaultRepository.updateSettings(transform) }.onFailure { showToast("更新设置失败: ${it.message}") }
}

fun AppViewModel.cycleTouchCursorMode() {
    val (next, label) = when (vaultState.value.data.settings.touchCursorMode.lowercase()) {
        "auto" -> "cursor" to "光标"; "cursor" -> "scroll" to "滚屏"; else -> "auto" to "自动"
    }
    updateSettings { it.copy(touchCursorMode = next) }; showToast("触控模式：$label")
}

fun AppViewModel.cycleTouchCursorSensitivity() {
    val (next, label) = when (vaultState.value.data.settings.touchCursorSensitivity.lowercase()) {
        "precise" -> "balanced" to "标准"; "balanced" -> "fast" to "疾速"; else -> "precise" to "精细"
    }
    updateSettings { it.copy(touchCursorSensitivity = next) }; showToast("光标灵敏度：$label")
}

fun AppViewModel.saveSyncRelayUrl(rawUrl: String) {
    runCatching { SyncConfig.normalizeRelayUrl(rawUrl) }.onSuccess { norm ->
        updateSettings { it.copy(syncRelayUrl = norm) }
        showToast(if (norm.isEmpty()) "已恢复默认同步中继" else "已保存中继地址 $norm")
    }.onFailure { showToast(it.message ?: "中继地址无效") }
}

fun AppViewModel.testSyncRelay(rawUrl: String, onResult: (Result<Long>) -> Unit) {
    viewModelScope.launch {
        onResult(runCatching { container.syncService.testRelay(rawUrl.trim().ifEmpty { null }) })
    }
}
