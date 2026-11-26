package com.gravityssh.app.vault

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultCrypto(
    private val masterKeyProvider: MasterKeyProvider = AndroidKeystoreMasterKeyProvider()
) {
    private val random = SecureRandom()

    fun isPasswordProtected(blob: ByteArray): Boolean = readMode(blob) == MODE_PASSWORD

    fun isBiometricLocked(blob: ByteArray): Boolean = readMode(blob) == MODE_BIOMETRIC

    fun readMode(blob: ByteArray): Byte {
        if (blob.size < HEADER_SIZE) throw IllegalArgumentException("Vault file corrupted")
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) throw IllegalArgumentException("Invalid vault header")
        }
        return blob[5]
    }

    fun seal(plaintext: ByteArray, password: String? = null, biometricLock: Boolean = false): ByteArray {
        val (mode, innerPayload) = if (!password.isNullOrEmpty()) {
            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val pwKey = derivePasswordKey(password, salt)
            val enc = aesGcmEncrypt(pwKey, iv, plaintext, AAD_INNER)
            val buf = ByteArrayOutputStream(SALT_LEN + IV_LEN + enc.size)
            buf.write(salt)
            buf.write(iv)
            buf.write(enc)
            MODE_PASSWORD to buf.toByteArray()
        } else {
            val m = if (biometricLock) MODE_BIOMETRIC else MODE_KEYSTORE
            m to plaintext
        }

        val masterKey = masterKeyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        cipher.updateAAD(AAD_OUTER)
        val outerIv = cipher.iv
        val outerCt = cipher.doFinal(innerPayload)

        val out = ByteArrayOutputStream(HEADER_SIZE + outerIv.size + outerCt.size)
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(mode.toInt())
        out.write(outerIv.size)
        out.write(outerIv)
        out.write(outerCt)
        return out.toByteArray()
    }

    fun open(blob: ByteArray, password: String? = null): ByteArray {
        val mode = readMode(blob)
        val ivLen = blob[6].toInt() and 0xFF
        if (blob.size < HEADER_SIZE + ivLen + 16) {
            throw IllegalArgumentException("Vault payload truncated")
        }
        val outerIv = blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivLen)
        val outerCt = blob.copyOfRange(HEADER_SIZE + ivLen, blob.size)
        val masterKey = masterKeyProvider.getOrCreateKey()
        val innerPayload = aesGcmDecrypt(masterKey, outerIv, outerCt, AAD_OUTER)

        if (mode != MODE_PASSWORD) return innerPayload

        if (password.isNullOrEmpty()) throw SecurityException("需要主密码解锁保险库")
        if (innerPayload.size < SALT_LEN + IV_LEN + 16) {
            throw IllegalArgumentException("Password vault payload truncated")
        }
        val salt = innerPayload.copyOfRange(0, SALT_LEN)
        val innerIv = innerPayload.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val innerCt = innerPayload.copyOfRange(SALT_LEN + IV_LEN, innerPayload.size)
        val pwKey = derivePasswordKey(password, salt)
        return try {
            aesGcmDecrypt(pwKey, innerIv, innerCt, AAD_INNER)
        } catch (e: Exception) {
            throw SecurityException("主密码错误", e)
        }
    }

    private fun derivePasswordKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun aesGcmEncrypt(key: SecretKey, iv: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: SecretKey, iv: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    companion object {
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), 'A'.code.toByte())
        private const val VERSION: Byte = 1
        const val MODE_KEYSTORE: Byte = 1
        const val MODE_PASSWORD: Byte = 2
        const val MODE_BIOMETRIC: Byte = 3
        private const val HEADER_SIZE = 7
        private const val SALT_LEN = 16
        private const val IV_LEN = 12
        private const val PBKDF2_ITERS = 200_000
        private const val AES_GCM = "AES/GCM/NoPadding"
        private val AAD_OUTER = "gssh-android-vault-v1".toByteArray(Charsets.UTF_8)
        private val AAD_INNER = "gssh-android-vault-pw-v1".toByteArray(Charsets.UTF_8)
    }
}
