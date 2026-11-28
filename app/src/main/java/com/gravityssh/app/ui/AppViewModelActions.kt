package com.gravityssh.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.GravitySshApp
import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.KeyDeployResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncInboxItemUi
import com.gravityssh.app.model.SyncPingResultUi
import com.gravityssh.app.model.SyncRelayStatusUi
import com.gravityssh.app.ssh.SshKeyGenerator
import com.gravityssh.app.sync.SyncConfig
import com.gravityssh.app.terminal.TouchCursorMode
import com.gravityssh.app.terminal.TouchSensitivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun AppViewModel.unlockVault(password: String, onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
        val res = withContext(Dispatchers.IO) { container.vaultRepository.unlock(password) }
        onResult(res)
    }
}

fun AppViewModel.unlockVaultWithBiometric(onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
        val res = withContext(Dispatchers.IO) { container.vaultRepository.unlockWithBiometric() }
        onResult(res)
    }
}

fun AppViewModel.resetCorruptedVault(onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
        val res = withContext(Dispatchers.IO) { container.vaultRepository.resetCorruptedVault() }
        res.onSuccess { showToast("已备份旧文件并重置保险库") }
            .onFailure { showToast("重置保险库失败: ${it.message}") }
        onResult(res)
    }
}

fun AppViewModel.setMasterPassword(current: String, next: String, onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
        val res = withContext(Dispatchers.IO) { container.vaultRepository.setMasterPassword(current, next) }
        res.onSuccess { showToast(if (next.isEmpty()) "已移除主密码" else "主密码已更新") }
            .onFailure { showToast(it.message ?: "操作失败") }
        onResult(res)
    }
}

fun AppViewModel.saveServer(server: ServerConfig) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.saveServer(server) }
            .onSuccess { showToast("已保存主机 ${it.name}") }
            .onFailure { showToast("保存失败: ${it.message}") }
    }
}

fun AppViewModel.deleteServer(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.deleteServer(id) }
            .onSuccess { showToast("已删除主机") }
            .onFailure { showToast("删除失败: ${it.message}") }
    }
}

fun AppViewModel.generateKey(type: String, name: String, comment: String, onResult: (Boolean) -> Unit) {
    viewModelScope.launch {
        val ok = runCatching {
            val key = withContext(Dispatchers.Default) {
                GravitySshApp.ensureBouncyCastleRegistered()
                if (type == "rsa") SshKeyGenerator.generateRsa4096(name, comment)
                else SshKeyGenerator.generateEd25519(name, comment)
            }
            withContext(Dispatchers.IO) { container.vaultRepository.saveSshKey(key) }
            key
        }.onSuccess { key ->
            showToast("已生成 ${key.keyType.uppercase()} 密钥 ${key.name}")
        }.onFailure {
            showToast("生成密钥失败: ${it.message}")
        }.isSuccess
        onResult(ok)
    }
}

fun AppViewModel.importKey(name: String, pem: String, passphrase: String, comment: String, onResult: (Boolean) -> Unit) {
    viewModelScope.launch {
        val ok = runCatching {
            val key = withContext(Dispatchers.Default) {
                GravitySshApp.ensureBouncyCastleRegistered()
                SshKeyGenerator.importKey(name, pem, passphrase, comment)
            }
            withContext(Dispatchers.IO) { container.vaultRepository.saveSshKey(key) }
            key
        }.onSuccess { key ->
            showToast("已导入密钥 ${key.name}")
        }.onFailure {
            showToast("导入失败: ${it.message}")
        }.isSuccess
        onResult(ok)
    }
}

fun AppViewModel.deleteKey(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.deleteSshKey(id) }
            .onSuccess { showToast("已删除 SSH 密钥") }
            .onFailure { showToast("删除失败: ${it.message}") }
    }
}

suspend fun AppViewModel.testServerConnection(server: ServerConfig): Result<String> {
    withContext(Dispatchers.Default) { GravitySshApp.ensureBouncyCastleRegistered() }
    return container.sshSessionManager.testConnection(server)
}

suspend fun AppViewModel.deployKey(key: SSHKeyRecord, targets: List<ServerConfig>): List<KeyDeployResult> {
    withContext(Dispatchers.Default) { GravitySshApp.ensureBouncyCastleRegistered() }
    return vaultState.value.data.let { container.keyDeployer.deployToServers(key, targets, it.servers, it.sshKeys) }
}

