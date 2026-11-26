package com.gravityssh.app.vault

import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.VaultData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun path(): String = vaultFile.absolutePath

    fun openInitial(): VaultOpenResult {
        if (!vaultFile.exists()) {
            val init = VaultData(templates = BuiltinOpsPresets.all())
            save(init, null)
            return VaultOpenResult(locked = false, hasPassword = false, biometric = false, data = init)
        }
        return when (crypto.readMode(vaultFile.readBytes())) {
            VaultCrypto.MODE_PASSWORD -> VaultOpenResult(locked = true, hasPassword = true, biometric = false, data = VaultData())
            VaultCrypto.MODE_BIOMETRIC -> VaultOpenResult(locked = true, hasPassword = false, biometric = true, data = VaultData())
            else -> {
                val data = load(null)
                VaultOpenResult(locked = false, hasPassword = false, biometric = data.settings.biometricLockEnabled, data = data)
            }
        }
    }

    fun loadForBiometric(cachedPassword: String?): Pair<VaultData, Boolean> {
        val blob = vaultFile.readBytes()
        val pw = if (crypto.isPasswordProtected(blob)) {
            cachedPassword?.takeIf { it.isNotEmpty() } ?: throw SecurityException("请先输入主密码解锁")
        } else null
        return decode(crypto.open(blob, pw)) to (pw != null)
    }

    fun load(password: String? = null): VaultData = decode(crypto.open(vaultFile.readBytes(), password))

    fun save(data: VaultData, password: String?) {
        vaultFile.parentFile?.mkdirs()
        val plain = json.encodeToString(data).toByteArray(Charsets.UTF_8)
        val blob = crypto.seal(plain, password, data.settings.biometricLockEnabled)
        val tmp = File(vaultFile.parentFile, "${vaultFile.name}.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(vaultFile)) {
            vaultFile.writeBytes(blob)
            tmp.delete()
        }
    }

    private fun decode(plain: ByteArray): VaultData =
        json.decodeFromString(VaultData.serializer(), plain.toString(Charsets.UTF_8))
}
