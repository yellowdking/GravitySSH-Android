package com.gravityssh.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gravityssh.app.AppContainer
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.model.UpdateCheckResult
import com.gravityssh.app.model.VaultState
import com.gravityssh.app.vault.KnownHostEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(val container: AppContainer) : ViewModel() {

    val vaultState: StateFlow<VaultState> = container.vaultRepository.state
    val sessionTabs: StateFlow<List<SessionTab>> = container.sshSessionManager.tabs
    val activeSessionId: StateFlow<String?> = container.sshSessionManager.activeSessionId

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _updateResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateResult: StateFlow<UpdateCheckResult?> = _updateResult.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    private val _knownHosts = MutableStateFlow<List<KnownHostEntry>>(emptyList())
    val knownHosts: StateFlow<List<KnownHostEntry>> = _knownHosts.asStateFlow()

    init {
        refreshKnownHosts()
        viewModelScope.launch {
            val st = vaultState.value
            if (!st.lockState.locked && st.data.settings.checkUpdatesOnStartup) {
                checkUpdates(silent = true)
            }
        }
    }

    fun showToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun refreshKnownHosts() {
        _knownHosts.value = container.knownHostsManager.listKnownHosts()
    }

    fun clearHostKnownKey(host: String, port: Int): Boolean {
        val removed = container.knownHostsManager.clearHost(host, port)
        refreshKnownHosts()
        showToast(if (removed) "已清除 $host:$port 主机指纹缓存" else "未找到 $host:$port 缓存")
        return removed
    }

    fun clearKnownHostByKey(hostPort: String) {
        container.knownHostsManager.clearHostByKey(hostPort)
        refreshKnownHosts()
        showToast("已删除主机指纹 $hostPort")
    }

    fun clearAllKnownHosts() {
        val n = container.knownHostsManager.clearAll()
        refreshKnownHosts()
        showToast("已清除 $n 条 SSH 主机指纹缓存")
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
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}