fun AppViewModel.saveShortcut(sc: CommandShortcut) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.saveShortcut(sc) }
            .onSuccess { showToast("已保存快捷命令 ${it.name}") }
            .onFailure { showToast("保存失败: ${it.message}") }
    }
}

fun AppViewModel.deleteShortcut(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.deleteShortcut(id) }
            .onSuccess { showToast("已删除快捷命令") }
            .onFailure { showToast("删除失败: ${it.message}") }
    }
}

fun AppViewModel.runShortcutInActive(sc: CommandShortcut): Boolean {
    val sid = activeSessionId.value ?: return false
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.incrementShortcutUse(sc.id) }
    }
    container.sshSessionManager.runScript(sid, sc.command, sc.autoExecute)
    return true
}

fun AppViewModel.runCommandInActive(command: String, autoExecute: Boolean = true): Boolean {
    val sid = activeSessionId.value ?: return false
    container.sshSessionManager.runScript(sid, command, autoExecute)
    return true
}

fun AppViewModel.saveTemplate(tpl: BuiltinTemplate) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.saveTemplate(tpl) }
            .onSuccess { showToast("已保存运维模板 ${it.name}") }
            .onFailure { showToast("保存失败: ${it.message}") }
    }
}

fun AppViewModel.deleteTemplate(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.deleteTemplate(id) }
            .onSuccess { showToast("已删除运维模板") }
            .onFailure { showToast("删除失败: ${it.message}") }
    }
}

fun AppViewModel.loadPresetTemplates() {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.loadPresetTemplates() }
            .onSuccess { showToast("预置模板加载完成：新增 ${it.added} 项，跳过 ${it.skipped} 项") }
            .onFailure { showToast("加载失败: ${it.message}") }
    }
}

fun AppViewModel.clearHistory() {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.clearHistory() }
            .onSuccess { showToast("已清空命令历史") }
            .onFailure { showToast("清空失败: ${it.message}") }
    }
}

fun AppViewModel.updateSettings(
    onSuccess: (() -> Unit)? = null,
    transform: (AppSettings) -> AppSettings
) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.updateSettings(transform) }
            .onSuccess { onSuccess?.invoke() }
            .onFailure { showToast("更新设置失败: ${it.message}") }
    }
}

fun AppViewModel.setBiometricLockEnabled(
    enabled: Boolean,
    onResult: (Result<Unit>) -> Unit = {}
) {
    viewModelScope.launch {
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val saved = container.vaultRepository.updateSettings {
                    it.copy(biometricLockEnabled = enabled)
                }
                if (enabled && !saved.biometricLockEnabled) {
                    throw IllegalStateException("已设置主密码，无法同时启用生物识别锁")
                }
            }
        }
        onResult(res)
    }
}

fun AppViewModel.cycleTouchCursorMode() {
    val current = TouchCursorMode.fromId(vaultState.value.data.settings.touchCursorMode)
    val next = TouchCursorMode.next(current)
    updateSettings { it.copy(touchCursorMode = next.id) }
    showToast("触控模式：${next.label}")
}

fun AppViewModel.cycleTouchCursorSensitivity() {
    val current = TouchSensitivity.fromId(vaultState.value.data.settings.touchCursorSensitivity)
    val next = TouchSensitivity.next(current)
    updateSettings { it.copy(touchCursorSensitivity = next.id) }
    showToast("光标灵敏度：${next.label}")
}

// ---------------------------------------------------------------------------
// Sync Actions (delegating to locked SyncService synctool-v1 contract)
// ---------------------------------------------------------------------------

fun AppViewModel.clearLastSyncSummary() {
    _lastSyncSummary.value = null
}

fun AppViewModel.refreshSyncOverview() {
    container.syncService.refreshOverview()
}

