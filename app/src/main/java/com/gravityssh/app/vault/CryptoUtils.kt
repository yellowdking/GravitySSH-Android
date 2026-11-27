package com.gravityssh.app.vault

import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object CryptoUtils {
    const val AES_GCM = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128

    fun pbkdf2Sha256(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyBits: Int = 256
    ): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, keyBits)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            zero(chars)
        }
    }

    fun aesGcmEncrypt(
        key: SecretKey,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmEncrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray = aesGcmEncrypt(SecretKeySpec(keyBytes, "AES"), iv, plaintext, aad)

    fun aesGcmDecrypt(
        key: SecretKey,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun aesGcmDecrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray = aesGcmDecrypt(SecretKeySpec(keyBytes, "AES"), iv, ciphertext, aad)

    fun zero(bytes: ByteArray?) {
        if (bytes != null) Arrays.fill(bytes, 0.toByte())
    }

    fun zero(chars: CharArray?) {
        if (chars != null) Arrays.fill(chars, '\u0000')
    }
}
