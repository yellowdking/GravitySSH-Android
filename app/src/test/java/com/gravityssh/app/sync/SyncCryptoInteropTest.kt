package com.gravityssh.app.sync

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.vault.VaultCrypto
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class SyncCryptoInteropTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun decryptWindowsGoldenVectorByteForByte() {
        val code = "K7M9-P4X2"
        val canonical = SyncCrypto.canonicalizeCode(code)
        assertEquals("K7M9P4X2", canonical)
        assertEquals("gssh-sync://v1?code=K7M9-P4X2", SyncCrypto.buildQrUri(code))
        assertEquals("K7M9P4X2", SyncCrypto.canonicalizeCode("gssh-sync://v1?code=k7m9-p4x2"))
        assertEquals("K7M9P4X2", SyncCrypto.canonicalizeCode("\tK7M9-\tP4X2 \r\n"))

        val channelId = SyncCrypto.deriveChannelId(code)
        assertEquals(
            "dda9c60ab5d048fe1299e0815ff885d13047578a81a4b6969b676820a0371c7e",
            channelId
        )

        val saltB64 = "AAECAwQFBgcICQoLDA0ODw=="
        val salt = Base64.getDecoder().decode(saltB64)
        val aesKeyHex = SyncCrypto.bytesToHex(SyncCrypto.deriveAesKey(canonical, salt))
        assertEquals(
            "b91a0aab1e53ea797042d28deb026dc6c4264ede7a76ea37cdaaf9e224eaa936",
            aesKeyHex
        )

        val nonceB64 = "oKGio6Slpqeoqaqr"
        val goldenCt = "APaRyQ4BbxId1Z8T80df15PY5T5ZivUIHTYORE54hf1vt0vs0HgZTp+kdW6T6LzODHRmdQ4H3x9CJ0eFuC9YFTKgpfjz/sPZ5ewF/vdoTWnz/YvillbpEL53eFwEy3CwlqW5yJPTaG5yU39gILQs2ZhRDj9uptJe7iR4GrTAI/dY6EC1aVHf+v7nwp9agPRGG5le1FEvmps/VvI3PYG/k4/DuGjBqprb1KMdZB/56Zqkfd3qwobBkyPWlHLhUbiNaYPBsfXOf93p79cau5jYfFRCSbxp+W+0gbLdnvb1M2RtniB3r4z4nQABfkK/HZABs+FRK16PQUvruP0GUkzRoF/FviFW1iWn6HOY9JDBkmOvd7fYvyWjpnfM1B76Bd3Mf7H4XUZJ1Odqyjlky/rZx+4V2vjnx3utCZmLNBKWSfUU+GBQdo7NhDfT6BPP8T66cGezEtofJ6xYUwp2fS8Eu7zoAuWefxGTgAzVwNTc79WdvbpOCip3eiKIRUSdPAQH+N0cNLo00zI/kEHFQkx0iuRwWVMcO5TW3ks8yJvvBjXIu9nULelVYYTwz/p+WoJfikIgavX3Vm9Kcof9Cw=="

        val bundle = SyncCrypto.decryptBundle(saltB64, nonceB64, goldenCt, code)
        assertEquals(1, bundle.schemaVersion)
        assertEquals("2026-01-02T03:04:05Z", bundle.exportedAt)
        assertEquals("windows", bundle.sourcePlatform)
        assertEquals("WIN-DEV-01", bundle.sourceDevice)
        assertEquals(1, bundle.servers.size)
        assertEquals("srv-golden-1", bundle.servers[0].id)
        assertEquals("192.0.2.10", bundle.servers[0].host)
        assertEquals(22, bundle.servers[0].port)
        assertEquals("root", bundle.servers[0].username)
        assertEquals(AuthType.PASSWORD, bundle.servers[0].authType)
        assertEquals("golden-pass-!@#", bundle.servers[0].password)
        assertEquals(1, bundle.shortcuts.size)
        assertEquals("sc-golden-1", bundle.shortcuts[0].id)
        assertEquals("Disk", bundle.shortcuts[0].name)
        assertEquals("df -h", bundle.shortcuts[0].command)
    }

    @Test
    fun appGateHmacMatchesWindowsGoldenVector() {
        val sig = SyncCrypto.signAppGate(
            appGateKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            method = "POST",
            path = "/v1/pickup",
            timestampSec = 1767323045L,
            rawBody = """{"channelId":"abc"}""".toByteArray(Charsets.UTF_8)
        )
        assertEquals("02e518b092e36f3a78d7e0f782bfa85a186171b74bf062b9f3d10c90a35cba5c", sig)
    }

    @Test
    fun encryptDecryptRoundTripAndValidationErrors() {
        val code = SyncCrypto.generateSyncCode()
        assertTrue(code.matches(Regex("^[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$")))
        val input = SyncBundle(
            schemaVersion = 1,
            exportedAt = "2026-11-26T10:00:00Z",
            sourcePlatform = "android",
            sourceDevice = "Pixel",
            servers = listOf(ServerConfig(id = "s1", name = "Prod", host = "10.0.0.1", port = 22, username = "root")),
            shortcuts = listOf(CommandShortcut(id = "c1", name = "Uptime", command = "uptime"))
        )
        val blob = SyncCrypto.encryptBundle(input, code)
        val decoded = SyncCrypto.decryptBundle(blob.saltB64, blob.nonceB64, blob.ciphertextB64, code)
        assertEquals(input, decoded)

        // Wrong code must throw SecurityException with Chinese message
        val wrongCode = if (code == "K7M9-P4X2") "M2N3-P4Q5" else "K7M9-P4X2"
        val badTagErr = runCatching {
            SyncCrypto.decryptBundle(blob.saltB64, blob.nonceB64, blob.ciphertextB64, wrongCode)
        }.exceptionOrNull()
        assertTrue(badTagErr is SecurityException)
        assertTrue(badTagErr!!.message.orEmpty().contains("解密失败"))

        // Short salt / nonce / ciphertext must fail fast with IllegalArgumentException
        val shortB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { SyncCrypto.decryptRawJson(shortB64, blob.nonceB64, blob.ciphertextB64, code) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptRawJson(blob.saltB64, shortB64, blob.ciphertextB64, code) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptRawJson(blob.saltB64, blob.nonceB64, shortB64, code) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun normalizeRelayUrlPreservesPathAndDetectsRawIpHost() {
        assertEquals("", SyncConfig.normalizeRelayUrl("   "))
        assertEquals("https://1.2.3.4:54843", SyncConfig.normalizeRelayUrl("1.2.3.4:54843"))
        assertEquals("https://1.2.3.4:54843", SyncConfig.normalizeRelayUrl("https://1.2.3.4:54843/"))
        assertEquals("https://sync.example.com", SyncConfig.normalizeRelayUrl("sync.example.com"))
        assertEquals("https://sync.example.com:8443", SyncConfig.normalizeRelayUrl("sync.example.com:8443"))
        assertEquals("https://sync.example.com/relay", SyncConfig.normalizeRelayUrl("https://sync.example.com/relay/"))
        assertEquals("https://sync.example.com:8443/api/sync", SyncConfig.normalizeRelayUrl("sync.example.com:8443/api/sync/"))
        assertTrue(SyncConfig.isRawIpHost("107.172.98.169"))
        assertTrue(SyncConfig.isRawIpHost("[2001:db8::1]"))
        assertFalse(SyncConfig.isRawIpHost("sync.example.com"))
        assertTrue(runCatching { SyncConfig.normalizeRelayUrl("http://1.2.3.4:54843") }.isFailure)
    }

    @Test
    fun syncServiceChecksLockCapsPreviewsAndPreservesPreviewOnImportFailure() = runTest {
        val repo = VaultRepository(File(tempFolder.root, "vault.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        assertTrue(repo.setMasterPassword("", "pw123").isSuccess)

        val pickupCalls = AtomicInteger(0)
        var nextBundle = SyncBundle(
            schemaVersion = 1,
            servers = listOf(ServerConfig(id = "s1", name = "S1", host = "10.0.0.1"))
        )
        val fakeRelay = object : SyncRelayClient() {
            override suspend fun pickup(relayUrl: String, channelId: String): PickupResponse {
                pickupCalls.incrementAndGet()
                val blob = SyncCrypto.encryptBundle(nextBundle, "K7M9-P4X2")
                return PickupResponse(blob.saltB64, blob.nonceB64, blob.ciphertextB64)
            }
        }
        val service = SyncService(repo, fakeRelay)

        // Locked vault must reject previewReceive BEFORE touching relay
        repo.lockNow()
        val lockedErr = runCatching { service.previewReceive("K7M9-P4X2") }.exceptionOrNull()
        assertTrue(lockedErr is SecurityException)
        assertEquals(0, pickupCalls.get())

        assertTrue(repo.unlock("pw123").isSuccess)

        // Preview cache capped at 2 entries
        val p1 = service.previewReceive("K7M9-P4X2")
        val p2 = service.previewReceive("K7M9-P4X2")
        val p3 = service.previewReceive("K7M9-P4X2")
        assertTrue(runCatching { service.applyReceive(p1.previewId, SyncImportMode.MERGE) }.isFailure)

        // Lock clears remaining previews
        repo.lockNow()
        assertTrue(repo.unlock("pw123").isSuccess)
        assertTrue(runCatching { service.applyReceive(p2.previewId, SyncImportMode.MERGE) }.isFailure)
        assertTrue(runCatching { service.applyReceive(p3.previewId, SyncImportMode.MERGE) }.isFailure)

        // Preview is retained if import fails (schemaVersion != 1), and removed once import succeeds
        nextBundle = SyncBundle(schemaVersion = 99)
        val badPreview = service.previewReceive("K7M9-P4X2")
        val badImport = runCatching { service.applyReceive(badPreview.previewId, SyncImportMode.MERGE) }.exceptionOrNull()
        assertNotNull(badImport)
        assertTrue(badImport is IllegalArgumentException)
        // Still cached, so a second attempt fails for schemaVersion (not "已过期")
        val secondErr = runCatching { service.applyReceive(badPreview.previewId, SyncImportMode.MERGE) }.exceptionOrNull()
        assertTrue(secondErr is IllegalArgumentException)

        nextBundle = SyncBundle(schemaVersion = 1, servers = listOf(ServerConfig(id = "s-ok", name = "OK", host = "10.0.0.5")))
        val okPreview = service.previewReceive("K7M9-P4X2")
        val sum = service.applyReceive(okPreview.previewId, SyncImportMode.MERGE)
        assertEquals(1, sum.serversAdded)
        // Consumed after success
        assertTrue(runCatching { service.applyReceive(okPreview.previewId, SyncImportMode.MERGE) }.exceptionOrNull() is IllegalStateException)
    }
}
