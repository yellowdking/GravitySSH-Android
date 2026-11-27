package com.gravityssh.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.AppContainer
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncReceiveWaitSession
import com.gravityssh.app.model.SyncSendPollResult
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.model.TransferProgress
import com.gravityssh.app.model.UpdateCheckResult
import com.gravityssh.app.model.VaultState
import com.gravityssh.app.vault.KnownHostEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.util.concurrent.atomic.AtomicLong

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

data class SyncReceiveUiState(
    val inputCode: String = "",
    val receiverCode: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val waitSession: SyncReceiveWaitSession? = null,
    val waitRemainingSec: Long = 0L,
    val consecutivePollErrors: Int = 0,
    val lastPollError: String? = null,
    val preview: SyncReceivePreview? = null,
    val summary: SyncImportSummary? = null
)

class AppViewModel(val container: AppContainer) : ViewModel() {

    val vaultState: StateFlow<VaultState> = container.vaultRepository.state
    val sessionTabs: StateFlow<List<SessionTab>> = container.sshSessionManager.tabs
    val activeSessionId: StateFlow<String?> = container.sshSessionManager.activeSessionId
    val knownHosts: StateFlow<List<KnownHostEntry>> = container.knownHostsManager.knownHosts
    val knownHostsError: StateFlow<String?> = container.knownHostsManager.lastError

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

    internal val _syncSendState = MutableStateFlow(SyncSendUiState())
    val syncSendState: StateFlow<SyncSendUiState> = _syncSendState.asStateFlow()

    internal var syncStartJob: Job? = null
    internal var syncApproveJob: Job? = null
    internal var syncCountdownJob: Job? = null
    internal var syncPollJob: Job? = null
    internal val syncSendGeneration = AtomicLong(0L)

    internal val _syncReceiveState = MutableStateFlow(
        SyncReceiveUiState(receiverCode = container.syncService.generateReceiverCode())
    )
    val syncReceiveState: StateFlow<SyncReceiveUiState> = _syncReceiveState.asStateFlow()

    internal var syncReceiveStartJob: Job? = null
    internal var syncReceiveApplyJob: Job? = null
    internal var syncReceiveCountdownJob: Job? = null
    internal var syncReceivePollJob: Job? = null
    internal val syncReceiveGeneration = AtomicLong(0L)

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
                .map { it.lockState.locked }
                .distinctUntilChanged()
                .collect { locked ->
                    if (locked) {
                        resetSyncUiOnLock()
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
        viewModelScope.launch(Dispatchers.IO) {
            container.vaultRepository.lockNow()
        }
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

    private fun resetSyncUiOnLock() {
        syncSendGeneration.incrementAndGet()
        syncStartJob?.cancel()
        syncStartJob = null
        syncApproveJob?.cancel()
        syncApproveJob = null
        syncCountdownJob?.cancel()
        syncCountdownJob = null
        syncPollJob?.cancel()
        syncPollJob = null
        _syncSendState.value = SyncSendUiState()

        syncReceiveGeneration.incrementAndGet()
        syncReceiveStartJob?.cancel()
        syncReceiveStartJob = null
        syncReceiveApplyJob?.cancel()
        syncReceiveApplyJob = null
        syncReceiveCountdownJob?.cancel()
        syncReceiveCountdownJob = null
        syncReceivePollJob?.cancel()
        syncReceivePollJob = null
        _syncReceiveState.value = SyncReceiveUiState(
            receiverCode = container.syncService.generateReceiverCode()
        )
    }

    override fun onCleared() {
        sftpTransferJobs.values.forEach { it.cancel() }
        sftpTransferJobs.clear()
        pendingSftpDownloadTargets.clear()

        syncSendGeneration.incrementAndGet()
        syncStartJob?.cancel()
        syncApproveJob?.cancel()
        syncCountdownJob?.cancel()
        syncPollJob?.cancel()
        val pendingId = _syncSendState.value.session?.sessionId
        if (!pendingId.isNullOrBlank()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                runCatching { container.syncService.cancelSend(pendingId) }
            }
        }

        syncReceiveGeneration.incrementAndGet()
        syncReceiveStartJob?.cancel()
        syncReceiveApplyJob?.cancel()
        syncReceiveCountdownJob?.cancel()
        syncReceivePollJob?.cancel()
        _syncReceiveState.value.waitSession?.waitId?.let { waitId ->
            runCatching { container.syncService.cancelReceiveWait(waitId) }
        }
        _syncReceiveState.value.preview?.previewId?.let { previewId ->
            runCatching { container.syncService.discardPreview(previewId) }
        }
        super.onCleared()
    }
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}
