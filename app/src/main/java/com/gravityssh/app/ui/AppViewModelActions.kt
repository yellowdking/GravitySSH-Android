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
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncReceivePollState
import com.gravityssh.app.model.SyncReceiveWaitSession
import com.gravityssh.app.model.SyncSendPollState
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.ssh.SshKeyGenerator
import com.gravityssh.app.sync.SyncConfig
import com.gravityssh.app.terminal.TouchCursorMode
import com.gravityssh.app.terminal.TouchSensitivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

private const val SYNC_OFFLINE_EXPIRE_GRACE_MS = 150_000L

fun AppViewModel.startSyncSend() {
    if (_syncSendState.value.busy) return
    val gen = syncSendGeneration.incrementAndGet()
    syncStartJob?.cancel()
    syncApproveJob?.cancel()
    syncCountdownJob?.cancel()
    syncPollJob?.cancel()
    _syncSendState.value = SyncSendUiState(busy = true)
    syncStartJob = viewModelScope.launch {
        var createdSess: SyncSendSession? = null
        try {
            val sess = container.syncService.startSend()
            createdSess = sess
            if (syncSendGeneration.get() != gen) {
                val sid = sess.sessionId.ifBlank { sess.code }
                withContext(Dispatchers.IO + NonCancellable) {
                    runCatching { container.syncService.cancelSend(sid) }
                }
                return@launch
            }
            _syncSendState.value = SyncSendUiState(
                session = sess,
                remainingSec = ((sess.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            )
            launchSyncSendLoops(sess)
        } catch (e: CancellationException) {
            createdSess?.let { sess ->
                val sid = sess.sessionId.ifBlank { sess.code }
                withContext(Dispatchers.IO + NonCancellable) {
                    runCatching { container.syncService.cancelSend(sid) }
                }
            }
            throw e
        } catch (err: Throwable) {
            if (syncSendGeneration.get() == gen) {
                _syncSendState.value = SyncSendUiState(error = err.message ?: "初始化同步通道失败")
            }
        }
    }
}

private fun AppViewModel.launchSyncSendLoops(sess: SyncSendSession) {
    syncCountdownJob?.cancel()
    syncCountdownJob = viewModelScope.launch {
        while (true) {
            val left = ((sess.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            _syncSendState.update {
                if (it.session?.sessionId == sess.sessionId) it.copy(remainingSec = left) else it
            }
            // Do not terminate polling on local countdown reaching 0 (allows ±120s client/relay clock skew).
            // Authoritative relay gone/404 response terminates polling in startSyncSendPolling.
            if (left <= 0L) break
            delay(1000L)
        }
    }
    startSyncSendPolling(sess.sessionId, sess.expiresAtEpochMs)
}

private fun AppViewModel.startSyncSendPolling(sessionId: String, expiresAtEpochMs: Long) {
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
                        var matched = false
                        _syncSendState.update { state ->
                            if (state.session?.sessionId == sessionId) {
                                matched = true
                                SyncSendUiState(consumedSuccess = true)
                            } else state
                        }
                        if (matched) {
                            syncCountdownJob?.cancel()
                            showToast("对方已成功接收配置")
                        }
                        break
                    }
                    SyncSendPollState.REJECTED -> {
                        var matched = false
                        _syncSendState.update { state ->
                            if (state.session?.sessionId == sessionId) {
                                matched = true
                                SyncSendUiState(error = "已拒绝配对请求")
                            } else state
                        }
                        if (matched) syncCountdownJob?.cancel()
                        break
                    }
                    SyncSendPollState.EXPIRED -> {
                        var matched = false
                        _syncSendState.update { state ->
                            if (state.session?.sessionId == sessionId) {
                                matched = true
                                SyncSendUiState(error = "同步码已过期或已失效，请重新生成")
                            } else state
                        }
                        if (matched) syncCountdownJob?.cancel()
                        break
                    }
                    SyncSendPollState.ERROR -> {
                        var matched = false
                        _syncSendState.update { state ->
                            if (state.session?.sessionId == sessionId) {
                                matched = true
                                SyncSendUiState(error = res.errorMessage ?: "验证请求异常，已终止同步")
                            } else state
                        }
                        if (matched) syncCountdownJob?.cancel()
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                var matched = false
                _syncSendState.update { state ->
                    if (state.session?.sessionId == sessionId) {
                        matched = true
                        SyncSendUiState(error = e.message ?: "保险库已锁定")
                    } else state
                }
                if (matched) syncCountdownJob?.cancel()
                break
            } catch (e: Exception) {
                var nextErrors = 0
                _syncSendState.update { state ->
                    if (state.session?.sessionId == sessionId) {
                        nextErrors = state.consecutivePollErrors + 1
                        state.copy(
                            consecutivePollErrors = nextErrors,
                            lastPollError = e.message ?: "网络异常"
                        )
                    } else state
                }
                if (nextErrors >= 5 && System.currentTimeMillis() > expiresAtEpochMs + SYNC_OFFLINE_EXPIRE_GRACE_MS) {
                    var matched = false
                    _syncSendState.update { state ->
                        if (state.session?.sessionId == sessionId) {
                            matched = true
                            SyncSendUiState(error = "同步码已过期且网络不可达，请重新生成")
                        } else state
                    }
                    if (matched) syncCountdownJob?.cancel()
                    break
                }
            }
            delay(SyncConfig.PAIR_POLL_INTERVAL_MS)
        }
    }
}

fun AppViewModel.approveSyncSend(sessionId: String, requestId: String, receiverCode: String) {
    val initial = _syncSendState.value
    val sess = initial.session ?: return
    if (initial.busy || sess.sessionId != sessionId) return
    val expiresAtEpochMs = sess.expiresAtEpochMs
    _syncSendState.update {
        if (it.session?.sessionId == sessionId) it.copy(busy = true, error = null) else it
    }
    syncApproveJob?.cancel()
    syncApproveJob = viewModelScope.launch {
        try {
            container.syncService.approveSend(sessionId, requestId, receiverCode)
            var matched = false
            _syncSendState.update { cur ->
                if (cur.session?.sessionId == sessionId) {
                    matched = true
                    cur.copy(
                        busy = false,
                        pollResult = cur.pollResult?.copy(state = SyncSendPollState.APPROVED),
                        consecutivePollErrors = 0,
                        lastPollError = null
                    )
                } else cur
            }
            if (matched) {
                startSyncSendPolling(sessionId, expiresAtEpochMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (err: Throwable) {
            _syncSendState.update { cur ->
                if (cur.session?.sessionId == sessionId) {
                    cur.copy(busy = false, error = err.message ?: "同意发送失败")
                } else cur
            }
        }
    }
}

fun AppViewModel.cancelSyncSend(sessionIdOrCode: String) {
    syncSendGeneration.incrementAndGet()
    syncStartJob?.cancel()
    syncStartJob = null
    syncApproveJob?.cancel()
    syncApproveJob = null
    syncCountdownJob?.cancel()
    syncPollJob?.cancel()
    _syncSendState.value = SyncSendUiState()
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.syncService.cancelSend(sessionIdOrCode) }
            .onSuccess { showToast("已撤销同步码") }
            .onFailure { showToast("撤销同步码失败: ${it.message}") }
    }
}

fun AppViewModel.rejectSyncSend(sessionId: String, requestId: String) {
    syncSendGeneration.incrementAndGet()
    syncStartJob?.cancel()
    syncStartJob = null
    syncApproveJob?.cancel()
    syncApproveJob = null
    syncCountdownJob?.cancel()
    syncPollJob?.cancel()
    _syncSendState.value = SyncSendUiState()
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.syncService.rejectSend(sessionId, requestId) }
            .onSuccess { showToast("已拒绝配对请求并撤销同步") }
            .onFailure { showToast("拒绝配对失败: ${it.message}") }
    }
}