fun AppViewModel.saveCustomSyncRelay(
    rawUri: String,
    onResult: (Result<SyncRelayStatusUi>) -> Unit = {}
) {
    val trimmed = rawUri.trim()
    viewModelScope.launch {
        val res = container.syncService.saveCustomRelayUri(trimmed)
        res.onSuccess { status ->
            if (trimmed.isEmpty() || status.mode != "custom") {
                showToast("已恢复默认官方中继")
            } else {
                val safeDisplay = SyncConfig.redactUri(status.endpoint)
                    .substringBefore('#')
                    .ifBlank { status.redactedUri.substringBefore('#') }
                showToast("已保存自定义中继: $safeDisplay")
            }
        }.onFailure { err ->
            showToast((err.message ?: "中继地址无效").substringBefore('#'))
        }
        onResult(res)
    }
}

fun AppViewModel.testSyncRelay(
    rawUri: String = "",
    onResult: (Result<SyncPingResultUi>) -> Unit = {}
) {
    viewModelScope.launch {
        val res = container.syncService.testRelay(rawUri.trim())
        onResult(res)
    }
}

fun AppViewModel.updateSyncDeviceLabel(
    label: String,
    onResult: (Result<Unit>) -> Unit = {}
) {
    viewModelScope.launch {
        val res = container.syncService.updateDeviceLabel(label.trim())
        res.onSuccess { showToast("已更新本机同步名称") }
            .onFailure { showToast(it.message ?: "更新设备名称失败") }
        onResult(res)
    }
}

fun AppViewModel.startSyncInboxWatch() {
    container.syncService.startForegroundInboxWatch()
}

fun AppViewModel.stopSyncInboxWatch() {
    container.syncService.stopForegroundInboxWatch()
}

fun AppViewModel.refreshSyncInbox(
    silent: Boolean = false,
    onResult: (Result<List<SyncInboxItemUi>>) -> Unit = {}
) {
    viewModelScope.launch {
        val res = container.syncService.refreshInbox()
        if (!silent) {
            res.onFailure { showToast(it.message ?: "刷新收件箱失败") }
        }
        onResult(res)
    }
}

fun AppViewModel.sendSyncToPairedDevice(
    deviceId: String,
    deliveryMode: String = "auto",
    onResult: (Result<Unit>) -> Unit = {}
) {
    if (_syncActionBusy.value) return
    viewModelScope.launch {
        _syncActionBusy.value = true
        try {
            val res = container.syncService.sendToPairedDevice(deviceId, deliveryMode)
            res.onSuccess {
                showToast(if (deliveryMode == "pipe") "实时直传完成" else "已推送")
            }.onFailure { err ->
                showToast(err.message ?: "推送失败")
            }
            onResult(res)
        } finally {
            _syncActionBusy.value = false
        }
    }
}

fun AppViewModel.applySyncInboxItem(
    itemId: String,
    importMode: String,
    onResult: (Result<SyncImportSummary>) -> Unit = {}
) {
    if (_syncActionBusy.value) return
    viewModelScope.launch {
        _syncActionBusy.value = true
        try {
            val res = container.syncService.applyInboxItem(itemId, importMode)
            res.onSuccess { summary ->
                _lastSyncSummary.value = summary
                showToast("导入完成")
            }.onFailure { err ->
                showToast(err.message ?: "导入失败")
            }
            onResult(res)
        } finally {
            _syncActionBusy.value = false
        }
    }
}

fun AppViewModel.dismissSyncInboxItem(
    itemId: String,
    onResult: (Result<Unit>) -> Unit = {}
) {
    if (_syncActionBusy.value) return
    viewModelScope.launch {
        _syncActionBusy.value = true
        try {
            val res = container.syncService.dismissInboxItem(itemId)
            res.onSuccess { showToast("已删除") }
                .onFailure { showToast(it.message ?: "删除失败") }
            onResult(res)
        } finally {
            _syncActionBusy.value = false
        }
    }
}

fun AppViewModel.revokeSyncPairedDevice(
    deviceId: String,
    onResult: (Result<Unit>) -> Unit = {}
) {
    if (_syncActionBusy.value) return
    viewModelScope.launch {
        _syncActionBusy.value = true
        try {
            val res = container.syncService.revokePairedDevice(deviceId)
            res.onSuccess { showToast("已解除配对") }
                .onFailure { showToast(it.message ?: "解除配对失败") }
            onResult(res)
        } finally {
            _syncActionBusy.value = false
        }
    }
}

