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
import com.gravityssh.app.model.SyncVaultState
import com.gravityssh.app.model.VaultData
import com.gravityssh.app.model.VaultLockState
import com.gravityssh.app.model.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class VaultRepository(
    vaultFile: File,
    crypto: VaultCrypto = VaultCrypto()
) {
    private val lock = Any()
    private val store = VaultPersistence(vaultFile, crypto)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onLockCallbacks = CopyOnWriteArrayList<() -> Unit>()

    private var cachedPasswordKey: CachedPasswordKey? = null
    private var currentData: VaultData = VaultData()
    private var pendingDirty: Boolean = false
    private var debouncedFlushJob: Job? = null

    val masterKeyProvider: MasterKeyProvider
        get() = store.masterKeyProvider

    private val _state = MutableStateFlow(
        VaultState(lockState = VaultLockState(locked = true, initialized = false))
    )
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
                startupError = null,
                initialized = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { Log.e(TAG, "Failed to open vault", e) }
            clearSecretsLocked()
            currentData = VaultData()
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(
                locked = true,
                hasPassword = false,
                biometric = false,
                startupError = "保险库打开失败（文件损坏或系统密钥不可用）：${e.message ?: e.javaClass.simpleName}",
                initialized = true
            )
        }
    }

    fun resetCorruptedVault(): Result<Unit> = synchronized(lock) {
        runCatching {
            store.backupCorruptedFile()
            val res = try {
                store.openInitial()
            } catch (_: Throwable) {
                masterKeyProvider.resetMasterKey()
                masterKeyProvider.resetBiometricAuthKey()
                store.deleteVaultFileIfExists()
                store.openInitial()
            }
            clearSecretsLocked()
            currentData = res.data
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(
                locked = res.locked,
                hasPassword = res.hasPassword,
                biometric = res.biometric,
                startupError = null,
                initialized = true
            )
        }
    }

    fun unlock(password: String): Result<Unit> = synchronized(lock) {
        runCatching {
            val (data, newKey) = store.loadWithKeyCache(password)
            val oldKey = cachedPasswordKey
            currentData = data
            cachedPasswordKey = newKey
            oldKey?.clear()
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(locked = false, hasPassword = newKey != null, startupError = null, initialized = true)
        }.onFailure { e ->
            if (e is VaultKeystoreException || e is IllegalArgumentException || e is SerializationException) {
                runCatching { Log.e(TAG, "Vault unlock failed due to keystore/corruption", e) }
                val cur = _state.value.lockState
                emitStateLocked(
                    locked = true,
                    hasPassword = cur.hasPassword,
                    biometric = cur.biometricEnabled,
                    startupError = e.message ?: "系统密钥已失效或保险库文件损坏，请备份并重置保险库",
                    initialized = true
                )
            }
        }
    }

    fun unlockWithBiometric(): Result<Unit> = synchronized(lock) {
        runCatching {
            val (data, hasPw, newKey) = store.loadForBiometric()
            val oldKey = cachedPasswordKey
            currentData = data
            cachedPasswordKey = newKey
            oldKey?.clear()
            pendingDirty = false
            cancelDebouncedFlushLocked()
            emitStateLocked(locked = false, hasPassword = hasPw, startupError = null, initialized = true)
        }.onFailure { e ->
            if (e is VaultKeystoreException || e is IllegalArgumentException || e is SerializationException) {
                runCatching { Log.e(TAG, "Biometric unlock failed due to keystore/corruption", e) }
                val cur = _state.value.lockState
                emitStateLocked(
                    locked = true,
                    hasPassword = cur.hasPassword,
                    biometric = cur.biometricEnabled,
                    startupError = e.message ?: "系统密钥已失效或保险库文件损坏，请备份并重置保险库",
                    initialized = true
                )
            }
        }
    }

    fun lockNow() {
        var dirtySnapshot: VaultData? = null
        var dirtyKeyCopy: CachedPasswordKey? = null
        var flushFuture: CompletableFuture<Unit>? = null

        val didLock = synchronized(lock) {
            val cur = _state.value.lockState
            if (cur.locked) return@synchronized false
            if (!cur.hasPassword && !cur.biometricEnabled) return@synchronized false

            if (pendingDirty) {
                dirtySnapshot = currentData
                dirtyKeyCopy = cachedPasswordKey?.let(::clonePasswordKey)
                flushFuture = store.registerPendingFlush()
            }
            cancelDebouncedFlushLocked()
            pendingDirty = false
            clearSecretsLocked()
            currentData = VaultData()
            emitStateLocked(
                locked = true,
                hasPassword = cur.hasPassword,
                biometric = cur.biometricEnabled,
                startupError = cur.startupError,
                initialized = cur.initialized
            )
            true
        }

        if (!didLock) return

        onLockCallbacks.forEach { cb -> runCatching { cb() } }

        val snap = dirtySnapshot
        val fut = flushFuture
        if (snap != null && fut != null) {
            val keyCopy = dirtyKeyCopy
            ioScope.launch {
                try {
                    store.saveFromAsyncFlush(snap, keyCopy)
                } catch (e: Throwable) {
                    runCatching { Log.e(TAG, "Failed to save pending vault changes after lock", e) }
                } finally {
                    keyCopy?.clear()
                    store.completePendingFlush(fut)
                }
            }
        }
    }

    fun setMasterPassword(currentPassword: String, newPassword: String): Result<Unit> = synchronized(lock) {
        runCatching {
            requireUnlocked()
            val curKey = cachedPasswordKey
            if (curKey != null && !store.verifyPassword(currentPassword, curKey)) {
                throw SecurityException("当前主密码不正确")
            }
            if (newPassword.isNotEmpty() && newPassword.isBlank()) {
                throw IllegalArgumentException("主密码不能仅包含空白字符")
            }
            if (curKey == null && newPassword.isEmpty()) {
                throw IllegalArgumentException("请输入新主密码")
            }
            val nextPassword = newPassword.ifEmpty { null }
            val nextData = if (nextPassword != null && currentData.settings.biometricLockEnabled) {
                currentData.copy(settings = currentData.settings.copy(biometricLockEnabled = false))
            } else {
                currentData
            }
            val nextKey = nextPassword?.let { store.deriveForNewPassword(it) }
            try {
                store.save(nextData, nextKey)
            } catch (e: Exception) {
                nextKey?.clear()
                throw e
            }
            val oldKey = cachedPasswordKey
            currentData = nextData
            cachedPasswordKey = nextKey
            oldKey?.clear()
            cancelDebouncedFlushLocked()
            pendingDirty = false
            emitStateLocked(locked = false, hasPassword = cachedPasswordKey != null)
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

    fun importSyncBundle(bundle: SyncBundle, mode: String): SyncImportSummary =
        importSyncBundle(bundle, SyncImportMode.fromWire(mode))

    fun readSyncState(): SyncVaultState = synchronized(lock) {
        requireUnlocked()
        currentData.syncState
    }

    fun readSyncStateOrNull(): SyncVaultState? = synchronized(lock) {
        if (_state.value.lockState.locked) null else currentData.syncState
    }

    fun <T> mutateSyncState(op: (SyncVaultState) -> Pair<SyncVaultState, T>): T = mutate { d ->
        val (nextSync, res) = op(d.syncState)
        d.copy(syncState = nextSync) to res
    }

    fun updateSyncState(op: (SyncVaultState) -> SyncVaultState): SyncVaultState = mutate { d ->
        val nextSync = op(d.syncState)
        d.copy(syncState = nextSync) to nextSync
    }

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
        store.save(currentData, cachedPasswordKey)
        pendingDirty = false
    }

    fun flushPendingHistoryAsync() {
        ioScope.launch {
            runCatching { flushPendingHistory() }
                .onFailure { e -> runCatching { Log.e(TAG, "Async pending vault flush failed", e) } }
        }
    }

    fun shouldAutoLock(backgroundedAtElapsedMs: Long, nowElapsedMs: Long): Boolean {
        val snap = _state.value
        return VaultCrudOps.shouldAutoLockAfterBackground(
            lockState = snap.lockState,
            autoLockMinutes = snap.data.settings.autoLockMinutes,
            backgroundedAtElapsedMs = backgroundedAtElapsedMs,
            nowElapsedMs = nowElapsedMs
        )
    }

    fun lockIfAutoLockElapsed(backgroundedAtElapsedMs: Long, nowElapsedMs: Long): Boolean {
        if (!shouldAutoLock(backgroundedAtElapsedMs, nowElapsedMs)) return false
        lockNow()
        return _state.value.lockState.locked
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutate { d ->
        val transformed = VaultCrudOps.sanitizeSettings(transform(d.settings).copy(syncRelayUrl = ""))
        val u = if (cachedPasswordKey != null && transformed.biometricLockEnabled) {
            transformed.copy(biometricLockEnabled = false)
        } else {
            transformed
        }
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
            emitStateLocked(locked = false, hasPassword = cachedPasswordKey != null)
            scheduleDebouncedFlushLocked()
        }
    }

    private fun requireUnlocked() {
        if (_state.value.lockState.locked) throw SecurityException("保险库已锁定")
    }

    private fun persistAndCommitLocked(next: VaultData) {
        // Persist BEFORE updating in-memory state so failures leave state untouched (rollback-safe)
        store.save(next, cachedPasswordKey)
        currentData = next
        cancelDebouncedFlushLocked()
        pendingDirty = false
        emitStateLocked(locked = false, hasPassword = cachedPasswordKey != null)
    }

    private fun scheduleDebouncedFlushLocked() {
        debouncedFlushJob?.cancel()
        debouncedFlushJob = ioScope.launch {
            delay(DEBOUNCED_FLUSH_MS)
            synchronized(lock) {
                if (!pendingDirty || _state.value.lockState.locked) return@synchronized
                runCatching {
                    store.save(currentData, cachedPasswordKey)
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

    private fun clonePasswordKey(src: CachedPasswordKey): CachedPasswordKey {
        val salt = src.saltCopy()
        val raw = src.secretKey().encoded
        return try {
            CachedPasswordKey(salt, raw)
        } finally {
            CryptoUtils.zero(salt)
            CryptoUtils.zero(raw)
        }
    }

    private fun clearSecretsLocked() {
        cachedPasswordKey?.clear()
        cachedPasswordKey = null
    }

    private fun emitStateLocked(
        locked: Boolean,
        hasPassword: Boolean,
        biometric: Boolean = currentData.settings.biometricLockEnabled,
        startupError: String? = null,
        initialized: Boolean = true
    ) {
        val effectiveBiometric = !hasPassword && biometric
        _state.value = VaultState(
            lockState = VaultLockState(
                locked = locked,
                hasPassword = hasPassword,
                biometricEnabled = effectiveBiometric,
                startupError = startupError,
                initialized = initialized
            ),
            data = if (locked) VaultData() else currentData
        )
    }

    companion object {
        private const val TAG = "VaultRepository"
        private const val DEBOUNCED_FLUSH_MS = 1500L
    }
}
