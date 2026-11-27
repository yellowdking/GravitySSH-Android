package com.gravityssh.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.AppContainer
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.model.SyncSendPollResult
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.model.TransferProgress
import com.gravityssh.app.model.UpdateCheckResult
import com.gravityssh.app.model.VaultState
import com.gravityssh.app.vault.KnownHostEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SyncSendUiState(
    val session: SyncSendSession? = null,
    val pollResult: SyncSendPollResult? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val consumedSuccess: Boolean = false,
    val remainingSec: Long = 0L,
    val consecutivePollErrors: Int = 0,
    val lastPollError: String? = null
)

class AppViewModel(val container: AppContainer) : ViewModel() {

    val vaultState: StateFlow<VaultState> = container.vaultRepository.state
    val sessionTabs: StateFlow<List<SessionTab>> = container.sshSessionManager.tabs
    val activeSessionId: StateFlow<String?> = container.sshSessionManager.activeSessionId
    val knownHosts: StateFlow<List<KnownHostEntry>> = container.knownHostsManager.knownHosts

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _updateResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateResult: StateFlow<UpdateCheckResult?> = _updateResult.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    internal val _sftpTransfer = MutableStateFlow<TransferProgress?>(null)
    val sftpTransfer: StateFlow<TransferProgress?> = _sftpTransfer.asStateFlow()

    internal val _sftpMutatedTick = MutableStateFlow(0)
    val sftpMutatedTick: StateFlow<Int> = _sftpMutatedTick.asStateFlow()

    internal var sftpTransferJob: Job? = null

    internal val _syncSendState = MutableStateFlow(SyncSendUiState())
    val syncSendState: StateFlow<SyncSendUiState> = _syncSendState.asStateFlow()

    internal var syncCountdownJob: Job? = null
    internal var syncPollJob: Job? = null

    private var startupUpdateChecked = false

    init {
        viewModelScope.launch {
            vaultState
                .map { !it.lockState.locked to it.data.settings.checkUpdatesOnStartup }
                .distinctUntilChanged()
                .collect { (unlocked, checkEnabled) ->
                    if (unlocked && !startupUpdateChecked) {
                        startupUpdateChecked = true
                        if (checkEnabled) checkUpdates(silent = true)
                    }
                }
        }
    }

    fun showToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun clearHostKnownKey(host: String, port: Int): Boolean {
        val removed = container.knownHostsManager.clearHost(host, port)
        showToast(if (removed) "已清除 $host:$port 主机指纹缓存" else "未找到 $host:$port 缓存")
        return removed
    }

    fun clearKnownHostByKey(hostPort: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.knownHostsManager.clearHostByKey(hostPort)
            showToast("已删除主机指纹 $hostPort")
        }
    }

    fun clearAllKnownHosts() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = container.knownHostsManager.clearAll()
            showToast("已清除 $n 条 SSH 主机指纹缓存")
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

    override fun onCleared() {
        sftpTransferJob?.cancel()
        syncCountdownJob?.cancel()
        syncPollJob?.cancel()
        val pendingId = _syncSendState.value.session?.sessionId
        if (!pendingId.isNullOrBlank()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                runCatching { container.syncService.cancelSend(pendingId) }
            }
        }
        super.onCleared()
    }
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}
