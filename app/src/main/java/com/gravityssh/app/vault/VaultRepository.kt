package com.gravityssh.app.vault

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

class VaultRepository(
    vaultFile: File,
    crypto: VaultCrypto = VaultCrypto()
) {
    private val lock = Any()
    private val store = VaultPersistence(vaultFile, crypto)
    private var activePassword: String? = null
    private var currentData: VaultData = VaultData()

    private val _state = MutableStateFlow(VaultState(lockState = VaultLockState(vaultPath = store.path())))
    val state: StateFlow<VaultState> = _state.asStateFlow()

    fun open() = synchronized(lock) {
        val res = store.openInitial()
        currentData = res.data
        emitStateLocked(res.locked, res.hasPassword, res.biometric)
    }

    fun unlock(password: String): Result<Unit> = synchronized(lock) {
        runCatching {
            currentData = store.load(password)
            activePassword = password
            emitStateLocked(locked = false, hasPassword = true)
        }
    }

    fun unlockWithBiometric(): Result<Unit> = synchronized(lock) {
        runCatching {
            val (data, hasPw) = store.loadForBiometric(activePassword)
            currentData = data
            emitStateLocked(locked = false, hasPassword = hasPw)
        }
    }

    fun lockNow() = synchronized(lock) {
        val cur = _state.value.lockState
        if (cur.hasPassword || cur.biometricEnabled) emitStateLocked(true, cur.hasPassword, cur.biometricEnabled)
    }

    fun setMasterPassword(currentPassword: String, newPassword: String): Result<Unit> = synchronized(lock) {
        runCatching {
            if (!activePassword.isNullOrEmpty() && currentPassword != activePassword) {
                throw SecurityException("当前主密码不正确")
            }
            activePassword = newPassword.ifEmpty { null }
            persistAndEmitLocked()
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

    fun markServerConnected(id: String) = mutateIfUnlocked { d ->
        val now = Instant.now().toString()
        d.copy(servers = d.servers.map { if (it.id == id) it.copy(lastConnectedAt = now) else it })
    }

    fun incrementShortcutUse(id: String) = mutateIfUnlocked { d ->
        d.copy(shortcuts = d.shortcuts.map { if (it.id == id) it.copy(useCount = it.useCount + 1) else it })
    }

    fun loadPresetTemplates(): PresetLoadResult = synchronized(lock) {
        requireUnlocked()
        val (next, res) = VaultCrudOps.loadPresets(currentData)
        if (res.added > 0) { currentData = next; persistAndEmitLocked() }
        res
    }

    fun recordCommand(command: String, serverId: String, serverName: String, sessionId: String) = mutateIfUnlocked { d ->
        if (!d.settings.recordCommands || command.isBlank()) d
        else VaultCrudOps.appendHistory(d, command, serverId, serverName, sessionId)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutate { d ->
        val u = transform(d.settings); d.copy(settings = u) to u
    }

    fun exportSyncBundle(sourceDevice: String): SyncBundle = synchronized(lock) {
        requireUnlocked(); VaultSyncOps.exportBundle(currentData, sourceDevice)
    }

    private fun <T> mutate(op: (VaultData) -> Pair<VaultData, T>): T = synchronized(lock) {
        requireUnlocked(); val (next, res) = op(currentData); currentData = next; persistAndEmitLocked(); res
    }

    private fun mutateUnit(op: (VaultData) -> VaultData) = synchronized(lock) {
        requireUnlocked(); currentData = op(currentData); persistAndEmitLocked()
    }

    private fun mutateIfUnlocked(op: (VaultData) -> VaultData) = synchronized(lock) {
        if (_state.value.lockState.locked) return@synchronized
        val next = op(currentData); if (next !== currentData) { currentData = next; persistAndEmitLocked() }
    }

    private fun requireUnlocked() { if (_state.value.lockState.locked) throw SecurityException("保险库已锁定") }

    private fun persistAndEmitLocked() {
        store.save(currentData, activePassword)
        emitStateLocked(locked = false, hasPassword = !activePassword.isNullOrEmpty())
    }

    private fun emitStateLocked(locked: Boolean, hasPassword: Boolean, biometric: Boolean = currentData.settings.biometricLockEnabled) {
        _state.value = VaultState(
            VaultLockState(locked, hasPassword, biometric, store.path(), currentData.version),
            if (locked) VaultData() else currentData
        )
    }
}