fun AppViewModel.updateSyncReceiveInputCode(code: String) {
    _syncReceiveState.update { it.copy(inputCode = code, error = null) }
}

fun AppViewModel.refreshSyncReceiverCode() {
    val cur = _syncReceiveState.value
    if (cur.busy || cur.waitSession != null) return
    _syncReceiveState.update {
        it.copy(receiverCode = container.syncService.generateReceiverCode())
    }
}

fun AppViewModel.startSyncReceive(codeOrUri: String) {
    val cur = _syncReceiveState.value
    if (codeOrUri.isBlank() || cur.busy || cur.waitSession != null) return
    val receiverCode = cur.receiverCode.ifBlank { container.syncService.generateReceiverCode() }
    val gen = syncReceiveGeneration.incrementAndGet()
    syncReceiveStartJob?.cancel()
    syncReceiveCountdownJob?.cancel()
    syncReceivePollJob?.cancel()
    _syncReceiveState.update {
        it.copy(
            receiverCode = receiverCode,
            busy = true,
            error = null,
            summary = null,
            consecutivePollErrors = 0,
            lastPollError = null
        )
    }
    syncReceiveStartJob = viewModelScope.launch {
        var createdWait: SyncReceiveWaitSession? = null
        try {
            val wait = container.syncService.startReceiveRequest(codeOrUri, receiverCode)
            createdWait = wait
            if (syncReceiveGeneration.get() != gen) {
                container.syncService.cancelReceiveWait(wait.waitId)
                return@launch
            }
            val oldPreviewId = _syncReceiveState.value.preview?.previewId
            if (oldPreviewId != null) {
                runCatching { container.syncService.discardPreview(oldPreviewId) }
            }
            _syncReceiveState.update {
                it.copy(
                    busy = false,
                    error = null,
                    preview = null,
                    waitSession = wait,
                    waitRemainingSec = ((wait.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L),
                    consecutivePollErrors = 0,
                    lastPollError = null
                )
            }
            launchSyncReceiveLoops(wait)
        } catch (e: CancellationException) {
            createdWait?.let { runCatching { container.syncService.cancelReceiveWait(it.waitId) } }
            throw e
        } catch (err: Throwable) {
            if (syncReceiveGeneration.get() == gen) {
                _syncReceiveState.update {
                    it.copy(
                        busy = false,
                        error = err.message ?: "发起验证失败，请确认同步码有效"
                    )
                }
            }
        }
    }
}

private fun AppViewModel.launchSyncReceiveLoops(wait: SyncReceiveWaitSession) {
    syncReceiveCountdownJob?.cancel()
    syncReceiveCountdownJob = viewModelScope.launch {
        while (true) {
            val left = ((wait.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            _syncReceiveState.update { state ->
                if (state.waitSession?.waitId == wait.waitId) state.copy(waitRemainingSec = left) else state
            }
            // Do not halt polling on local clock reaching 0 (tolerates ±120s client/relay clock skew).
            if (left <= 0L) break
            delay(1000L)
        }
    }
    syncReceivePollJob?.cancel()
    syncReceivePollJob = viewModelScope.launch {
        while (true) {
            if (_syncReceiveState.value.waitSession?.waitId != wait.waitId) break
            try {
                val res = container.syncService.pollReceiveStatus(wait.waitId)
                when (res.state) {
                    SyncReceivePollState.PENDING -> {
                        _syncReceiveState.update { state ->
                            if (state.waitSession?.waitId == wait.waitId) {
                                state.copy(consecutivePollErrors = 0, lastPollError = null)
                            } else state
                        }
                    }
                    SyncReceivePollState.APPROVED -> {
                        var matched = false
                        _syncReceiveState.update { state ->
                            if (state.waitSession?.waitId == wait.waitId) {
                                matched = true
                                state.copy(
                                    waitSession = null,
                                    waitRemainingSec = 0L,
                                    preview = res.preview,
                                    consecutivePollErrors = 0,
                                    lastPollError = null,
                                    error = null
                                )
                            } else state
                        }
                        if (matched) {
                            syncReceiveCountdownJob?.cancel()
                        } else {
                            res.preview?.previewId?.let { pid ->
                                runCatching { container.syncService.discardPreview(pid) }
                            }
                        }
                        break
                    }
                    SyncReceivePollState.REJECTED -> {
                        var matched = false
                        _syncReceiveState.update { state ->
                            if (state.waitSession?.waitId == wait.waitId) {
                                matched = true
                                state.copy(
                                    waitSession = null,
                                    waitRemainingSec = 0L,
                                    consecutivePollErrors = 0,
                                    lastPollError = null,
                                    receiverCode = container.syncService.generateReceiverCode(),
                                    error = "发送端已拒绝此次同步请求"
                                )
                            } else state
                        }
                        if (matched) syncReceiveCountdownJob?.cancel()
                        break
                    }
                    SyncReceivePollState.EXPIRED -> {
                        var matched = false
                        _syncReceiveState.update { state ->
                            if (state.waitSession?.waitId == wait.waitId) {
                                matched = true
                                state.copy(
                                    waitSession = null,
                                    waitRemainingSec = 0L,
                                    consecutivePollErrors = 0,
                                    lastPollError = null,
                                    receiverCode = container.syncService.generateReceiverCode(),
                                    error = "同步请求已过期或被发送端撤销"
                                )
                            } else state
                        }
                        if (matched) syncReceiveCountdownJob?.cancel()
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                var matched = false
                _syncReceiveState.update { state ->
                    if (state.waitSession?.waitId == wait.waitId) {
                        matched = true
                        state.copy(
                            waitSession = null,
                            waitRemainingSec = 0L,
                            error = e.message ?: "解密或校验失败"
                        )
                    } else state
                }
                if (matched) syncReceiveCountdownJob?.cancel()
                break
            } catch (e: IllegalStateException) {
                var matched = false
                _syncReceiveState.update { state ->
                    if (state.waitSession?.waitId == wait.waitId) {
                        matched = true
                        state.copy(
                            waitSession = null,
                            waitRemainingSec = 0L,
                            error = e.message ?: "中继状态异常"
                        )
                    } else state
                }
                if (matched) syncReceiveCountdownJob?.cancel()
                break
            } catch (e: Exception) {
                var nextErrors = 0
                _syncReceiveState.update { state ->
                    if (state.waitSession?.waitId == wait.waitId) {
                        nextErrors = state.consecutivePollErrors + 1
                        state.copy(
                            consecutivePollErrors = nextErrors,
                            lastPollError = e.message ?: "网络异常"
                        )
                    } else state
                }
                if (nextErrors >= 5 && System.currentTimeMillis() > wait.expiresAtEpochMs + SYNC_OFFLINE_EXPIRE_GRACE_MS) {
                    container.syncService.cancelReceiveWait(wait.waitId)
                    var matched = false
                    _syncReceiveState.update { state ->
                        if (state.waitSession?.waitId == wait.waitId) {
                            matched = true
                            state.copy(
                                waitSession = null,
                                waitRemainingSec = 0L,
                                consecutivePollErrors = 0,
                                lastPollError = null,
                                receiverCode = container.syncService.generateReceiverCode(),
                                error = "等待发送端确认已超时且网络不可达，请重试"
                            )
                        } else state
                    }
                    if (matched) syncReceiveCountdownJob?.cancel()
                    break
                }
            }
            delay(SyncConfig.PAIR_POLL_INTERVAL_MS)
        }
    }
}

fun AppViewModel.cancelSyncReceiveWait() {
    val waitId = _syncReceiveState.value.waitSession?.waitId
    syncReceiveGeneration.incrementAndGet()
    syncReceiveStartJob?.cancel()
    syncReceiveStartJob = null
    syncReceiveCountdownJob?.cancel()
    syncReceiveCountdownJob = null
    syncReceivePollJob?.cancel()
    syncReceivePollJob = null
    _syncReceiveState.update {
        it.copy(
            busy = false,
            waitSession = null,
            waitRemainingSec = 0L,
            consecutivePollErrors = 0,
            lastPollError = null,
            receiverCode = container.syncService.generateReceiverCode()
        )
    }
    if (waitId != null) {
        runCatching { container.syncService.cancelReceiveWait(waitId) }
    }
}

fun AppViewModel.applySyncReceive(mode: SyncImportMode) {
    val cur = _syncReceiveState.value
    val p = cur.preview ?: return
    if (cur.busy) return
    _syncReceiveState.update {
        if (it.preview?.previewId == p.previewId) it.copy(busy = true, error = null) else it
    }
    syncReceiveApplyJob?.cancel()
    syncReceiveApplyJob = viewModelScope.launch {
        try {
            val summary = withContext(Dispatchers.IO) {
                container.syncService.applyReceive(p.previewId, mode)
            }
            _syncReceiveState.update { state ->
                if (state.preview?.previewId == p.previewId) {
                    state.copy(
                        busy = false,
                        summary = summary,
                        preview = null,
                        inputCode = "",
                        receiverCode = container.syncService.generateReceiverCode(),
                        error = null
                    )
                } else state
            }
            showToast("同步导入完成")
        } catch (e: CancellationException) {
            _syncReceiveState.update { state ->
                if (state.preview?.previewId == p.previewId) state.copy(busy = false) else state
            }
            throw e
        } catch (err: Throwable) {
            _syncReceiveState.update { state ->
                if (state.preview?.previewId == p.previewId) {
                    state.copy(busy = false, error = err.message ?: "导入失败")
                } else state
            }
        }
    }
}

fun AppViewModel.discardSyncReceivePreview() {
    val previewId = _syncReceiveState.value.preview?.previewId ?: return
    if (_syncReceiveState.value.busy) return
    runCatching { container.syncService.discardPreview(previewId) }
    _syncReceiveState.update {
        if (it.preview?.previewId == previewId) {
            it.copy(preview = null, error = null)
        } else it
    }
}

fun AppViewModel.cleanupAbandonedSyncOnLeave() {
    val cur = _syncSendState.value
    val sess = cur.session
    if (sess == null && cur.busy) {
        syncSendGeneration.incrementAndGet()
        syncStartJob?.cancel()
        syncStartJob = null
        syncCountdownJob?.cancel()
        syncPollJob?.cancel()
        _syncSendState.value = SyncSendUiState()
    } else if (sess != null && cur.pollResult?.state != SyncSendPollState.APPROVED) {
        val sid = sess.sessionId.ifBlank { sess.code }
        syncSendGeneration.incrementAndGet()
        syncStartJob?.cancel()
        syncStartJob = null
        syncApproveJob?.cancel()
        syncApproveJob = null
        syncCountdownJob?.cancel()
        syncPollJob?.cancel()
        _syncSendState.value = SyncSendUiState()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { container.syncService.cancelSend(sid) }
        }
    } else if (sess == null && cur.consumedSuccess) {
        _syncSendState.value = SyncSendUiState()
    }

    val recv = _syncReceiveState.value
    if (recv.waitSession != null || (recv.busy && recv.preview == null)) {
        cancelSyncReceiveWait()
    } else if (recv.preview == null && recv.summary != null) {
        _syncReceiveState.update { it.copy(summary = null) }
    }
}