fun AppViewModel.startSyncDeviceBindOffer(mode: Int, includeRelay: Boolean = false) {
    _lastSyncSummary.value = null
    container.syncService.startDeviceBindOffer(mode, includeRelay)
}

fun AppViewModel.acceptSyncDeviceBind(codeOrUri: String) {
    val clean = codeOrUri.trim()
    if (clean.isEmpty()) return
    _lastSyncSummary.value = null
    container.syncService.acceptDeviceBind(clean)
}

fun AppViewModel.startSyncOneShotSend(mode: Int, includeRelay: Boolean = false) {
    _lastSyncSummary.value = null
    container.syncService.startOneShotSend(mode, includeRelay)
}

fun AppViewModel.startSyncReceive(codeOrUri: String) {
    val clean = codeOrUri.trim()
    if (clean.isEmpty()) return
    _lastSyncSummary.value = null
    container.syncService.startReceive(clean)
}

fun AppViewModel.submitSyncSasInput(sasDigits: String) {
    container.syncService.submitSasInput(sasDigits.trim())
}

fun AppViewModel.confirmSyncResponderSas(approved: Boolean) {
    container.syncService.confirmResponderSas(approved)
}

fun AppViewModel.cancelSyncFlow() {
    container.syncService.cancelActiveFlow()
}

fun AppViewModel.applySyncPendingPreview(
    importMode: String,
    onResult: (Result<SyncImportSummary>) -> Unit = {}
) {
    if (_syncActionBusy.value) return
    viewModelScope.launch {
        _syncActionBusy.value = true
        try {
            val res = container.syncService.applyPendingPreview(importMode)
            res.onSuccess { summary ->
                _lastSyncSummary.value = summary
                showToast("同步导入完成")
            }.onFailure { err ->
                showToast(err.message ?: "同步导入失败")
            }
            onResult(res)
        } finally {
            _syncActionBusy.value = false
        }
    }
}

fun AppViewModel.clearSyncPendingPreview() {
    container.syncService.clearPendingPreview()
}

// ---------------------------------------------------------------------------
// SFTP Transfer & File Actions
// ---------------------------------------------------------------------------

internal fun sanitizeUploadDisplayName(raw: String?): String {
    if (raw.isNullOrBlank() || '\u0000' in raw) return "upload.bin"
    val leaf = raw.replace('\\', '/').substringAfterLast('/').trim()
    val clean = leaf.filter { ch -> ch >= ' ' && ch != '\u007F' && ch != '/' && ch != '\\' && ch != '\u0000' }
    return if (clean.isEmpty() || clean == "." || clean == "..") "upload.bin" else clean
}

private fun queryDocumentMetaBlocking(context: Context, uri: Uri): Pair<String, Long> {
    var rawName: String? = null
    var total = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) {
                    rawName = c.getString(ni)
                }
                if (si >= 0 && !c.isNull(si)) {
                    total = c.getLong(si)
                }
            }
        }
    }
    return sanitizeUploadDisplayName(rawName) to total
}

private fun deleteSafDocumentQuietly(context: Context, uri: Uri) {
    runCatching {
        val deleted = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        if (!deleted) {
            context.contentResolver.delete(uri, null, null)
        }
    }
}

fun AppViewModel.deletePartialSafUri(context: Context, uri: Uri) {
    val appCtx = context.applicationContext
    viewModelScope.launch(Dispatchers.IO + NonCancellable) {
        deleteSafDocumentQuietly(appCtx, uri)
    }
}

fun AppViewModel.startSftpUploadFromUri(
    context: Context,
    sessionId: String,
    remoteDir: String,
    uri: Uri
) {
    val appCtx = context.applicationContext
    sftpTransferJobs[sessionId]?.cancel()
    _sftpTransfers.update { it - sessionId }
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        val thisJob = coroutineContext[Job]
        var fileName = "upload.bin"
        try {
            withContext(Dispatchers.IO) {
                val (safeName, totalBytes) = queryDocumentMetaBlocking(appCtx, uri)
                fileName = safeName
                val remotePath = "${remoteDir.trimEnd('/')}/$safeName"
                val stream = appCtx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件")
                stream.use { input ->
                    container.sftpService.uploadStream(sessionId, remotePath, input, totalBytes) { p ->
                        if (sftpTransferJobs[sessionId] === thisJob) {
                            _sftpTransfers.update { it + (sessionId to p) }
                        }
                    }
                }
            }
            _sftpMutatedTick.update { it + 1 }
            showToast("上传完成: $fileName")
        } catch (_: CancellationException) {
            showToast("已取消上传: $fileName")
        } catch (e: Throwable) {
            showToast("上传失败: ${e.message}")
        } finally {
            if (thisJob != null && sftpTransferJobs.remove(sessionId, thisJob)) {
                _sftpTransfers.update { it - sessionId }
            }
        }
    }
    sftpTransferJobs[sessionId] = job
    job.start()
}

