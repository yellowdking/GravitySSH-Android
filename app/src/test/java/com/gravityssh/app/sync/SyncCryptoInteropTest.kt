package com.gravityssh.app.sync

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.JumpHostConfig
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.PairRequestPayload
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncReceivePollState
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncSendPollState
import com.gravityssh.app.vault.VaultCrypto
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.security.auth.x500.X500Principal

class SyncCryptoInteropTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun codeCanonicalizationQrUriAndValidationErrors() {
        val code = "K7M9-P4X2"
        val canonical = SyncCrypto.canonicalizeCode(code)
        assertEquals("K7M9P4X2", canonical)
        assertEquals("gssh-sync://v1?code=K7M9-P4X2", SyncCrypto.buildQrUri(code))
        assertEquals("K7M9P4X2", SyncCrypto.canonicalizeCode("gssh-sync://v1?code=k7m9-p4x2"))
        assertEquals("K7M9P4X2", SyncCrypto.canonicalizeCode("\tK7M9-\tP4X2 \r\n"))
        assertEquals(
            "dda9c60ab5d048fe1299e0815ff885d13047578a81a4b6969b676820a0371c7e",
            SyncCrypto.deriveChannelId(code)
        )

        val generated = SyncCrypto.generateSyncCode()
        assertTrue(generated.matches(Regex("^[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$")))

        val recvCode = "R3T8-W5Y6"
        val reqId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val reqBlob = SyncCrypto.encryptPairRequest(
            code,
            PairRequestPayload(receiverCode = recvCode, receiverDevice = "Pixel", requestedAt = "2026-11-27T10:00:00Z")
        )
        val dualBlob = SyncCrypto.encryptBundleDual(
            SyncBundle(schemaVersion = 1, servers = listOf(ServerConfig(id = "s1", name = "Prod", host = "10.0.0.1"))),
            code,
            recvCode,
            reqId
        )

