package com.gravityssh.app.sync

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.PairRequestPayload
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncReceivePollState
import com.gravityssh.app.model.SyncSendPollState
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

    @Test
    fun decryptWindowsV2DualGoldenVectorsByteForByte() {
        val sendCode = "K7M9-P4X2"
        val recvCode = "M8N2-Q5R7"
        val canonicalSend = SyncCrypto.canonicalizeCode(sendCode)
        val canonicalRecv = SyncCrypto.canonicalizeCode(recvCode)
        assertEquals("K7M9P4X2", canonicalSend)
        assertEquals("M8N2Q5R7", canonicalRecv)

        val channelId = SyncCrypto.deriveChannelId(sendCode)
        val requestId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals("dda9c60ab5d048fe1299e0815ff885d13047578a81a4b6969b676820a0371c7e", channelId)

        val dualSecret = SyncCrypto.deriveDualSecret(sendCode, recvCode)
        assertEquals("K7M9P4X2:M8N2Q5R7", dualSecret)

        val dualAad = SyncCrypto.deriveDualAad(channelId, requestId, canonicalRecv)
        assertEquals(
            "dda9c60ab5d048fe1299e0815ff885d13047578a81a4b6969b676820a0371c7e:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:M8N2Q5R7",
            dualAad
        )

        val saltB64 = "AAECAwQFBgcICQoLDA0ODw=="
        val nonceB64 = "oKGio6Slpqeoqaqr"
        val salt = Base64.getDecoder().decode(saltB64)
        val nonce = Base64.getDecoder().decode(nonceB64)
        val dualAesKeyHex = SyncCrypto.bytesToHex(SyncCrypto.deriveAesKey(dualSecret, salt))
        assertEquals(
            "1260b54d306f1fafd1cc9269c1bdbf28f3b5e0c781839dc38ad212d0c354a14a",
            dualAesKeyHex
        )

        val pairReqCiphertextB64 =
            "APaRyQ4BbxId1TM4rqq7naFWq7TYtpQZiLMxHR6ePklgv0iWXAgj/hxwBD9GmA1hehr88Gil2CwJry5gLkQGVuT7M4K76ftGg0q0JAakKlGMpPU90qXFkR88vNkqjuijkHPuSc4JWjpmribIQ95TdYF/6rIrLLFJt0/jlsXobDYUh7frQiGgkHEFcAeJWFHnbKmoAbh4I742hFtVbfOWbyyT"
        val decodedReq = SyncCrypto.decryptPairRequest(sendCode, saltB64, nonceB64, pairReqCiphertextB64)
        assertEquals("M8N2-Q5R7", decodedReq.receiverCode)
        assertEquals("Pixel-9", decodedReq.receiverDevice)
        assertEquals("android", decodedReq.receiverPlatform)
        assertEquals("2026-01-02T03:04:05Z", decodedReq.requestedAt)

        // Deterministic encryptPairRequest round-trip
        val reEncReq = SyncCrypto.encryptPairRequest(sendCode, decodedReq, fixedSalt = salt, fixedNonce = nonce)
        assertEquals(channelId, reEncReq.channelId)
        assertEquals(saltB64, reEncReq.saltB64)
        assertEquals(nonceB64, reEncReq.nonceB64)
        assertEquals(decodedReq, SyncCrypto.decryptPairRequest(sendCode, reEncReq.saltB64, reEncReq.nonceB64, reEncReq.ciphertextB64))

        val dualBundleCiphertextB64 =
            "vzp6gCf3XAQ6HkWPOPwgdA3P8OZ/5+Z0UtAOnfbhwtJlUxLS0SR9gz7kcmUaC8MuWVLXvVoWQtEntbvDuLkQw/ZH7K8StuM5j6Iv2+SiywqyPD3I+/2MFKIPssjBGJ3Acl0mG4f/BlCZl7FQg8u22EI4YlgHNDqJpehpbdBoAS6eSH++HspnBHiAnbAPDHpFZL2wE1c0/2IfrfdFpjNvdUWR2z4Tbw99ZiUoiujrR77kuNUEDoSUz10H1p1Si3VgrTt4wrrZoVID7NRgyD6WQf8p45KNG6c4nxtnLd9slkCn0cPzxGz+zgA+LfJc8Ndt9Ef9KkJeNIQkPgp98tCwE6gBWGYo14cdazo97J01TyJr/1qzrL7+oPhz3rqTXeweLCXN1ewVc188oG3/Q8Tj8IXyMCoLc1F8S8nsqs8nKfxhPVIhn9BAwL1Bm1Y8nhSv7Bgoz2SryJnLkkv+IvKDfz4HKCOfNowjQYBgiMgdYccXTqpj2k84qkeO01AcaKb3u4YCbaTl6zpJ1aC34ol1CZhdbtHZnsb+U9K561XccWsvPdjBRb0IvyCZlS8XGzNtPWFk0/Ci5VdGJaxLDw=="
        val decodedBundle = SyncCrypto.decryptBundleDual(
            saltB64 = saltB64,
            nonceB64 = nonceB64,
            ctB64 = dualBundleCiphertextB64,
            sendCode = sendCode,
            recvCode = recvCode,
            requestId = requestId
        )
        assertEquals(1, decodedBundle.schemaVersion)
        assertEquals("2026-01-02T03:04:05Z", decodedBundle.exportedAt)
        assertEquals("windows", decodedBundle.sourcePlatform)
        assertEquals("WIN-DEV-01", decodedBundle.sourceDevice)
        assertEquals(1, decodedBundle.servers.size)
        assertEquals("srv-golden-1", decodedBundle.servers[0].id)
        assertEquals("192.0.2.10", decodedBundle.servers[0].host)
        assertEquals("golden-pass-!@#", decodedBundle.servers[0].password)
        assertEquals(1, decodedBundle.shortcuts.size)
        assertEquals("sc-golden-1", decodedBundle.shortcuts[0].id)

        // Deterministic encryptBundleDual round-trip
        val reEncDual = SyncCrypto.encryptBundleDual(decodedBundle, sendCode, recvCode, requestId, fixedSalt = salt, fixedNonce = nonce)
        assertEquals(channelId, reEncDual.channelId)
        assertEquals(saltB64, reEncDual.saltB64)
        assertEquals(nonceB64, reEncDual.nonceB64)
        assertEquals(
            decodedBundle,
            SyncCrypto.decryptBundleDual(reEncDual.saltB64, reEncDual.nonceB64, reEncDual.ciphertextB64, sendCode, recvCode, requestId)
        )
    }

    @Test
    fun v2DualKeyCryptoAndPairRequestRoundTrip() {
        val tok = SyncCrypto.generateHexToken32()
        assertTrue(tok.matches(Regex("^[0-9a-f]{64}$")))

        val sendCode = "K7M9-P4X2"
        val recvCode = "R3T8-W5Y6"
        val channelId = SyncCrypto.deriveChannelId(sendCode)
        val requestId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        assertEquals("K7M9P4X2:R3T8W5Y6", SyncCrypto.deriveDualSecret(sendCode, recvCode))
        assertEquals(
            "$channelId:$requestId:R3T8W5Y6",
            SyncCrypto.deriveDualAad(channelId, requestId, SyncCrypto.canonicalizeCode(recvCode))
        )

        val reqInfo = PairRequestPayload(
            receiverCode = recvCode,
            receiverDevice = "Pixel 9",
            receiverPlatform = "android",
            requestedAt = "2026-11-27T11:00:00Z"
        )
        val reqBlob = SyncCrypto.encryptPairRequest(sendCode, reqInfo)
        val decodedReq = SyncCrypto.decryptPairRequest(sendCode, reqBlob.saltB64, reqBlob.nonceB64, reqBlob.ciphertextB64)
        assertEquals(reqInfo, decodedReq)
        assertTrue(
            runCatching {
                SyncCrypto.decryptPairRequest(recvCode, reqBlob.saltB64, reqBlob.nonceB64, reqBlob.ciphertextB64)
            }.exceptionOrNull() is SecurityException
        )

        val bundle = SyncBundle(
            schemaVersion = 1,
            exportedAt = "2026-11-27T11:01:00Z",
            sourcePlatform = "android",
            sourceDevice = "Pixel 9",
            servers = listOf(ServerConfig(id = "s-v2", name = "DualHost", host = "10.0.1.2", port = 22, username = "ubuntu")),
            shortcuts = listOf(CommandShortcut(id = "c-v2", name = "Logs", command = "journalctl -n 50"))
        )
        val dualBlob = SyncCrypto.encryptBundleDual(bundle, sendCode, recvCode, requestId)
        assertEquals(channelId, dualBlob.channelId)
        val decodedBundle = SyncCrypto.decryptBundleDual(
            dualBlob.saltB64,
            dualBlob.nonceB64,
            dualBlob.ciphertextB64,
            sendCode,
            recvCode,
            requestId
        )
        assertEquals(bundle, decodedBundle)

        // Wrong recvCode, wrong sendCode, or wrong requestId (AAD) must fail with SecurityException
        assertTrue(
            runCatching {
                SyncCrypto.decryptBundleDual(dualBlob.saltB64, dualBlob.nonceB64, dualBlob.ciphertextB64, sendCode, "2222-3333", requestId)
            }.exceptionOrNull() is SecurityException
        )
        assertTrue(
            runCatching {
                SyncCrypto.decryptBundleDual(dualBlob.saltB64, dualBlob.nonceB64, dualBlob.ciphertextB64, "2222-3333", recvCode, requestId)
            }.exceptionOrNull() is SecurityException
        )
        assertTrue(
            runCatching {
                SyncCrypto.decryptBundleDual(
                    dualBlob.saltB64,
                    dualBlob.nonceB64,
                    dualBlob.ciphertextB64,
                    sendCode,
                    recvCode,
                    "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
                )
            }.exceptionOrNull() is SecurityException
        )
    }

    @Test
    fun v2MutualPairingServiceFlowAndLockChecks() = runTest {
        val senderRepo = VaultRepository(File(tempFolder.root, "sender.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        senderRepo.open()
        assertTrue(senderRepo.setMasterPassword("", "pw123").isSuccess)
        senderRepo.saveServer(ServerConfig(id = "srv-1", name = "Prod", host = "192.168.1.10", username = "ops"))

        val receiverRepo = VaultRepository(File(tempFolder.root, "receiver.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        receiverRepo.open()
        assertTrue(receiverRepo.setMasterPassword("", "pw456").isSuccess)

        var slotState = ""
        var slotChannel = ""
        var slotToken = ""
        var slotRequestId = ""
        var reqSalt = ""
        var reqNonce = ""
        var reqCt = ""
        var appSalt = ""
        var appNonce = ""
        var appCt = ""
        val pairRequestCalls = AtomicInteger(0)
        val dropCalls = AtomicInteger(0)

        val relay = object : SyncRelayClient() {
            override suspend fun drop(relayUrl: String, req: DropRequest): DropResponse {
                dropCalls.incrementAndGet()
                return DropResponse(ok = true)
            }

            override suspend fun pairInit(relayUrl: String, channelId: String, senderToken: String, ttlSeconds: Int): PairInitResponse {
                slotState = "waiting"
                slotChannel = channelId
                slotToken = senderToken
                return PairInitResponse(ok = true, expiresAt = "2099-01-01T00:00:00Z")
            }

            override suspend fun pairRequest(
                relayUrl: String,
                channelId: String,
                requestId: String,
                salt: String,
                nonce: String,
                ciphertext: String
            ): PairRequestResponse {
                pairRequestCalls.incrementAndGet()
                assertEquals(slotChannel, channelId)
                slotState = "requested"
                slotRequestId = requestId
                reqSalt = salt
                reqNonce = nonce
                reqCt = ciphertext
                return PairRequestResponse(ok = true, expiresAt = "2099-01-01T00:00:00Z")
            }

            override suspend fun pairPoll(relayUrl: String, channelId: String, senderToken: String): PairPollResponse {
                assertEquals(slotChannel, channelId)
                assertEquals(slotToken, senderToken)
                return when (slotState) {
                    "waiting" -> PairPollResponse(state = "waiting")
                    "requested" -> PairPollResponse(state = "requested", requestId = slotRequestId, salt = reqSalt, nonce = reqNonce, ciphertext = reqCt)
                    "approved" -> PairPollResponse(state = "approved", requestId = slotRequestId)
                    "rejected" -> PairPollResponse(state = "rejected", requestId = slotRequestId)
                    else -> PairPollResponse(state = "gone")
                }
            }

            override suspend fun pairApprove(
                relayUrl: String,
                channelId: String,
                senderToken: String,
                requestId: String,
                salt: String,
                nonce: String,
                ciphertext: String
            ) {
                assertEquals(slotChannel, channelId)
                assertEquals(slotToken, senderToken)
                assertEquals(slotRequestId, requestId)
                slotState = "approved"
                appSalt = salt
                appNonce = nonce
                appCt = ciphertext
            }

            override suspend fun pairReject(relayUrl: String, channelId: String, senderToken: String, requestId: String) {
                if (slotState.isEmpty()) throw RelayGoneException("gone")
                slotState = "rejected"
            }

            override suspend fun pairStatus(relayUrl: String, channelId: String, requestId: String): PairStatusResponse {
                if (slotState.isEmpty()) throw RelayGoneException("gone")
                assertEquals(slotChannel, channelId)
                assertEquals(slotRequestId, requestId)
                return when (slotState) {
                    "waiting", "requested" -> PairStatusResponse(state = "pending")
                    "rejected" -> {
                        slotState = ""
                        PairStatusResponse(state = "rejected")
                    }
                    "approved" -> {
                        slotState = "" // burn after read
                        PairStatusResponse(state = "approved", salt = appSalt, nonce = appNonce, ciphertext = appCt)
                    }
                    else -> throw RelayGoneException("gone")
                }
            }

            override suspend fun pairCancel(relayUrl: String, channelId: String, senderToken: String) {
                if (slotState.isEmpty()) throw RelayGoneException("gone")
                slotState = ""
            }
        }

        val senderService = SyncService(senderRepo, relay)
        val receiverService = SyncService(receiverRepo, relay)

        // 1. Locked receiver must fail BEFORE touching relay
        receiverRepo.lockNow()
        val recvCode = receiverService.generateReceiverCode()
        assertTrue(runCatching { receiverService.startReceiveRequest("K7M9-P4X2", recvCode) }.exceptionOrNull() is SecurityException)
        assertEquals(0, pairRequestCalls.get())
        assertTrue(receiverRepo.unlock("pw456").isSuccess)

        // 2. Sender starts v2 send -> ZERO dropCalls (no vault payload uploaded before verification!)
        val sendSess = senderService.startSend()
        assertEquals(0, dropCalls.get())
        assertTrue(sendSess.sessionId.isNotBlank())
        assertEquals(SyncSendPollState.WAITING, senderService.pollSend(sendSess.sessionId).state)

        // 3. Receiver submits request; both poll -> sender sees receiverCode, receiver sees PENDING
        val waitSess = receiverService.startReceiveRequest(sendSess.qrText, recvCode)
        assertEquals(SyncReceivePollState.PENDING, receiverService.pollReceiveStatus(waitSess.waitId).state)

        val pollReq = senderService.pollSend(sendSess.sessionId)
        assertEquals(SyncSendPollState.REQUESTED, pollReq.state)
        assertEquals(recvCode, pollReq.receiverCode)
        assertEquals("android", pollReq.receiverPlatform)
        assertEquals(waitSess.requestId, pollReq.requestId)

        // 4. Sender approves -> receiver pulls and burns -> sender sees CONSUMED
        senderService.approveSend(sendSess.sessionId, pollReq.requestId, pollReq.receiverCode)
        assertEquals(SyncSendPollState.APPROVED, senderService.pollSend(sendSess.sessionId).state)

        val recvDone = receiverService.pollReceiveStatus(waitSess.waitId)
        assertEquals(SyncReceivePollState.APPROVED, recvDone.state)
        assertNotNull(recvDone.preview)
        assertEquals(1, recvDone.preview!!.serverCount)

        assertEquals(SyncSendPollState.CONSUMED, senderService.pollSend(sendSess.sessionId).state)

        // 5. Receiver imports preview
        val summary = receiverService.applyReceive(recvDone.preview!!.previewId, SyncImportMode.MERGE)
        assertEquals(1, summary.serversAdded)

        // 6. Rejection flow & auto-reject on corrupted PairRequest
        val sendSess2 = senderService.startSend()
        val waitSess2 = receiverService.startReceiveRequest(sendSess2.code, receiverService.generateReceiverCode())
        val pollReq2 = senderService.pollSend(sendSess2.sessionId)
        senderService.rejectSend(sendSess2.sessionId, pollReq2.requestId)
        assertEquals(SyncReceivePollState.REJECTED, receiverService.pollReceiveStatus(waitSess2.waitId).state)

        val sendSess3 = senderService.startSend()
        receiverService.startReceiveRequest(sendSess3.code, receiverService.generateReceiverCode())
        reqCt = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" // corrupt pair request ciphertext
        val errPoll = senderService.pollSend(sendSess3.sessionId)
        assertEquals(SyncSendPollState.ERROR, errPoll.state)
        assertEquals("rejected", slotState)

        // 7. cancelSend with unknown/expired UUID and cancelSend/rejectSend on RelayGoneException must not throw
        senderService.cancelSend("00000000-0000-0000-0000-000000000000")
        val sendSess4 = senderService.startSend()
        slotState = "" // simulate expired/consumed on relay
        senderService.cancelSend(sendSess4.sessionId)
        val sendSess5 = senderService.startSend()
        slotState = "" // simulate expired/consumed on relay
        senderService.rejectSend(sendSess5.sessionId, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
    }
}
