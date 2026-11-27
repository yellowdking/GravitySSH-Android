package com.gravityssh.app.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface MasterKeyProvider {
    fun getOrCreateKey(): SecretKey
    fun createBiometricAuthCipher(): Cipher? = null
    fun resetBiometricAuthKey() {}
}

class AndroidKeystoreMasterKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
    private val biometricAlias: String = BIOMETRIC_AUTH_ALIAS
) : MasterKeyProvider {
    override fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    /**
     * Creates an AES-256-GCM Cipher initialized with a user-authentication-required Keystore key
     * for binding BiometricPrompt to a hardware-backed CryptoObject.
     *
     * Note: The vault outer master key ([DEFAULT_ALIAS]) remains separate so existing vaults
     * and P0-2 device-credential fallback / password unlock continue working without data loss.
     */
    override fun createBiometricAuthCipher(): Cipher? {
        return try {
            initBiometricCipher(getOrCreateBiometricAuthKey())
        } catch (_: KeyPermanentlyInvalidatedException) {
            resetBiometricAuthKey()
            runCatching { initBiometricCipher(getOrCreateBiometricAuthKey()) }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    override fun resetBiometricAuthKey() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(biometricAlias)) {
                ks.deleteEntry(biometricAlias)
            }
        }
    }

    private fun initBiometricCipher(key: SecretKey): Cipher {
        val cipher = Cipher.getInstance(CryptoUtils.AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    private fun getOrCreateBiometricAuthKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(biometricAlias, null) as? SecretKey
        if (existing != null) return existing

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            biometricAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        gen.init(builder.build())
        return gen.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "GravitySSHVaultMasterKey"
        const val BIOMETRIC_AUTH_ALIAS = "GravitySSHVaultBiometricAuthKey"
    }
}
