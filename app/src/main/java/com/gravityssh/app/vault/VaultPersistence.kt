package com.gravityssh.app.vault

import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.VaultData
import com.yellowdking.synctool.envelope.SyncUri
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class VaultOpenResult(
    val locked: Boolean,
    val hasPassword: Boolean,
    val biometric: Boolean,
    val data: VaultData
)

internal class VaultPersistence(
    private val vaultFile: File,
    private val crypto: VaultCrypto
) {
    private val json = AppJson.json
    private val fileKey: String = runCatching { vaultFile.canonicalPath }.getOrElse { vaultFile.absolutePath }

    val masterKeyProvider: MasterKeyProvider
        get() = crypto.masterKeyProvider

    fun registerPendingFlush(): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        val prev = pendingFlushes.put(fileKey, future)
        prev?.let { runCatching { it.get(FLUSH_WAIT_SECONDS, TimeUnit.SECONDS) } }
        return future
    }

    fun completePendingFlush(future: CompletableFuture<Unit>) {
        future.complete(Unit)
        pendingFlushes.remove(fileKey, future)
    }

    private fun awaitPendingFlush() {
        pendingFlushes[fileKey]?.let { fut ->
            runCatching { fut.get(FLUSH_WAIT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    fun openInitial(): VaultOpenResult {
        awaitPendingFlush()
        if (!vaultFile.exists()) {
            val init = VaultData(templates = BuiltinOpsPresets.all())
            saveInternal(init, cachedKey = null)
            return VaultOpenResult(locked = false, hasPassword = false, biometric = false, data = init)
        }
        val blob = vaultFile.readBytes()
        return when (crypto.readMode(blob)) {
            VaultCrypto.MODE_PASSWORD -> VaultOpenResult(locked = true, hasPassword = true, biometric = false, data = VaultData())
            VaultCrypto.MODE_BIOMETRIC -> VaultOpenResult(locked = true, hasPassword = false, biometric = true, data = VaultData())
            else -> {
                val plain = crypto.open(blob, null)
                val (data, migratedRelay) = try {
                    decodeWithMigrationFlag(plain)
                } finally {
                    CryptoUtils.zero(plain)
                }
                if (migratedRelay) {
                    runCatching { saveInternal(data, cachedKey = null) }
                }
                VaultOpenResult(locked = false, hasPassword = false, biometric = data.settings.biometricLockEnabled, data = data)
            }
        }
    }

    fun loadWithKeyCache(password: String? = null): Pair<VaultData, CachedPasswordKey?> {
        awaitPendingFlush()
        val (plain, cachedKey) = crypto.openWithKeyCache(vaultFile.readBytes(), password)
        return try {
            val (data, migratedRelay) = decodeWithMigrationFlag(plain)
            if (migratedRelay) {
                runCatching { saveInternal(data, cachedKey = cachedKey) }
            }
            data to cachedKey
        } catch (e: Exception) {
            cachedKey?.clear()
            throw e
        } finally {
            CryptoUtils.zero(plain)
        }
    }

    fun loadForBiometric(): Triple<VaultData, Boolean, CachedPasswordKey?> {
        awaitPendingFlush()
        val blob = vaultFile.readBytes()
        if (crypto.isPasswordProtected(blob)) {
            throw SecurityException("请先输入主密码解锁")
        }
        val (plain, cachedKey) = crypto.openWithKeyCache(blob, null)
        return try {
            val (data, migratedRelay) = decodeWithMigrationFlag(plain)
            if (migratedRelay) {
                runCatching { saveInternal(data, cachedKey = cachedKey) }
            }
            Triple(data, false, cachedKey)
        } catch (e: Exception) {
            cachedKey?.clear()
            throw e
        } finally {
            CryptoUtils.zero(plain)
        }
    }

    fun deriveForNewPassword(password: String): CachedPasswordKey =
        crypto.deriveForNewPassword(password)

    fun verifyPassword(password: String, cachedKey: CachedPasswordKey): Boolean =
        crypto.verifyPassword(password, cachedKey)

    fun save(data: VaultData, cachedKey: CachedPasswordKey? = null) {
        awaitPendingFlush()
        saveInternal(data, cachedKey)
    }

    fun saveFromAsyncFlush(data: VaultData, cachedKey: CachedPasswordKey? = null) {
        saveInternal(data, cachedKey)
    }

    fun deleteVaultFileIfExists() {
        awaitPendingFlush()
        runCatching {
            if (vaultFile.exists()) {
                vaultFile.delete()
            }
        }
    }

    private fun saveInternal(data: VaultData, cachedKey: CachedPasswordKey? = null) {
        val plain = json.encodeToString(data).toByteArray(Charsets.UTF_8)
        val blob = try {
            val biometricLock = cachedKey == null && data.settings.biometricLockEnabled
            crypto.sealWithCachedKey(plain, cachedKey, biometricLock)
        } finally {
            CryptoUtils.zero(plain)
        }
        AtomicFileOps.writeBytesAtomic(vaultFile, blob)
    }

    fun backupCorruptedFile(): File? {
        awaitPendingFlush()
        if (!vaultFile.exists()) return null
        val dir = vaultFile.parentFile ?: File(".")
        val prefix = "${vaultFile.name}.corrupt."
        var ts = System.currentTimeMillis()
        var backup = File(dir, "$prefix$ts")
        while (backup.exists()) {
            ts++
            backup = File(dir, "$prefix$ts")
        }
        Files.move(vaultFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        pruneCorruptBackups(dir, prefix, MAX_CORRUPT_BACKUPS)
        return backup
    }

    private fun decodeWithMigrationFlag(plain: ByteArray): Pair<VaultData, Boolean> {
        val decoded = json.decodeFromString(VaultData.serializer(), plain.toString(Charsets.UTF_8))
        val legacyRaw = decoded.settings.syncRelayUrl
        var nextSyncState = decoded.syncState
        var migratedRelay = false
        if (legacyRaw.isNotEmpty()) {
            migratedRelay = true
            val trimmed = legacyRaw.trim()
            if (trimmed.isNotEmpty() && nextSyncState.customRelayUri.isBlank()) {
                val parsedProfile = runCatching { SyncUri.parseRelayUri(trimmed) }.getOrNull()
                if (parsedProfile != null) {
                    val canonicalUri = runCatching { SyncUri.formatRelayUri(parsedProfile) }.getOrNull()
                    if (!canonicalUri.isNullOrEmpty()) {
                        nextSyncState = nextSyncState.copy(customRelayUri = canonicalUri)
                    }
                }
            }
        }
        val baseSettings = if (legacyRaw.isNotEmpty()) {
            decoded.settings.copy(syncRelayUrl = "")
        } else {
            decoded.settings
        }
        val sanitizedSettings = VaultCrudOps.sanitizeSettings(baseSettings)
        val finalData = if (sanitizedSettings == decoded.settings && nextSyncState == decoded.syncState) {
            decoded
        } else {
            decoded.copy(settings = sanitizedSettings, syncState = nextSyncState)
        }
        return finalData to migratedRelay
    }

    companion object {
        const val MAX_CORRUPT_BACKUPS = 3
        private const val FLUSH_WAIT_SECONDS = 5L
        private val pendingFlushes = ConcurrentHashMap<String, CompletableFuture<Unit>>()

        internal fun pruneCorruptBackups(dir: File, prefix: String, maxBackups: Int = MAX_CORRUPT_BACKUPS) {
            val backups = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) } ?: return
            if (backups.size <= maxBackups) return
            backups
                .sortedWith(
                    compareByDescending<File> { it.name.removePrefix(prefix).toLongOrNull() ?: Long.MIN_VALUE }
                        .thenByDescending { it.lastModified() }
                        .thenByDescending { it.name }
                )
                .drop(maxBackups)
                .forEach { stale -> runCatching { stale.delete() } }
        }
    }
}
