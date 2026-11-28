package com.gravityssh.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.AppContainer
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.model.TransferProgress
import com.gravityssh.app.model.UpdateCheckResult
import com.gravityssh.app.model.VaultState
import com.gravityssh.app.vault.KnownHostEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class AppViewModel(val container: AppContainer) : ViewModel() {

    val vaultState: StateFlow<VaultState> = container.vaultRepository.state
    val sessionTabs: StateFlow<List<SessionTab>> = container.sshSessionManager.tabs
    val activeSessionId: StateFlow<String?> = container.sshSessionManager.activeSessionId
    val knownHosts: StateFlow<List<KnownHostEntry>> = container.knownHostsManager.knownHosts
    val knownHostsError: StateFlow<String?> = container.knownHostsManager.lastError
    val syncUiState: StateFlow<SyncUiState> = container.syncService.uiState

    private val _toastEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _updateResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateResult: StateFlow<UpdateCheckResult?> = _updateResult.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    internal val _sftpTransfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val sftpTransfers: StateFlow<Map<String, TransferProgress>> = _sftpTransfers.asStateFlow()

    internal val _sftpMutatedTick = MutableStateFlow(0)
    val sftpMutatedTick: StateFlow<Int> = _sftpMutatedTick.asStateFlow()

    internal val sftpTransferJobs = ConcurrentHashMap<String, Job>()
    internal val pendingSftpDownloadTargets = ConcurrentHashMap<String, Pair<String, String>>()

    internal val _syncActionBusy = MutableStateFlow(false)
    val syncActionBusy: StateFlow<Boolean> = _syncActionBusy.asStateFlow()

    internal val _lastSyncSummary = MutableStateFlow<SyncImportSummary?>(null)
    val lastSyncSummary: StateFlow<SyncImportSummary?> = _lastSyncSummary.asStateFlow()

    private var startupUpdateChecked = false

    init {
        viewModelScope.launch {
            vaultState
                .map { (it.lockState.initialized && !it.lockState.locked) to it.data.settings.checkUpdatesOnStartup }
                .distinctUntilChanged()
                .collect { (unlocked, checkEnabled) ->
                    if (unlocked && !startupUpdateChecked) {
                        startupUpdateChecked = true
                        if (checkEnabled) checkUpdates(silent = true)
                    }
                }
        }
        viewModelScope.launch {
            knownHostsError
                .filterNotNull()
                .distinctUntilChanged()
                .collect { err -> showToast(err) }
        }
        viewModelScope.launch {
            vaultState
                .map { it.lockState }
                .distinctUntilChanged()
                .collect { vState ->
                    if (vState.locked) {
                        cancelAllSftpOnLock()
                        _syncActionBusy.value = false
                        _lastSyncSummary.value = null
                        container.syncService.onVaultLocked()
                    } else if (vState.initialized) {
                        container.syncService.refreshOverview()
                    }
                }
        }
    }

    fun showToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun dismissKnownHostsError() {
        container.knownHostsManager.clearLastError()
    }

    fun setPendingSftpDownloadTarget(sessionId: String, remotePath: String, fileName: String) {
        pendingSftpDownloadTargets[sessionId] = remotePath to fileName
    }

    fun consumePendingSftpDownloadTarget(sessionId: String): Pair<String, String>? =
        pendingSftpDownloadTargets.remove(sessionId)

    fun lockVault() {
        container.vaultRepository.lockNow()
    }

    fun clearHostKnownKey(host: String, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { container.knownHostsManager.clearHost(host, port) }
                .onFailure { showToast("清除主机指纹失败: ${it.message}") }
                .getOrNull() ?: return@launch
            showToast(if (removed) "已清除 $host:$port 主机指纹缓存" else "未找到 $host:$port 缓存")
        }
    }

    fun clearKnownHostByKey(hostPort: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.knownHostsManager.clearHostByKey(hostPort) }
                .onSuccess { showToast("已删除主机指纹 $hostPort") }
                .onFailure { showToast("删除主机指纹失败: ${it.message}") }
        }
    }

    fun clearAllKnownHosts() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.knownHostsManager.clearAll() }
                .onSuccess { n -> showToast("已清除 $n 条 SSH 主机指纹缓存") }
                .onFailure { showToast("清除主机指纹失败: ${it.message}") }
        }
    }

    fun openServerSession(server: ServerConfig): String =
        container.sshSessionManager.openSession(server)

    fun checkUpdates(silent: Boolean = false) {
        if (_checkingUpdate.value) return
        viewModelScope.launch {
            _checkingUpdate.value = true
            runCatching { container.updateChecker.checkLatest() }
                .onSuccess { res ->
                    _updateResult.value = res
                    if (!silent) {
                        showToast(if (res.hasUpdate) "发现新版本 v${res.latestVersion}" else "当前已是最新版本 v${res.currentVersion}")
                    }
                }
                .onFailure { if (!silent) showToast("检查更新失败: ${it.message}") }
            _checkingUpdate.value = false
        }
    }

    private fun cancelAllSftpOnLock() {
        val activeJobs = sftpTransferJobs.values.toList()
        sftpTransferJobs.clear()
        activeJobs.forEach { it.cancel() }
        pendingSftpDownloadTargets.clear()
        _sftpTransfers.value = emptyMap()
    }

    override fun onCleared() {
        cancelAllSftpOnLock()
        container.syncService.stopForegroundInboxWatch()
        container.syncService.cancelActiveFlow()
        super.onCleared()
    }
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}