fun AppViewModel.startSftpUpload(
    context: Context,
    sessionId: String,
    remotePath: String,
    fileName: String,
    uri: Uri,
    totalBytes: Long
) {
    val appCtx = context.applicationContext
    val safeName = sanitizeUploadDisplayName(fileName)
    val safeRemotePath = "${remotePath.substringBeforeLast('/', "").trimEnd('/')}/$safeName"
    sftpTransferJobs[sessionId]?.cancel()
    _sftpTransfers.update { it - sessionId }
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        val thisJob = coroutineContext[Job]
        try {
            withContext(Dispatchers.IO) {
                val stream = appCtx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件")
                stream.use { input ->
                    container.sftpService.uploadStream(sessionId, safeRemotePath, input, totalBytes) { p ->
                        if (sftpTransferJobs[sessionId] === thisJob) {
                            _sftpTransfers.update { it + (sessionId to p) }
                        }
                    }
                }
            }
            _sftpMutatedTick.update { it + 1 }
            showToast("上传完成: $safeName")
        } catch (_: CancellationException) {
            showToast("已取消上传: $safeName")
        } catch (e: Throwable) {
            showToast("上传失败: ${e.message}")
        } finally {
            if (thisJob != null && sftpTransferJobs.remove(sessionId, thisJob)) {
                _sftpTransfers.update { it - sessionId }
            }
        }
    }
    sftpTransferJobs[sessionId] = job
    job.start()
}

fun AppViewModel.startSftpDownload(
    context: Context,
    sessionId: String,
    remotePath: String,
    fileName: String,
    uri: Uri
) {
    val appCtx = context.applicationContext
    sftpTransferJobs[sessionId]?.cancel()
    _sftpTransfers.update { it - sessionId }
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        val thisJob = coroutineContext[Job]
        try {
            withContext(Dispatchers.IO) {
                val stream = appCtx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法写入目标文件")
                stream.use { output ->
                    container.sftpService.downloadStream(sessionId, remotePath, output) { p ->
                        if (sftpTransferJobs[sessionId] === thisJob) {
                            _sftpTransfers.update { it + (sessionId to p) }
                        }
                    }
                }
            }
            showToast("下载完成: $fileName")
        } catch (_: CancellationException) {
            withContext(Dispatchers.IO + NonCancellable) {
                deleteSafDocumentQuietly(appCtx, uri)
            }
            showToast("已取消下载: $fileName")
        } catch (e: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) {
                deleteSafDocumentQuietly(appCtx, uri)
            }
            showToast("下载失败: ${e.message}")
        } finally {
            if (thisJob != null && sftpTransferJobs.remove(sessionId, thisJob)) {
                _sftpTransfers.update { it - sessionId }
            }
        }
    }
    sftpTransferJobs[sessionId] = job
    job.start()
}

fun AppViewModel.cancelSftpTransfer(sessionId: String) {
    sftpTransferJobs[sessionId]?.cancel()
}

fun AppViewModel.runSftpOp(
    successMsg: String? = null,
    errorPrefix: String = "操作失败",
    reloadOnSuccess: Boolean = true,
    onDone: ((Result<Unit>) -> Unit)? = null,
    block: suspend () -> Unit
) {
    viewModelScope.launch {
        val res = runCatching { block() }
        res.onSuccess {
            if (reloadOnSuccess) _sftpMutatedTick.update { it + 1 }
            if (successMsg != null) showToast(successMsg)
        }.onFailure { err ->
            if (err is CancellationException) throw err
            showToast("$errorPrefix: ${err.message}")
        }
        onDone?.invoke(res)
    }
}
