package com.gravityssh.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.KeyDeployResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncSendPollState
import com.gravityssh.app.ssh.SshKeyGenerator
import com.gravityssh.app.sync.SyncConfig
import com.gravityssh.app.terminal.TouchCursorMode
import com.gravityssh.app.terminal.TouchSensitivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

suspend fun AppViewModel.deployKey(key: SSHKeyRecord, targets: List<ServerConfig>): List<KeyDeployResult> =
    vaultState.value.data.let { container.keyDeployer.deployToServers(key, targets, it.servers, it.sshKeys) }

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

fun AppViewModel.updateSettings(transform: (AppSettings) -> AppSettings) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.vaultRepository.updateSettings(transform) }
            .onFailure { showToast("更新设置失败: ${it.message}") }
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

fun AppViewModel.startSftpUpload(
    context: Context,
    sessionId: String,
    remotePath: String,
    fileName: String,
    uri: Uri,
    totalBytes: Long
) {
    val appCtx = context.applicationContext
    sftpTransferJob?.cancel()
    sftpTransferJob = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val stream = appCtx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件")
                stream.use { input ->
                    container.sftpService.uploadStream(sessionId, remotePath, input, totalBytes) { p ->
                        _sftpTransfer.value = p
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
            _sftpTransfer.value = null
            sftpTransferJob = null
        }
    }
}

fun AppViewModel.startSftpDownload(
    context: Context,
    sessionId: String,
    remotePath: String,
    fileName: String,
    uri: Uri
) {
    val appCtx = context.applicationContext
    sftpTransferJob?.cancel()
    sftpTransferJob = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val stream = appCtx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法写入目标文件")
                stream.use { output ->
                    container.sftpService.downloadStream(sessionId, remotePath, output) { p ->
                        _sftpTransfer.value = p
                    }
                }
            }
            showToast("下载完成: $fileName")
        } catch (_: CancellationException) {
            showToast("已取消下载: $fileName")
        } catch (e: Throwable) {
            showToast("下载失败: ${e.message}")
        } finally {
            _sftpTransfer.value = null
            sftpTransferJob = null
        }
    }
}

