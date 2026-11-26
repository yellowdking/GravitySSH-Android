package com.gravityssh.app.sync

import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SyncCryptoInteropTest {

    @Test
    fun decryptWindowsGoldenVectorByteForByte() {
        val code = "K7M9-P4X2"
        val canonical = SyncCrypto.canonicalizeCode(code)
        assertEquals("K7M9P4X2", canonical)
        assertEquals("gssh-sync://v1?code=K7M9-P4X2", SyncCrypto.buildQrUri(code))
        assertEquals("K7M9P4X2", SyncCrypto.canonicalizeCode("gssh-sync://v1?code=k7m9-p4x2"))

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
    fun encryptDecryptRoundTrip() {
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
    }
}
