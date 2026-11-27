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

    fun openInitial(): VaultOpenResult {
        if (!vaultFile.exists()) {
            val init = VaultData(templates = BuiltinOpsPresets.all())
            save(init, password = null, cachedKey = null)
            return VaultOpenResult(locked = false, hasPassword = false, biometric = false, data = init)
        }
        val blob = vaultFile.readBytes()
        return when (crypto.readMode(blob)) {
            VaultCrypto.MODE_PASSWORD -> VaultOpenResult(locked = true, hasPassword = true, biometric = false, data = VaultData())
            VaultCrypto.MODE_BIOMETRIC -> VaultOpenResult(locked = true, hasPassword = false, biometric = true, data = VaultData())
            else -> {
                val data = decode(crypto.open(blob, null))
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
        }
    }

    fun loadForBiometric(cachedPassword: String?): Triple<VaultData, Boolean, CachedPasswordKey?> {
        val blob = vaultFile.readBytes()
        val pw = if (crypto.isPasswordProtected(blob)) {
            cachedPassword?.takeIf { it.isNotEmpty() } ?: throw SecurityException("请先输入主密码解锁")
        } else {
            null
        }
        val (plain, cachedKey) = crypto.openWithKeyCache(blob, pw)
        return try {
            Triple(decode(plain), pw != null, cachedKey)
        } catch (e: Exception) {
            cachedKey?.clear()
            throw e
        }
    }

    fun deriveForNewPassword(password: String): CachedPasswordKey =
        crypto.deriveForNewPassword(password)

    fun save(data: VaultData, password: String?, cachedKey: CachedPasswordKey? = null) {
        val plain = json.encodeToString(data).toByteArray(Charsets.UTF_8)
        val hasPassword = cachedKey != null || !password.isNullOrEmpty()
        val biometricLock = !hasPassword && data.settings.biometricLockEnabled
        val blob = if (cachedKey != null) {
            crypto.sealWithCachedKey(plain, cachedKey, biometricLock)
        } else {
            crypto.seal(plain, password, biometricLock)
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
