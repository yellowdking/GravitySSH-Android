package com.gravityssh.app.sync

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.PairRequestPayload
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncSendPollState
import com.gravityssh.app.vault.VaultCrypto
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

class SyncRelayAndPrimitivesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun pairRequestEnforces64KiBGunzipCapWhileBundleRetains16MiBLimit() {
        assertEquals(64 * 1024, SyncCryptoPrimitives.MAX_PAIR_REQUEST_PLAIN_BYTES)
        assertEquals(16 * 1024 * 1024, SyncCryptoPrimitives.MAX_BUNDLE_PLAIN_BYTES)

        val exact64k = ByteArray(64 * 1024) { 'A'.code.toByte() }
        val over64k = ByteArray(64 * 1024 + 1) { 'A'.code.toByte() }
        assertEquals(
            exact64k.size,
            SyncCryptoPrimitives.gzipDecompress(
                SyncCryptoPrimitives.gzipCompress(exact64k),
                SyncCryptoPrimitives.MAX_PAIR_REQUEST_PLAIN_BYTES,
                "cap"
            ).size
        )
        val rawErr = runCatching {
            SyncCryptoPrimitives.gzipDecompress(
                SyncCryptoPrimitives.gzipCompress(over64k),
                SyncCryptoPrimitives.MAX_PAIR_REQUEST_PLAIN_BYTES,
                "配对验证请求超过 64 KiB 上限"
            )
        }.exceptionOrNull()
        assertTrue("Expected IOException for >64KiB gunzip, got $rawErr", rawErr is IOException)

        // End-to-end encrypted PairRequest > 64 KiB uncompressed must be rejected on decryptPairRequest
        val sendCode = "K7M9-P4X2"
        val recvCode = "R3T8-W5Y6"
        val bigPairReq = PairRequestPayload(
            receiverCode = recvCode,
            receiverDevice = "A".repeat(70_000),
            receiverPlatform = "android",
            requestedAt = "2026-11-27T12:00:00Z"
        )
        val reqBlob = SyncCrypto.encryptPairRequest(sendCode, bigPairReq)
        val reqErr = runCatching {
            SyncCrypto.decryptPairRequest(sendCode, reqBlob.saltB64, reqBlob.nonceB64, reqBlob.ciphertextB64)
        }.exceptionOrNull()
        assertTrue("Expected IOException for oversized PairRequest, got $reqErr", reqErr is IOException)

