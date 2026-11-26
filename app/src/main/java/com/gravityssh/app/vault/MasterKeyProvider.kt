package com.gravityssh.app.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

interface MasterKeyProvider {
    fun getOrCreateKey(): SecretKey
}

class AndroidKeystoreMasterKeyProvider(
    private val alias: String = DEFAULT_ALIAS
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

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "GravitySSHVaultMasterKey"
    }
}

class InMemoryMasterKeyProvider(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : MasterKeyProvider {
    private val key = SecretKeySpec(keyBytes.copyOf(32), "AES")
    override fun getOrCreateKey(): SecretKey = key
}