        val shortB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { SyncCrypto.decryptPairRequest(code, shortB64, reqBlob.nonceB64, reqBlob.ciphertextB64) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptPairRequest(code, reqBlob.saltB64, shortB64, reqBlob.ciphertextB64) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptPairRequest(code, reqBlob.saltB64, reqBlob.nonceB64, shortB64) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptBundleDual(shortB64, dualBlob.nonceB64, dualBlob.ciphertextB64, code, recvCode, reqId) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptBundleDual(dualBlob.saltB64, shortB64, dualBlob.ciphertextB64, code, recvCode, reqId) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncCrypto.decryptBundleDual(dualBlob.saltB64, dualBlob.nonceB64, shortB64, code, recvCode, reqId) }.exceptionOrNull() is IllegalArgumentException)
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
    fun normalizeRelayUrlPreservesPathAndDetectsRawIpHost() {
        assertEquals("", SyncConfig.normalizeRelayUrl("   "))
        assertEquals("https://1.2.3.4:54843", SyncConfig.normalizeRelayUrl("1.2.3.4:54843"))
        assertEquals("https://1.2.3.4:54843", SyncConfig.normalizeRelayUrl("https://1.2.3.4:54843/"))
        assertEquals("https://sync.example.com", SyncConfig.normalizeRelayUrl("sync.example.com"))
        assertEquals("https://sync.example.com:8443", SyncConfig.normalizeRelayUrl("sync.example.com:8443"))
        assertEquals("https://sync.example.com/relay", SyncConfig.normalizeRelayUrl("https://sync.example.com/relay/"))
        assertEquals("https://sync.example.com:8443/api/sync", SyncConfig.normalizeRelayUrl("sync.example.com:8443/api/sync/"))
        assertTrue(SyncConfig.isRawIpHost("43.155.231.14"))
        assertTrue(SyncConfig.isRawIpHost("[2001:db8::1]"))
        assertFalse(SyncConfig.isRawIpHost("sync.example.com"))
        assertTrue(runCatching { SyncConfig.normalizeRelayUrl("http://1.2.3.4:54843") }.isFailure)
    }

    @Test
    fun syncServiceChecksLockCapsPreviewsAndPreservesPreviewOnImportFailure() = runTest {
        val repo = VaultRepository(File(tempFolder.root, "vault.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        assertTrue(repo.setMasterPassword("", "pw123").isSuccess)
        val pairStatusCalls = AtomicInteger(0)
        var nextBundle = SyncBundle(
            schemaVersion = 1,
            servers = listOf(ServerConfig(id = "s1", name = "S1", host = "10.0.0.1"))
        )
        val sendCode = "K7M9-P4X2"
        val recvCode = "M8N2-Q5R7"
        val channelId = SyncCrypto.deriveChannelId(sendCode)
        val fakeRelay = object : SyncRelayClient() {
            override suspend fun pairRequest(
                relayUrl: String,
                channelId: String,
                requestId: String,
                salt: String,
                nonce: String,
                ciphertext: String
            ): PairRequestResponse = PairRequestResponse(ok = true, expiresAt = "2099-01-01T00:00:00Z")

            override suspend fun pairStatus(
                relayUrl: String,
                channelId: String,
                requestId: String
            ): PairStatusResponse {
                pairStatusCalls.incrementAndGet()
                val blob = SyncCrypto.encryptBundleDual(nextBundle, sendCode, recvCode, requestId)
                return PairStatusResponse(
                    state = "approved",
                    salt = blob.saltB64,
                    nonce = blob.nonceB64,
                    ciphertext = blob.ciphertextB64
                )
            }
        }
        val service = SyncService(repo, fakeRelay)

        // Locked vault must reject startReceiveRequest BEFORE touching relay
        repo.lockNow()
        val lockedErr = runCatching { service.startReceiveRequest(sendCode, recvCode) }.exceptionOrNull()
        assertTrue(lockedErr is SecurityException)
        assertEquals(0, pairStatusCalls.get())

        assertTrue(repo.unlock("pw123").isSuccess)

        suspend fun receiveOnce(): SyncReceivePreview {
            val wait = service.startReceiveRequest(sendCode, recvCode)
            assertEquals(channelId, SyncCrypto.deriveChannelId(wait.sendCode))
            return service.pollReceiveStatus(wait.waitId).preview!!
        }

        // Preview cache capped at 2 entries
        val p1 = receiveOnce()
        val p2 = receiveOnce()
        val p3 = receiveOnce()
        assertTrue(runCatching { service.applyReceive(p1.previewId, SyncImportMode.MERGE) }.isFailure)

        // Lock clears remaining previews
        repo.lockNow()
        assertTrue(repo.unlock("pw123").isSuccess)
        assertTrue(runCatching { service.applyReceive(p2.previewId, SyncImportMode.MERGE) }.isFailure)
        assertTrue(runCatching { service.applyReceive(p3.previewId, SyncImportMode.MERGE) }.isFailure)

        // Preview is retained if import fails (schemaVersion != 1), and removed once import succeeds
        nextBundle = SyncBundle(schemaVersion = 99)
        val badPreview = receiveOnce()
        val badImport = runCatching { service.applyReceive(badPreview.previewId, SyncImportMode.MERGE) }.exceptionOrNull()
        assertNotNull(badImport)
        assertTrue(badImport is IllegalArgumentException)
        val secondErr = runCatching { service.applyReceive(badPreview.previewId, SyncImportMode.MERGE) }.exceptionOrNull()
        assertTrue(secondErr is IllegalArgumentException)
        service.discardPreview(badPreview.previewId)

        nextBundle = SyncBundle(schemaVersion = 1, servers = listOf(ServerConfig(id = "s-ok", name = "OK", host = "10.0.0.5")))
        val okPreview = receiveOnce()
        val sum = service.applyReceive(okPreview.previewId, SyncImportMode.MERGE)
        assertEquals(1, sum.serversAdded)
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

        val relay = object : SyncRelayClient() {
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

        // 2. Sender starts v2 send and polls WAITING
        val sendSess = senderService.startSend()
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

    @Test
    fun syncMergePreventsCredentialLeakAndPresetHijack() {
        val repo = VaultRepository(File(tempFolder.root, "merge-sec.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        repo.saveServer(
            ServerConfig(
                id = "s-corp",
                name = "Corp",
                host = "app.corp.local",
                port = 22,
                username = "ops",
                authType = AuthType.KEY,
                sshKeyId = "k-corp",
                proxy = ProxyConfig(ProxyType.SOCKS5, "corp-proxy.local", 1080, "alice", "CORP-PROXY-PW"),
                jumpHost = JumpHostConfig(
                    mode = JumpMode.CUSTOM,
                    host = "corp-jump.local",
                    port = 22,
                    username = "bastion",
                    authType = AuthType.KEY,
                    password = "CORP-JUMP-PW",
                    sshKeyId = "k-jump"
                )
            )
        )

        val incoming = SyncBundle(
            schemaVersion = 1,
            servers = listOf(
                ServerConfig(
                    id = "s-corp",
                    name = "Corp Changed",
                    host = "app.corp.local",
                    port = 22,
                    username = "ops",
                    authType = AuthType.PASSWORD,
                    sshKeyId = "",
                    proxy = ProxyConfig(ProxyType.SOCKS5, "other-proxy.local", 1080, "alice", ""),
                    jumpHost = JumpHostConfig(mode = JumpMode.CUSTOM, host = "other-jump.local", port = 22, username = "bastion", password = "", sshKeyId = "")
                )
            ),
            templates = listOf(
                BuiltinTemplate(id = "tpl-bbr", presetId = "", name = "开启 BBR 网络加速", command = "echo hijacked"),
                BuiltinTemplate(id = "custom-dup", presetId = "fake-preset-id", name = "Fake Preset", command = "echo fake")
            )
        )

        repo.importSyncBundle(incoming, SyncImportMode.MERGE)
        val updated = repo.state.value.data.servers.first { it.id == "s-corp" }
        assertEquals("", updated.proxy.password)
        assertEquals("", updated.jumpHost.password)
        assertEquals("", updated.jumpHost.sshKeyId)
        assertEquals("", updated.sshKeyId)

        val bbr = repo.state.value.data.templates.first { it.presetId == "tpl-bbr" }
        assertFalse(bbr.command.contains("hijacked"))
        val fake = repo.state.value.data.templates.first { it.name == "Fake Preset" }
        assertEquals("", fake.presetId)

        // Overwrite mode also strips invalid/duplicate presetId
        repo.importSyncBundle(
            SyncBundle(
                schemaVersion = 1,
                templates = listOf(
                    BuiltinTemplate(id = "t1", presetId = "tpl-bbr", name = "BBR 1", command = "echo 1"),
                    BuiltinTemplate(id = "t2", presetId = "tpl-bbr", name = "BBR 2", command = "echo 2"),
                    BuiltinTemplate(id = "t3", presetId = "not-real", name = "Bad", command = "echo 3")
                )
            ),
            SyncImportMode.OVERWRITE
        )
        val tpls = repo.state.value.data.templates
        assertEquals(1, tpls.count { it.presetId == "tpl-bbr" })
        assertEquals("", tpls.first { it.name == "Bad" }.presetId)
    }

    @Test
    fun syncServiceClockSkewGraceAndLockAbortToctou() = runTest {
        val repo = VaultRepository(File(tempFolder.root, "toctou.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        assertTrue(repo.setMasterPassword("", "pw123").isSuccess)

        val pairInitEntered = CompletableDeferred<Unit>()
        val pairInitRelease = CompletableDeferred<Unit>()
        val cancelCalls = AtomicInteger(0)
        val approveCalls = AtomicInteger(0)
        // Simulate relay clock 100s behind local clock (within 135s skew grace)
        val skewedExpiresIso = Instant.ofEpochMilli(System.currentTimeMillis() - 100_000L).toString()

        val relay = object : SyncRelayClient() {
            var blockNextInit = false

            override suspend fun pairInit(relayUrl: String, channelId: String, senderToken: String, ttlSeconds: Int): PairInitResponse {
                if (blockNextInit) {
                    pairInitEntered.complete(Unit)
                    pairInitRelease.await()
                }
                return PairInitResponse(ok = true, expiresAt = skewedExpiresIso)
            }

            override suspend fun pairPoll(relayUrl: String, channelId: String, senderToken: String): PairPollResponse =
                PairPollResponse(state = "waiting")

            override suspend fun pairApprove(
                relayUrl: String,
                channelId: String,
                senderToken: String,
                requestId: String,
                salt: String,
                nonce: String,
                ciphertext: String
            ) {
                approveCalls.incrementAndGet()
            }

            override suspend fun pairCancel(relayUrl: String, channelId: String, senderToken: String) {
                cancelCalls.incrementAndGet()
            }
        }

        val service = SyncService(repo, relay)

        // 1. Session within 135s clock skew grace must not be pruned prematurely
        val skewedSess = service.startSend()
        assertEquals(SyncSendPollState.WAITING, service.pollSend(skewedSess.sessionId).state)

        // 2. Locking vault mid-pairInit aborts startSend and triggers pairCancel
        relay.blockNextInit = true
        val pendingStart = async { runCatching { service.startSend() } }
        pairInitEntered.await()
        repo.lockNow()
        pairInitRelease.complete(Unit)
        val startRes = pendingStart.await()
        assertTrue(startRes.exceptionOrNull() is SecurityException)
        assertTrue(cancelCalls.get() >= 1)
        assertEquals(0, approveCalls.get())
    }

    @Test
    fun pollSendInspectsLiveApprovedFlagWhenGoneArrivesMidApprove() = runTest {
        val repo = VaultRepository(File(tempFolder.root, "race-consumed.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        val pollEntered = CompletableDeferred<Unit>()
        val releasePoll = CompletableDeferred<Unit>()

        val relay = object : SyncRelayClient() {
            override suspend fun pairInit(relayUrl: String, channelId: String, senderToken: String, ttlSeconds: Int): PairInitResponse =
                PairInitResponse(ok = true, expiresAt = "2099-01-01T00:00:00Z")

            override suspend fun pairPoll(relayUrl: String, channelId: String, senderToken: String): PairPollResponse {
                pollEntered.complete(Unit)
                releasePoll.await()
                return PairPollResponse(state = "gone")
            }

            override suspend fun pairApprove(
                relayUrl: String,
                channelId: String,
                senderToken: String,
                requestId: String,
                salt: String,
                nonce: String,
                ciphertext: String
            ) = Unit
        }

        val service = SyncService(repo, relay)
        val sendSess = service.startSend()
        val pollDeferred = async { service.pollSend(sendSess.sessionId) }
        pollEntered.await()

        // Approve while pollSend is in-flight with stale wasApproved=false snapshot
        service.approveSend(
            sessionId = sendSess.sessionId,
            requestId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            receiverCode = "M8N2-Q5R7"
        )
        releasePoll.complete(Unit)

        assertEquals(SyncSendPollState.CONSUMED, pollDeferred.await().state)
    }

    @Test
    fun relayUrlExplicitSpkiPinTls13AndCustomIpTofu() {
        // Pure TLS 1.3 enforced on API 29+
        assertEquals(listOf(TlsVersion.TLS_1_3), SyncHttpClients.tls13Spec.tlsVersions)

        val validPin = "A1".repeat(32)
        val norm = SyncConfig.normalizeRelayUrl("https://198.51.100.10:54843/#spki=SHA256:$validPin")
        assertEquals("https://198.51.100.10:54843#spki=${validPin.lowercase()}", norm)
        val ep = SyncConfig.parseRelayEndpoint(norm)
        assertEquals("https://198.51.100.10:54843", ep.baseUrl)
        assertEquals(validPin.lowercase(), ep.explicitSpkiPin)
        assertTrue(ep.isRawIp)
        assertFalse(ep.isDefaultRelay)
        assertTrue(runCatching { SyncConfig.normalizeRelayUrl("https://198.51.100.10:54843#spki=bad") }.isFailure)
        assertTrue(runCatching { SyncConfig.normalizeRelayUrl("https://user:pass@198.51.100.10:54843") }.isFailure)

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp1 = kpg.generateKeyPair()
        val kp2 = kpg.generateKeyPair()
        val certDns1 = stubX509Cert(kp1.public, listOf(listOf(2, "sshsync")))
        val certDns2 = stubX509Cert(kp2.public, listOf(listOf(2, "sshsync")))
        val certIpOk = stubX509Cert(kp1.public, listOf(listOf(7, "198.51.100.10")))
        val certIpBad = stubX509Cert(kp1.public, listOf(listOf(7, "198.51.100.99")))
        val spki1 = SyncHttpClients.spkiSha256Hex(certDns1)

        // Explicit SPKI pin match & mismatch
        SyncHttpClients.verifyPinnedSpkiCert(arrayOf(certDns1), spki1)
        assertTrue(runCatching { SyncHttpClients.verifyPinnedSpkiCert(arrayOf(certDns2), spki1) }.exceptionOrNull() is CertificateException)

        // Standard self-hosted bbplin/sshsync cert (DNS:sshsync, no IP SAN) works under TOFU & catches rotation/MITM
        SyncHttpClients.clearCustomRelayTofuPins()
        try {
            SyncHttpClients.verifyAndPinCustomRelayTofu("198.51.100.10:54843", certDns1, "198.51.100.10")
            SyncHttpClients.verifyAndPinCustomRelayTofu("198.51.100.10:54843", certDns1, "198.51.100.10")
            assertTrue(
                runCatching {
                    SyncHttpClients.verifyAndPinCustomRelayTofu("198.51.100.10:54843", certDns2, "198.51.100.10")
                }.exceptionOrNull() is CertificateException
            )

            // When cert DOES carry IP SANs, enforce IP SAN matching
            SyncHttpClients.verifySelfSignedIpLeafCert(certIpOk, "198.51.100.10")
            assertTrue(
                runCatching {
                    SyncHttpClients.verifySelfSignedIpLeafCert(certIpBad, "198.51.100.10")
                }.exceptionOrNull() is CertificateException
            )
        } finally {
            SyncHttpClients.clearCustomRelayTofuPins()
        }
    }

    @Test
    fun syncRelayClientCancelsPromptlyOnCoroutineCancel() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = CompletableDeferred<Unit>()
            val accepter = Thread {
                runCatching {
                    server.accept().use { sock ->
                        accepted.complete(Unit)
                        Thread.sleep(10_000L)
                        sock.close()
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = SyncRelayClient()
            val job = launch(Dispatchers.IO) {
                runCatching { client.ping("https://127.0.0.1:${server.localPort}") }
            }
            withTimeout(2_000L) { accepted.await() }
            delay(50L)
            job.cancel()
            withTimeout(3_000L) { job.join() }
            accepter.interrupt()
        }
    }

    @Test
    fun vaultSyncOpsSanitizationParityAndJumpCycleBreaking() {
        val repo = VaultRepository(File(tempFolder.root, "parity.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()

        val bundle = SyncBundle(
            schemaVersion = 1,
            sshKeys = listOf(
                SSHKeyRecord(id = "", name = "", fingerprint = "", publicKey = "", privateKeyPem = ""),
                SSHKeyRecord(id = "k-valid", name = "Valid", fingerprint = "SHA256:valid")
            ),
            servers = listOf(
                ServerConfig(
                    id = "srv-a",
                    name = "A",
                    host = "198.51.100.1",
                    port = 0,
                    timeoutSeconds = 0,
                    keepAliveSeconds = 0,
                    proxy = ProxyConfig(type = ProxyType.SOCKS5, host = "127.0.0.1", port = 0),
                    jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-b")
                ),
                ServerConfig(
                    id = "srv-b",
                    name = "B",
                    host = "198.51.100.2",
                    port = 22,
                    keepAliveSeconds = -10,
                    proxy = ProxyConfig(type = ProxyType.HTTP, host = "127.0.0.1", port = 0),
                    jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-c")
                ),
                ServerConfig(
                    id = "srv-c",
                    name = "C",
                    host = "198.51.100.3",
                    port = 22,
                    jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-a")
                )
            ),
            shortcuts = listOf(
                CommandShortcut(id = "sc-empty", name = "NoCmd", command = "   "),
                CommandShortcut(id = "sc-ok", name = "Ok", command = "uname -a")
            ),
            templates = listOf(
                BuiltinTemplate(id = "t-empty", name = "NoCmd", command = "  "),
                BuiltinTemplate(id = "t-ok", name = "OkTpl", command = "uptime")
            )
        )

        val overSum = repo.importSyncBundle(bundle, SyncImportMode.OVERWRITE)
        assertEquals(1, overSum.keysAdded)
        assertEquals(3, overSum.serversAdded)
        assertEquals(1, overSum.shortcutsAdded)
        assertEquals(1, overSum.templatesAdded)

        val servers = repo.state.value.data.servers.associateBy { it.id }
        val srvA = servers["srv-a"]!!
        val srvB = servers["srv-b"]!!
        assertEquals(22, srvA.port)
        assertEquals(15, srvA.timeoutSeconds)
        assertEquals(0, srvA.keepAliveSeconds)
        assertEquals(1080, srvA.proxy.port)
        assertEquals(0, srvB.keepAliveSeconds)
        assertEquals(8080, srvB.proxy.port)
        // At least one edge in the 3-node jump cycle A -> B -> C -> A must be broken
        assertTrue(
            servers.values.any { it.jumpHost.mode == JumpMode.NONE && it.jumpHost.serverId.isEmpty() }
        )

        // Merge mode also breaks multi-hop cycles introduced across existing + incoming servers
        val cycleMerge = SyncBundle(
            schemaVersion = 1,
            servers = listOf(
                ServerConfig(
                    id = "srv-a",
                    name = "A",
                    host = "198.51.100.1",
                    port = 22,
                    jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-b")
                ),
                ServerConfig(
                    id = "srv-b",
                    name = "B",
                    host = "198.51.100.2",
                    port = 22,
                    jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-a")
                )
            )
        )
        repo.importSyncBundle(cycleMerge, SyncImportMode.MERGE)
        val merged = repo.state.value.data.servers.associateBy { it.id }
        val aPointsToB = merged["srv-a"]!!.jumpHost.mode == JumpMode.SERVER && merged["srv-a"]!!.jumpHost.serverId == "srv-b"
        val bPointsToA = merged["srv-b"]!!.jumpHost.mode == JumpMode.SERVER && merged["srv-b"]!!.jumpHost.serverId == "srv-a"
        assertFalse(aPointsToB && bPointsToA)
    }

    private fun stubX509Cert(pub: PublicKey, sans: Collection<List<*>>?): X509Certificate = object : X509Certificate() {
        private val principal = X500Principal("CN=sshsync")
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = principal
        override fun getSubjectDN(): Principal = principal
        override fun getNotBefore(): Date = Date(0L)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = ByteArray(0)
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getEncoded(): ByteArray = pub.encoded
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "StubX509Certificate"
        override fun getPublicKey(): PublicKey = pub
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun getSubjectAlternativeNames(): Collection<List<*>>? = sans
    }
}