fun AppViewModel.cancelSftpTransfer() {
    sftpTransferJob?.cancel()
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

fun AppViewModel.startSyncSend() {
    if (_syncSendState.value.busy) return
    syncCountdownJob?.cancel()
    syncPollJob?.cancel()
    _syncSendState.value = SyncSendUiState(busy = true)
    viewModelScope.launch {
        runCatching { container.syncService.startSend() }
            .onSuccess { sess ->
                _syncSendState.value = SyncSendUiState(
                    session = sess,
                    remainingSec = ((sess.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                )
                launchSyncSendLoops(sess)
            }
            .onFailure { err ->
                _syncSendState.value = SyncSendUiState(error = err.message ?: "初始化同步通道失败")
            }
    }
}

private fun AppViewModel.launchSyncSendLoops(sess: SyncSendSession) {
    syncCountdownJob?.cancel()
    syncCountdownJob = viewModelScope.launch {
        while (true) {
            val left = ((sess.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            if (left <= 0L) {
                syncPollJob?.cancel()
                _syncSendState.update {
                    if (it.session?.sessionId == sess.sessionId) {
                        SyncSendUiState(error = "同步码已过期，请重新生成")
                    } else it
                }
                break
            }
            _syncSendState.update {
                if (it.session?.sessionId == sess.sessionId) it.copy(remainingSec = left) else it
            }
            delay(1000L)
        }
    }
    startSyncSendPolling(sess.sessionId)
}

private fun AppViewModel.startSyncSendPolling(sessionId: String) {
    syncPollJob?.cancel()
    syncPollJob = viewModelScope.launch {
        while (true) {
            val cur = _syncSendState.value
            if (cur.session?.sessionId != sessionId || cur.pollResult?.state == SyncSendPollState.REQUESTED) {
                break
            }
            try {
                val res = container.syncService.pollSend(sessionId)
                when (res.state) {
                    SyncSendPollState.WAITING, SyncSendPollState.APPROVED -> {
                        _syncSendState.update {
                            if (it.session?.sessionId == sessionId) {
                                it.copy(pollResult = res, consecutivePollErrors = 0, lastPollError = null)
                            } else it
                        }
                    }
                    SyncSendPollState.REQUESTED -> {
                        _syncSendState.update {
                            if (it.session?.sessionId == sessionId) {
                                it.copy(pollResult = res, consecutivePollErrors = 0, lastPollError = null)
                            } else it
                        }
                        break
                    }
                    SyncSendPollState.CONSUMED -> {
                        syncCountdownJob?.cancel()
                        _syncSendState.value = SyncSendUiState(consumedSuccess = true)
                        showToast("对方已成功接收配置")
                        break
                    }
                    SyncSendPollState.REJECTED -> {
                        syncCountdownJob?.cancel()
                        _syncSendState.value = SyncSendUiState(error = "已拒绝配对请求")
                        break
                    }
                    SyncSendPollState.EXPIRED -> {
                        syncCountdownJob?.cancel()
                        _syncSendState.value = SyncSendUiState(error = "同步码已过期或已失效，请重新生成")
                        break
                    }
                    SyncSendPollState.ERROR -> {
                        syncCountdownJob?.cancel()
                        _syncSendState.value = SyncSendUiState(error = res.errorMessage ?: "验证请求异常，已终止同步")
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                syncCountdownJob?.cancel()
                _syncSendState.value = SyncSendUiState(error = e.message ?: "保险库已锁定")
                break
            } catch (e: Exception) {
                _syncSendState.update {
                    if (it.session?.sessionId == sessionId) {
                        it.copy(
                            consecutivePollErrors = it.consecutivePollErrors + 1,
                            lastPollError = e.message ?: "网络异常"
                        )
                    } else it
                }
            }
            delay(SyncConfig.PAIR_POLL_INTERVAL_MS)
        }
    }
}

fun AppViewModel.approveSyncSend(sessionId: String, requestId: String, receiverCode: String) {
    if (_syncSendState.value.busy) return
    _syncSendState.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
        runCatching {
            container.syncService.approveSend(sessionId, requestId, receiverCode)
        }.onSuccess {
            _syncSendState.update { cur ->
                cur.copy(
                    busy = false,
                    pollResult = cur.pollResult?.copy(state = SyncSendPollState.APPROVED),
                    consecutivePollErrors = 0,
                    lastPollError = null
                )
            }
            startSyncSendPolling(sessionId)
        }.onFailure { err ->
            _syncSendState.update { it.copy(busy = false, error = err.message ?: "同意发送失败") }
        }
    }
}

fun AppViewModel.cancelSyncSend(sessionIdOrCode: String) {
    syncCountdownJob?.cancel()
    syncPollJob?.cancel()
    _syncSendState.value = SyncSendUiState()
    viewModelScope.launch {
        runCatching { container.syncService.cancelSend(sessionIdOrCode) }
            .onSuccess { showToast("已撤销同步码") }
            .onFailure { showToast("撤销同步码失败: ${it.message}") }
    }
}

fun AppViewModel.rejectSyncSend(sessionId: String, requestId: String) {
    syncCountdownJob?.cancel()
    syncPollJob?.cancel()
    _syncSendState.value = SyncSendUiState()
    viewModelScope.launch {
        runCatching { container.syncService.rejectSend(sessionId, requestId) }
            .onSuccess { showToast("已拒绝配对请求并撤销同步") }
            .onFailure { showToast("拒绝配对失败: ${it.message}") }
    }
}

fun AppViewModel.cleanupAbandonedSyncOnLeave() {
    val cur = _syncSendState.value
    val sess = cur.session
    if (sess != null && cur.pollResult?.state != SyncSendPollState.APPROVED) {
        val sid = sess.sessionId.ifBlank { sess.code }
        syncCountdownJob?.cancel()
        syncPollJob?.cancel()
        _syncSendState.value = SyncSendUiState()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.syncService.cancelSend(sid) }
        }
    } else if (sess == null && cur.consumedSuccess) {
        _syncSendState.value = SyncSendUiState()
    }
}
