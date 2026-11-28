package com.gravityssh.app

import com.gravityssh.app.vault.MasterKeyProvider
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class InMemoryMasterKeyProvider(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : MasterKeyProvider {
    @Volatile
    private var key: SecretKey = SecretKeySpec(keyBytes.copyOf(32), "AES")

    val resetMasterKeyCalls = AtomicInteger(0)
    val resetBiometricKeyCalls = AtomicInteger(0)

    override fun getOrCreateKey(): SecretKey = key

    override fun resetMasterKey() {
        resetMasterKeyCalls.incrementAndGet()
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        key = SecretKeySpec(fresh, "AES")
    }

    override fun resetBiometricAuthKey() {
        resetBiometricKeyCalls.incrementAndGet()
    }
}