        // Full SyncBundle > 64 KiB uncompressed must still decrypt normally under 16 MiB cap
        val largeBundle = SyncBundle(
            schemaVersion = 1,
            exportedAt = "2026-11-27T12:00:00Z",
            sourcePlatform = "android",
            sourceDevice = "TestDevice",
            servers = (1..250).map { idx ->
                ServerConfig(
                    id = "srv-$idx",
                    name = "Server-$idx-" + "N".repeat(200),
                    host = "10.0.${idx / 256}.${idx % 256}",
                    notes = "D".repeat(200)
                )
            }
        )
        val requestId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val bundleBlob = SyncCrypto.encryptBundleDual(largeBundle, sendCode, recvCode, requestId)
        val decoded = SyncCrypto.decryptBundleDual(
            bundleBlob.saltB64,
            bundleBlob.nonceB64,
            bundleBlob.ciphertextB64,
            sendCode,
            recvCode,
            requestId
        )
        assertEquals(250, decoded.servers.size)
    }

    @Test
    fun multiPinSpkiAndCustomGateFragmentParsingAndVerification() {
        assertTrue(SyncConfig.DEFAULT_RELAY_SPKI_PINS.contains(SyncConfig.PINNED_SPKI_SHA256))

        val pin1 = "a1".repeat(32)
        val pin2 = "b2".repeat(32)
        val gateKey = "c3".repeat(32)

        val norm = SyncConfig.normalizeRelayUrl(
            "https://198.51.100.20:54843/#gate=${gateKey.uppercase()}&spki=SHA256:${pin1.uppercase()},$pin2,$pin1"
        )
        assertEquals("https://198.51.100.20:54843#spki=$pin1,$pin2&gate=$gateKey", norm)

        val ep = SyncConfig.parseRelayEndpoint(norm)
        assertEquals("https://198.51.100.20:54843", ep.baseUrl)
        assertEquals(listOf(pin1, pin2), ep.explicitSpkiPins)
        assertEquals(pin1, ep.explicitSpkiPin)
        assertEquals(gateKey, ep.customAppGateKey)

        // Gate-only fragment without #spki
        val gateOnly = SyncConfig.parseRelayEndpoint("https://sync.example.com/#gate=$gateKey")
        assertTrue(gateOnly.explicitSpkiPins.isEmpty())
        assertNull(gateOnly.explicitSpkiPin)
        assertEquals(gateKey, gateOnly.customAppGateKey)

        // Validation errors: invalid gate hex, >8 pins, bad pin
        assertTrue(runCatching { SyncConfig.normalizeRelayUrl("https://198.51.100.20:54843#gate=1234") }.isFailure)
        assertTrue(runCatching { SyncConfig.normalizeRelayUrl("https://198.51.100.20:54843#gate=${"z".repeat(64)}") }.isFailure)
        val ninePins = (1..9).joinToString(",") { i -> "%02x".format(i).repeat(32) }
        assertTrue(runCatching { SyncConfig.parseSpkiPinList(ninePins) }.isFailure)

        // Multi-pin certificate verification accepts either primary or rotated backup pin and rejects unknown cert
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val c1 = stubCert(kpg.generateKeyPair().public)
        val c2 = stubCert(kpg.generateKeyPair().public)
        val c3 = stubCert(kpg.generateKeyPair().public)
        val spki1 = SyncHttpClients.spkiSha256Hex(c1)
        val spki2 = SyncHttpClients.spkiSha256Hex(c2)

        SyncHttpClients.verifyPinnedSpkiCerts(arrayOf(c1), listOf(spki1, spki2))
        SyncHttpClients.verifyPinnedSpkiCerts(arrayOf(c2), listOf(spki1, spki2))
        assertTrue(
            runCatching {
                SyncHttpClients.verifyPinnedSpkiCerts(arrayOf(c3), listOf(spki1, spki2))
            }.exceptionOrNull() is CertificateException
        )

        // Multi-pin client builder accepts list without error
        assertNotNull(SyncHttpClients.buildPinnedClient(listOf(spki1, spki2), "198.51.100.20"))
    }

    @Test
    fun syncRelayClientSendsProtoHeaderCustomGateHmacAndHandles426AndLegacyRelays() = runTest {
        val customGate = "d4".repeat(32)
        val pin1 = "e5".repeat(32)
        val pin2 = "f6".repeat(32)
        val rawBody = "{}".toByteArray(Charsets.UTF_8)

        val baseClient = SyncRelayClient()
        // 1. Default endpoint signs with built-in APP_GATE_KEY and sets X-GSSH-Proto: 2
        val defaultEp = SyncConfig.parseRelayEndpoint("")
        val defaultReq = baseClient.buildSignedRequest(defaultEp, "/v1/ping", rawBody, 1767323045L)
        assertEquals("2", defaultReq.header(SyncConfig.WIRE_PROTO_HEADER))
        assertEquals(
            SyncCryptoPrimitives.signAppGate(SyncConfig.APP_GATE_KEY, "POST", "/v1/ping", 1767323045L, rawBody),
            defaultReq.header("X-GSSH-Sig")
        )

        // 2. Custom #gate=<hex> signs with custom gate key instead of built-in APP_GATE_KEY
        val customEp = SyncConfig.parseRelayEndpoint("https://198.51.100.20:54843#spki=$pin1,$pin2&gate=$customGate")
        val customReq = baseClient.buildSignedRequest(customEp, "/v1/ping", rawBody, 1767323045L)
        assertEquals("2", customReq.header(SyncConfig.WIRE_PROTO_HEADER))
        assertEquals(
            SyncCryptoPrimitives.signAppGate(customGate, "POST", "/v1/ping", 1767323045L, rawBody),
            customReq.header("X-GSSH-Sig")
        )

        // 3. Protocol negotiation over executeHttp stub
        var nextResponse = RelayHttpResponse(code = 200, body = """{"ok":true}""", protoMinHeader = "1", protoMaxHeader = "2")
        var lastRequest: Request? = null
        val stubClient = object : SyncRelayClient() {
            override suspend fun executeHttp(client: OkHttpClient, request: Request): RelayHttpResponse {
                lastRequest = request
                return nextResponse
            }
        }

        // Compatible relay with proto headers
        stubClient.ping("https://198.51.100.20:54843#gate=$customGate")
        assertEquals("2", lastRequest?.header(SyncConfig.WIRE_PROTO_HEADER))
        assertEquals(
            SyncCryptoPrimitives.signAppGate(
                customGate,
                "POST",
                "/v1/ping",
                lastRequest!!.header("X-GSSH-TS")!!.toLong(),
                rawBody
            ),
            lastRequest?.header("X-GSSH-Sig")
        )

        // Legacy relay omitting X-GSSH-Proto-Min / Max remains 100% compatible
        nextResponse = RelayHttpResponse(code = 200, body = """{"ok":true}""", protoMinHeader = null, protoMaxHeader = null)
        stubClient.ping("https://sync.example.com")

        // HTTP 426 Upgrade Required with JSON min/max surfaces clear Chinese version mismatch error
        nextResponse = RelayHttpResponse(
            code = 426,
            body = """{"error":"unsupported_protocol","minVersion":3,"maxVersion":4}""",
            protoMinHeader = "3",
            protoMaxHeader = "4"
        )
        val err426Json = runCatching { stubClient.ping("https://sync.example.com") }.exceptionOrNull()
        assertTrue(err426Json is IOException)
        assertTrue(err426Json!!.message!!.contains("同步协议版本不兼容"))
        assertTrue(err426Json.message!!.contains("3-4"))

        // HTTP 426 with header-only range
        nextResponse = RelayHttpResponse(code = 426, body = "", protoMinHeader = "3", protoMaxHeader = "3")
        val err426Hdr = runCatching { stubClient.ping("https://sync.example.com") }.exceptionOrNull()
        assertTrue(err426Hdr is IOException)
        assertTrue(err426Hdr!!.message!!.contains("3-3"))

        // Bare HTTP 426 without range info
        nextResponse = RelayHttpResponse(code = 426, body = "")
        val err426Bare = runCatching { stubClient.ping("https://sync.example.com") }.exceptionOrNull()
        assertTrue(err426Bare is IOException)
        assertTrue(err426Bare!!.message!!.contains("HTTP 426"))

        // Incompatible range advertised on 200
        nextResponse = RelayHttpResponse(code = 200, body = """{"ok":true}""", protoMinHeader = "3", protoMaxHeader = "5")
        val errRange = runCatching { stubClient.ping("https://sync.example.com") }.exceptionOrNull()
        assertTrue(errRange is IOException)
        assertTrue(errRange!!.message!!.contains("同步协议版本不兼容"))

        // Malformed range header on 200
        nextResponse = RelayHttpResponse(code = 200, body = """{"ok":true}""", protoMinHeader = "invalid", protoMaxHeader = "2")
        val errMalformed = runCatching { stubClient.ping("https://sync.example.com") }.exceptionOrNull()
        assertTrue(errMalformed is IOException)
    }

    @Test
    fun syncServicePollSendCachesDecryptedPairRequestAcrossRepeatedPolls() = runTest {
        val repo = VaultRepository(File(tempFolder.root, "cache.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()

        val recvCode = "M8N2-Q5R7"
        val requestId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        var reqBlob: EncryptedDropBlob? = null

        val relay = object : SyncRelayClient() {
            override suspend fun pairInit(relayUrl: String, channelId: String, senderToken: String, ttlSeconds: Int): PairInitResponse =
                PairInitResponse(ok = true, expiresAt = "2099-01-01T00:00:00Z")

            override suspend fun pairPoll(relayUrl: String, channelId: String, senderToken: String): PairPollResponse {
                val b = reqBlob!!
                return PairPollResponse(
                    state = "requested",
                    requestId = requestId,
                    salt = b.saltB64,
                    nonce = b.nonceB64,
                    ciphertext = b.ciphertextB64
                )
            }
        }

        val service = SyncService(repo, relay)
        val sendSess = service.startSend()
        reqBlob = SyncCrypto.encryptPairRequest(
            sendSess.code,
            PairRequestPayload(
                receiverCode = recvCode,
                receiverDevice = "Pixel-9",
                receiverPlatform = "android",
                requestedAt = "2026-11-27T12:00:00Z"
            )
        )

        val first = service.pollSend(sendSess.sessionId)
        val second = service.pollSend(sendSess.sessionId)
        assertEquals(SyncSendPollState.REQUESTED, first.state)
        assertEquals(first, second)
        assertEquals(recvCode, second.receiverCode)
        assertEquals("Pixel-9", second.receiverDevice)
    }

    private fun stubCert(pub: PublicKey): X509Certificate = object : X509Certificate() {
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
    }
}
