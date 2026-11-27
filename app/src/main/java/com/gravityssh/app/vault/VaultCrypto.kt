package com.gravityssh.app.vault

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class CachedPasswordKey internal constructor(
    salt: ByteArray,
    keyBytes: ByteArray
) {
    private val saltBytes = salt.copyOf()
    private val rawKey = keyBytes.copyOf()

    @Volatile
    private var cleared = false

    internal fun saltCopy(): ByteArray {
        check(!cleared) { "CachedPasswordKey has been cleared" }
        return saltBytes.copyOf()
    }

    internal fun secretKey(): SecretKey {
        check(!cleared) { "CachedPasswordKey has been cleared" }
        return SecretKeySpec(rawKey, "AES")
    }

    fun clear() {
        cleared = true
        CryptoUtils.zero(rawKey)
        CryptoUtils.zero(saltBytes)
    }
}

class VaultCrypto(
    private val masterKeyProvider: MasterKeyProvider = AndroidKeystoreMasterKeyProvider()
) {
    private val random = SecureRandom()

    fun isPasswordProtected(blob: ByteArray): Boolean = readMode(blob) == MODE_PASSWORD

    fun readMode(blob: ByteArray): Byte {
        validateHeader(blob)
        return blob[5]
    }

    fun deriveForNewPassword(password: String): CachedPasswordKey {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val keyBytes = CryptoUtils.pbkdf2Sha256(password, salt, PBKDF2_ITERS, 256)
        return try {
            CachedPasswordKey(salt, keyBytes)
        } finally {
            CryptoUtils.zero(keyBytes)
        }
    }

    fun seal(plaintext: ByteArray, password: String? = null, biometricLock: Boolean = false): ByteArray {
        val (mode, innerPayload) = if (!password.isNullOrEmpty()) {
            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val keyBytes = CryptoUtils.pbkdf2Sha256(password, salt, PBKDF2_ITERS, 256)
            val enc = try {
                CryptoUtils.aesGcmEncrypt(keyBytes, iv, plaintext, AAD_INNER)
            } finally {
                CryptoUtils.zero(keyBytes)
            }
            MODE_PASSWORD to packInnerPayload(salt, iv, enc)
        } else {
            val m = if (biometricLock) MODE_BIOMETRIC else MODE_KEYSTORE
            m to plaintext
        }
        return sealOuterV2(mode, innerPayload)
    }

    fun sealWithCachedKey(
        plaintext: ByteArray,
        cachedKey: CachedPasswordKey?,
        biometricLock: Boolean = false
    ): ByteArray {
        val (mode, innerPayload) = if (cachedKey != null) {
            val salt = cachedKey.saltCopy()
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val enc = CryptoUtils.aesGcmEncrypt(cachedKey.secretKey(), iv, plaintext, AAD_INNER)
            MODE_PASSWORD to packInnerPayload(salt, iv, enc)
        } else {
            val m = if (biometricLock) MODE_BIOMETRIC else MODE_KEYSTORE
            m to plaintext
        }
        return sealOuterV2(mode, innerPayload)
    }

    fun open(blob: ByteArray, password: String? = null): ByteArray {
        val (plain, cached) = openWithKeyCache(blob, password)
        cached?.clear()
        return plain
    }

    fun openWithKeyCache(blob: ByteArray, password: String? = null): Pair<ByteArray, CachedPasswordKey?> {
        val version = validateHeader(blob)
        val mode = blob[5]
        val ivLen = blob[6].toInt() and 0xFF
        if (ivLen == 0 || blob.size < HEADER_SIZE + ivLen + 16) {
            throw IllegalArgumentException("Vault payload truncated")
        }
        val header = blob.copyOfRange(0, HEADER_SIZE)
        val outerIv = blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivLen)
        val outerCt = blob.copyOfRange(HEADER_SIZE + ivLen, blob.size)
        val outerAad = when (version) {
            VERSION_V1 -> AAD_OUTER_V1
            VERSION_V2 -> buildOuterAadV2(header)
            else -> throw IllegalArgumentException("Unsupported vault version: $version")
        }
        val masterKey = masterKeyProvider.getOrCreateKey()
        val innerPayload = CryptoUtils.aesGcmDecrypt(masterKey, outerIv, outerCt, outerAad)

        if (mode != MODE_PASSWORD) return innerPayload to null

        if (password.isNullOrEmpty()) throw SecurityException("需要主密码解锁保险库")
        if (innerPayload.size < SALT_LEN + IV_LEN + 16) {
            throw IllegalArgumentException("Password vault payload truncated")
        }
        val salt = innerPayload.copyOfRange(0, SALT_LEN)
        val innerIv = innerPayload.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val innerCt = innerPayload.copyOfRange(SALT_LEN + IV_LEN, innerPayload.size)
        val keyBytes = CryptoUtils.pbkdf2Sha256(password, salt, PBKDF2_ITERS, 256)
        return try {
            val plain = try {
                CryptoUtils.aesGcmDecrypt(keyBytes, innerIv, innerCt, AAD_INNER)
            } catch (e: Exception) {
                throw SecurityException("主密码错误", e)
            }
            plain to CachedPasswordKey(salt, keyBytes)
        } finally {
            CryptoUtils.zero(keyBytes)
        }
    }

    private fun sealOuterV2(mode: Byte, innerPayload: ByteArray): ByteArray {
        val masterKey = masterKeyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(CryptoUtils.AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val outerIv = cipher.iv
        val header = buildHeader(VERSION_V2, mode, outerIv.size)
        cipher.updateAAD(buildOuterAadV2(header))
        val outerCt = cipher.doFinal(innerPayload)

        val out = ByteArrayOutputStream(HEADER_SIZE + outerIv.size + outerCt.size)
        out.write(header)
        out.write(outerIv)
        out.write(outerCt)
        return out.toByteArray()
    }

    private fun packInnerPayload(salt: ByteArray, iv: ByteArray, enc: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream(salt.size + iv.size + enc.size)
        buf.write(salt)
        buf.write(iv)
        buf.write(enc)
        return buf.toByteArray()
    }

    private fun buildHeader(version: Byte, mode: Byte, ivLen: Int): ByteArray = byteArrayOf(
        MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3],
        version,
        mode,
        ivLen.toByte()
    )

    private fun buildOuterAadV2(header: ByteArray): ByteArray {
        val out = ByteArray(AAD_OUTER_V2_PREFIX.size + header.size)
        System.arraycopy(AAD_OUTER_V2_PREFIX, 0, out, 0, AAD_OUTER_V2_PREFIX.size)
        System.arraycopy(header, 0, out, AAD_OUTER_V2_PREFIX.size, header.size)
        return out
    }

    private fun validateHeader(blob: ByteArray): Byte {
        if (blob.size < HEADER_SIZE) throw IllegalArgumentException("Vault file corrupted")
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) throw IllegalArgumentException("Invalid vault header")
        }
        val version = blob[4]
        if (version != VERSION_V1 && version != VERSION_V2) {
            throw IllegalArgumentException("Unsupported vault version: $version")
        }
        return version
    }

    companion object {
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), 'A'.code.toByte())
        const val VERSION_V1: Byte = 1
        const val VERSION_V2: Byte = 2
        const val MODE_KEYSTORE: Byte = 1
        const val MODE_PASSWORD: Byte = 2
        const val MODE_BIOMETRIC: Byte = 3
        private const val HEADER_SIZE = 7
        private const val SALT_LEN = 16
        private const val IV_LEN = 12
        private const val PBKDF2_ITERS = 200_000
        private val AAD_OUTER_V1 = "gssh-android-vault-v1".toByteArray(Charsets.UTF_8)
        private val AAD_OUTER_V2_PREFIX = "gssh-android-vault-v2:".toByteArray(Charsets.UTF_8)
        private val AAD_INNER = "gssh-android-vault-pw-v1".toByteArray(Charsets.UTF_8)
    }
}
