package com.gravityssh.app.vault

import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.VaultData
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    val masterKeyProvider: MasterKeyProvider
        get() = crypto.masterKeyProvider

    fun openInitial(): VaultOpenResult {
        if (!vaultFile.exists()) {
            val init = VaultData(templates = BuiltinOpsPresets.all())
            save(init, cachedKey = null)
            return VaultOpenResult(locked = false, hasPassword = false, biometric = false, data = init)
        }
        val blob = vaultFile.readBytes()
        return when (crypto.readMode(blob)) {
            VaultCrypto.MODE_PASSWORD -> VaultOpenResult(locked = true, hasPassword = true, biometric = false, data = VaultData())
            VaultCrypto.MODE_BIOMETRIC -> VaultOpenResult(locked = true, hasPassword = false, biometric = true, data = VaultData())
            else -> {
                val plain = crypto.open(blob, null)
                val data = try {
                    decode(plain)
                } finally {
                    CryptoUtils.zero(plain)
                }
                VaultOpenResult(locked = false, hasPassword = false, biometric = data.settings.biometricLockEnabled, data = data)
            }
        }
    }

    fun loadWithKeyCache(password: String? = null): Pair<VaultData, CachedPasswordKey?> {
        val (plain, cachedKey) = crypto.openWithKeyCache(vaultFile.readBytes(), password)
        return try {
            decode(plain) to cachedKey
        } catch (e: Exception) {
            cachedKey?.clear()
            throw e
        } finally {
            CryptoUtils.zero(plain)
        }
    }

    fun loadForBiometric(): Triple<VaultData, Boolean, CachedPasswordKey?> {
        val blob = vaultFile.readBytes()
        if (crypto.isPasswordProtected(blob)) {
            throw SecurityException("请先输入主密码解锁")
        }
        val (plain, cachedKey) = crypto.openWithKeyCache(blob, null)
        return try {
            Triple(decode(plain), false, cachedKey)
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
        if (!vaultFile.exists()) return null
        val backup = File(vaultFile.parentFile ?: File("."), "${vaultFile.name}.corrupt.${System.currentTimeMillis()}")
        Files.move(vaultFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return backup
    }

    private fun decode(plain: ByteArray): VaultData =
        json.decodeFromString(VaultData.serializer(), plain.toString(Charsets.UTF_8))
}
