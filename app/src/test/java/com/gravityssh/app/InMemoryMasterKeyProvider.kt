package com.gravityssh.app

import com.gravityssh.app.vault.MasterKeyProvider
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class InMemoryMasterKeyProvider(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : MasterKeyProvider {
    private val key = SecretKeySpec(keyBytes.copyOf(32), "AES")
    override fun getOrCreateKey(): SecretKey = key
}
