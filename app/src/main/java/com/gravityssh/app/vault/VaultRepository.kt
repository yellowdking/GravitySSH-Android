package com.gravityssh.app.vault

import android.util.Log
import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.PresetLoadResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.VaultData
import com.gravityssh.app.model.VaultLockState
import com.gravityssh.app.model.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class VaultRepository(
    vaultFile: File,
    crypto: VaultCrypto = VaultCrypto()
) {
    private val lock = Any()
    private val store = VaultPersistence(vaultFile, crypto)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onLockCallbacks = CopyOnWriteArrayList<() -> Unit>()

    private var activePassword: String? = null
    private var cachedPasswordKey: CachedPasswordKey? = null
    private var currentData: VaultData = VaultData()
    private var pendingDirty: Boolean = false
    private var debouncedFlushJob: Job? = null

    private val _state = MutableStateFlow(VaultState(lockState = VaultLockState(locked = true)))
    val state: StateFlow<VaultState> = _state.asStateFlow()

    fun addOnLockListener(listener: () -> Unit) {
        onLockCallbacks.addIfAbsent(listener)
    }

    fun open() = synchronized(lock) {
        try {
            val res = store.openInitial()
            clearSecretsLocked()
            currentData = res.data
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(
                locked = res.locked,
                hasPassword = res.hasPassword,
                biometric = res.biometric,
                startupError = null
            )
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed to open vault", e) }
            clearSecretsLocked()
            currentData = VaultData()
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(
                locked = true,
                hasPassword = false,
                biometric = false,
                startupError = "保险库打开失败（文件损坏或系统密钥不可用）：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    fun resetCorruptedVault(): Result<Unit> = synchronized(lock) {
        runCatching {
            store.backupCorruptedFile()
            val res = store.openInitial()
            clearSecretsLocked()
            currentData = res.data
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(
                locked = res.locked,
                hasPassword = res.hasPassword,
                biometric = res.biometric,
                startupError = null
            )
        }
    }

    fun unlock(password: String): Result<Unit> = synchronized(lock) {
        runCatching {
            val (data, newKey) = store.loadWithKeyCache(password)
            val oldKey = cachedPasswordKey
            currentData = data
            activePassword = password
            cachedPasswordKey = newKey
            oldKey?.clear()
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(locked = false, hasPassword = true, startupError = null)
        }
    }

    fun unlockWithBiometric(): Result<Unit> = synchronized(lock) {
        runCatching {
            val (data, hasPw, newKey) = store.loadForBiometric(activePassword)
            val oldKey = cachedPasswordKey
            currentData = data
            cachedPasswordKey = newKey
            oldKey?.clear()
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(locked = false, hasPassword = hasPw, startupError = null)
        }
    }

    fun lockNow() {
        val didLock = synchronized(lock) {
            val cur = _state.value.lockState
            if (!cur.hasPassword && !cur.biometricEnabled) return@synchronized false
            if (!cur.locked && pendingDirty) {
                runCatching { store.save(currentData, activePassword, cachedPasswordKey) }
            }
            cancelDebouncedFlushLocked()
            pendingDirty = false
            clearSecretsLocked()
            currentData = VaultData()
            emitStateLocked(
                locked = true,
                hasPassword = cur.hasPassword,
                biometric = cur.biometricEnabled,
                startupError = cur.startupError
            )
            true
        }
        if (didLock) {
            onLockCallbacks.forEach { cb -> runCatching { cb() } }
        }
    }

    fun setMasterPassword(currentPassword: String, newPassword: String): Result<Unit> = synchronized(lock) {
        runCatching {
            requireUnlocked()
            if (!activePassword.isNullOrEmpty() && currentPassword != activePassword) {
                throw SecurityException("当前主密码不正确")
            }
            val nextPassword = newPassword.ifEmpty { null }
            val nextKey = nextPassword?.let { store.deriveForNewPassword(it) }
            try {
                store.save(currentData, nextPassword, nextKey)
            } catch (e: Exception) {
                nextKey?.clear()
                throw e
            }
            val oldKey = cachedPasswordKey
            cachedPasswordKey = nextKey
            activePassword = nextPassword
            oldKey?.clear()
            cancelDebouncedFlushLocked()
            pendingDirty = false
            emitStateLocked(locked = false, hasPassword = !activePassword.isNullOrEmpty())
        }
    }

    fun saveServer(server: ServerConfig): ServerConfig = mutate { VaultCrudOps.upsertServer(it, server) }
    fun deleteServer(id: String) = mutateUnit { it.copy(servers = it.servers.filterNot { s -> s.id == id }) }
    fun saveSshKey(key: SSHKeyRecord): SSHKeyRecord = mutate { VaultCrudOps.upsertSshKey(it, key) }
    fun deleteSshKey(id: String) = mutateUnit { it.copy(sshKeys = it.sshKeys.filterNot { k -> k.id == id }) }
    fun saveShortcut(shortcut: CommandShortcut): CommandShortcut = mutate { VaultCrudOps.upsertShortcut(it, shortcut) }
    fun deleteShortcut(id: String) = mutateUnit { it.copy(shortcuts = it.shortcuts.filterNot { s -> s.id == id }) }
    fun saveTemplate(template: BuiltinTemplate): BuiltinTemplate = mutate { VaultCrudOps.upsertTemplate(it, template) }
    fun deleteTemplate(id: String) = mutateUnit { it.copy(templates = it.templates.filterNot { t -> t.id == id }) }
    fun clearHistory() = mutateUnit { it.copy(history = emptyList()) }
    fun importSyncBundle(bundle: SyncBundle, mode: SyncImportMode): SyncImportSummary = mutate { VaultSyncOps.applyBundle(it, bundle, mode) }

    fun markServerConnected(id: String) = mutateDebouncedIfUnlocked { d ->
        if (d.servers.none { it.id == id }) return@mutateDebouncedIfUnlocked d
        val now = Instant.now().toString()
        d.copy(servers = d.servers.map { if (it.id == id) it.copy(lastConnectedAt = now) else it })
    }

    fun incrementShortcutUse(id: String) = mutateDebouncedIfUnlocked { d ->
        if (d.shortcuts.none { it.id == id }) return@mutateDebouncedIfUnlocked d
        d.copy(shortcuts = d.shortcuts.map { if (it.id == id) it.copy(useCount = it.useCount + 1) else it })
    }

    fun loadPresetTemplates(): PresetLoadResult = synchronized(lock) {
        requireUnlocked()
        val (next, res) = VaultCrudOps.loadPresets(currentData)
        if (res.added > 0) {
            persistAndCommitLocked(next)
        }
        res
    }

    fun recordCommand(command: String, serverId: String, serverName: String, sessionId: String) =
        mutateDebouncedIfUnlocked { d ->
            if (!d.settings.recordCommands || command.isBlank()) return@mutateDebouncedIfUnlocked d
            VaultCrudOps.appendHistory(d, command, serverId, serverName, sessionId)
        }

    fun flushPendingHistory() = synchronized(lock) {
        if (!pendingDirty || _state.value.lockState.locked) return@synchronized
        cancelDebouncedFlushLocked()
        store.save(currentData, activePassword, cachedPasswordKey)
        pendingDirty = false
    }

    fun flushPendingHistoryAsync() {
        ioScope.launch {
            runCatching { flushPendingHistory() }
                .onFailure { e -> runCatching { Log.e(TAG, "Async pending vault flush failed", e) } }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutate { d ->
        val u = transform(d.settings)
        d.copy(settings = u) to u
    }

    fun exportSyncBundle(sourceDevice: String): SyncBundle = synchronized(lock) {
        requireUnlocked()
        VaultSyncOps.exportBundle(currentData, sourceDevice)
    }

    private fun <T> mutate(op: (VaultData) -> Pair<VaultData, T>): T = synchronized(lock) {
        requireUnlocked()
        val (next, res) = op(currentData)
        persistAndCommitLocked(next)
        res
    }

    private fun mutateUnit(op: (VaultData) -> VaultData) = synchronized(lock) {
        requireUnlocked()
        val next = op(currentData)
        persistAndCommitLocked(next)
    }

    private fun mutateDebouncedIfUnlocked(op: (VaultData) -> VaultData) = synchronized(lock) {
        if (_state.value.lockState.locked) return@synchronized
        val next = op(currentData)
        if (next !== currentData) {
            currentData = next
            pendingDirty = true
            emitStateLocked(locked = false, hasPassword = !activePassword.isNullOrEmpty())
            scheduleDebouncedFlushLocked()
        }
    }

    private fun requireUnlocked() {
        if (_state.value.lockState.locked) throw SecurityException("保险库已锁定")
    }

    private fun persistAndCommitLocked(next: VaultData) {
        // Persist BEFORE updating in-memory state so failures leave state untouched (rollback-safe)
        store.save(next, activePassword, cachedPasswordKey)
        currentData = next
        cancelDebouncedFlushLocked()
        pendingDirty = false
        emitStateLocked(locked = false, hasPassword = !activePassword.isNullOrEmpty())
    }

    private fun scheduleDebouncedFlushLocked() {
        debouncedFlushJob?.cancel()
        debouncedFlushJob = ioScope.launch {
            delay(DEBOUNCED_FLUSH_MS)
            synchronized(lock) {
                if (!pendingDirty || _state.value.lockState.locked) return@synchronized
                runCatching {
                    store.save(currentData, activePassword, cachedPasswordKey)
                    pendingDirty = false
                }.onFailure { e ->
                    runCatching { Log.e(TAG, "Debounced vault flush failed", e) }
                }
            }
        }
    }

    private fun cancelDebouncedFlushLocked() {
        debouncedFlushJob?.cancel()
        debouncedFlushJob = null
    }

    private fun clearSecretsLocked() {
        activePassword = null
        cachedPasswordKey?.clear()
        cachedPasswordKey = null
    }

    private fun emitStateLocked(
        locked: Boolean,
        hasPassword: Boolean,
        biometric: Boolean = currentData.settings.biometricLockEnabled,
        startupError: String? = null
    ) {
        _state.value = VaultState(
            lockState = VaultLockState(
                locked = locked,
                hasPassword = hasPassword,
                biometricEnabled = biometric,
                startupError = startupError
            ),
            data = if (locked) VaultData() else currentData
        )
    }

    companion object {
        private const val TAG = "VaultRepository"
        private const val DEBOUNCED_FLUSH_MS = 1500L
    }
}
