package com.gravityssh.app.sync

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.JumpHostConfig
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.vault.VaultCrypto
import com.gravityssh.app.vault.VaultRepository
import com.yellowdking.synctool.client.InboxPreview
import com.yellowdking.synctool.crypto.KeysAndAuth
import com.yellowdking.synctool.envelope.BlobDescriptor
import com.yellowdking.synctool.envelope.ErrorCode
import com.yellowdking.synctool.envelope.Manifest
import com.yellowdking.synctool.envelope.PairOffer
import com.yellowdking.synctool.envelope.ReceivedBlob
import com.yellowdking.synctool.envelope.ReceivedParcel
import com.yellowdking.synctool.envelope.SenderInfo
import com.yellowdking.synctool.envelope.SyncError
import com.yellowdking.synctool.envelope.SyncUri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class SyncParcelAndConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun syncConfigDefaultRelayRedactionAndPairUriExtraction(): Unit {
        val (defaultProfile, isCustom) = SyncConfig.resolveRelayProfile("")
        assertFalse(isCustom)
        assertEquals("https://43.155.231.14:54843", defaultProfile.endpoint)
        assertEquals("spki_pin", defaultProfile.tlsMode)
        val defaultUi = SyncConfig.toRelayStatusUi(defaultProfile, isCustom = false)
        assertEquals("default", defaultUi.mode)
        assertEquals("", defaultUi.redactedUri)

        val secretKey = "SecretAccessKey_0123456789_ABCDEFGHIJKLMN"
        val pin = "c4".repeat(32)
        val customUri = "synctool://relay?v=1&u=https%3A%2F%2F198.51.100.25%3A54843&pin=$pin#k=$secretKey"
        val redacted = SyncConfig.redactUri(customUri)
        assertFalse(redacted.contains(secretKey))
        assertTrue(redacted.endsWith("#[REDACTED]"))

        val (customProfile, customFlag) = SyncConfig.resolveRelayProfile(customUri)
        assertTrue(customFlag)
        val customUi = SyncConfig.toRelayStatusUi(customProfile, isCustom = true, certExpiresAtEpochSec = 1_800_000_000L, certRotateRecommended = true)
        assertEquals("custom", customUi.mode)
        assertFalse(customUi.redactedUri.contains(secretKey))
        assertTrue(customUi.certRotateRecommended)
        assertTrue(customUi.certExpiresAt.isNotEmpty())

        // 1-Scan Custom Relay extraction from synctool://pair URI
        val pairWithRelay = SyncUri.formatPairUri(
            PairOffer(
                roomId = "K7M9P2XQ",
                mode = 1,
                purpose = "oneshot",
                endpoint = "https://198.51.100.25:54843",
                spkiPins = listOf(pin),
                oobSecret = ByteArray(16) { 0x33 },
                accessKey = secretKey
            )
        )
        val extracted = SyncConfig.parseRelayFromPairURI(pairWithRelay)
        assertNotNull(extracted)
        assertEquals("https://198.51.100.25:54843", extracted!!.endpoint)
        assertEquals(listOf(pin), extracted.spkiPins)
        assertEquals(secretKey, extracted.accessKey)
        assertNull(SyncConfig.parseRelayFromPairURI("K7M9-P2XQ"))

        // Friendly error mapping redacts any URI secrets
        val authErr = SyncError(ErrorCode.ERR_AUTH_FAILED, rawMessage = "failed against $customUri")
        val friendly = SyncConfig.toFriendlyException(authErr)
        assertFalse(friendly.message.orEmpty().contains(secretKey))
    }

    @Test
    fun alwaysTwoPartParcelNeverLeaksSecretsInInlinePayloadAndSupportsAllDecodePaths(): Unit {
        val secretPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nTOP_SECRET_ED25519_KEY_MATERIAL\n-----END OPENSSH PRIVATE KEY-----"
        val secretPw = "SuperSecretRootPassword!2026"
        val bundle = SyncBundle(
            schemaVersion = 1,
            exportedAt = "2026-11-28T03:00:00Z",
            sourcePlatform = "android",
            sourceDevice = "Pixel-9-Pro",
            servers = listOf(
                ServerConfig(id = "srv-1", name = "Prod", host = "10.0.1.10", username = "root", password = secretPw)
            ),
            sshKeys = listOf(
                SSHKeyRecord(id = "key-1", name = "EdKey", fingerprint = "SHA256:xyz", privateKeyPem = secretPem)
            ),
            shortcuts = listOf(CommandShortcut(id = "sc-1", name = "Top", command = "htop")),
            templates = listOf(BuiltinTemplate(id = "tpl-1", name = "Uptime", command = "uptime"))
        )

        val parcelInput = SyncParcelCodec.encodeVaultParcel(bundle)
        assertEquals(SyncConfig.APP_ID, parcelInput.appId)
        assertEquals(SyncConfig.PARCEL_SCHEMA, parcelInput.schema)
        assertEquals(1, parcelInput.blobs.size)

        val inlineBytes = AppJson.json.encodeToString(JsonObject.serializer(), parcelInput.inlinePayload!!)
            .toByteArray(StandardCharsets.UTF_8)
        assertTrue("Inline summary must be < 512 B, got ${inlineBytes.size}", inlineBytes.size < 512)
        val inlineText = String(inlineBytes, StandardCharsets.UTF_8)
        assertFalse(inlineText.contains(secretPem))
        assertFalse(inlineText.contains("TOP_SECRET_ED25519_KEY_MATERIAL"))
        assertFalse(inlineText.contains(secretPw))
        assertTrue(inlineText.contains("\"serverCount\":1"))
        assertTrue(inlineText.contains("\"keyCount\":1"))

        val blobIn = parcelInput.blobs.single()
        assertEquals("vault_bundle", blobIn.role)
        assertEquals("vault_bundle.json.gz", blobIn.name)
        assertEquals("application/gzip", blobIn.mime)
        assertEquals("gzip", blobIn.encoding)

        // Write gzipped bytes to temp file and verify decode for both "vault_bundle" and "vault_dump"
        val gzBytes = blobIn.openStream().first.use { it.readBytes() }
        val shaHex = KeysAndAuth.toLowerHex(KeysAndAuth.sha256(gzBytes))
        val blobKeyB64 = KeysAndAuth.b64uEncode(ByteArray(32) { 0x11 })
        val senderId = "a".repeat(32)

        for (role in listOf("vault_bundle", "vault_dump")) {
            val verifiedFile = File(tempFolder.root, "recv_$role.gz").apply { writeBytes(gzBytes) }
            val desc = BlobDescriptor(
                partId = 1,
                role = role,
                name = "vault_bundle.json.gz",
                mime = "application/gzip",
                size = gzBytes.size.toLong(),
                sha256 = shaHex,
                key = blobKeyB64,
                encoding = "gzip"
            )
            val manifest = Manifest(
                msgId = "b".repeat(32),
                appId = SyncConfig.APP_ID,
                schema = SyncConfig.PARCEL_SCHEMA,
                createdAt = 1_760_000_000L,
                sender = SenderInfo(deviceId = senderId, label = "Win-PC", platform = "windows"),
                inlinePayload = parcelInput.inlinePayload,
                blobs = listOf(desc)
            )
            val inboxUi = SyncParcelCodec.toInboxItemUi(
                InboxPreview("item-1", 1_760_000_000L, 1_760_086_400L, gzBytes.size.toLong(), false, manifest)
            )
            assertEquals(1, inboxUi.serverCount)
            assertEquals(1, inboxUi.keyCount)
            assertEquals(1, inboxUi.shortcutCount)
            assertEquals(1, inboxUi.templateCount)
            assertEquals("Win-PC", inboxUi.senderLabel)

            val decoded = SyncParcelCodec.decodeVaultParcel(
                ReceivedParcel(
                    itemId = "item-1",
                    manifest = manifest,
                    blobs = listOf(ReceivedBlob(desc, verifiedFile, "vault_bundle.json.gz"))
                )
            )
            assertEquals(bundle, decoded)
            assertFalse("Temp verified blob file should be cleaned up after decode", verifiedFile.exists())
        }

        // Empty-blob inline_payload fallback
        val inlineBundleObj = AppJson.json.encodeToJsonElement(SyncBundle.serializer(), bundle).jsonObject
        val fallbackManifest = Manifest(
            msgId = "c".repeat(32),
            appId = SyncConfig.APP_ID,
            schema = SyncConfig.PARCEL_SCHEMA,
            createdAt = 1_760_000_000L,
            sender = SenderInfo(deviceId = senderId, label = "Fallback-Peer", platform = "windows"),
            inlinePayload = inlineBundleObj,
            blobs = emptyList()
        )
        val decodedFallback = SyncParcelCodec.decodeVaultParcel(ReceivedParcel(manifest = fallbackManifest, blobs = emptyList()))
        assertEquals(bundle, decodedFallback)

        // 16 MiB gunzip cap enforcement
        val overCapBytes = ByteArray(1024 * 1024 + 1) { 'X'.code.toByte() }
        val err = runCatching {
            SyncParcelCodec.readStreamCapped(ByteArrayInputStream(overCapBytes), 1024 * 1024)
        }.exceptionOrNull()
        assertTrue(err is SyncError)
        assertEquals(ErrorCode.ERR_MANIFEST_INVALID, (err as SyncError).code)
    }

    @Test
    fun syncMergePreventsCredentialLeakPresetHijackAndJumpCycles(): Unit {
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
                ),
                ServerConfig(id = "cyc-a", name = "A", host = "198.51.100.1", jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "cyc-b")),
                ServerConfig(id = "cyc-b", name = "B", host = "198.51.100.2", jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "cyc-a"))
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

        val serversById = repo.state.value.data.servers.associateBy { it.id }
        val aPointsB = serversById["cyc-a"]!!.jumpHost.mode == JumpMode.SERVER && serversById["cyc-a"]!!.jumpHost.serverId == "cyc-b"
        val bPointsA = serversById["cyc-b"]!!.jumpHost.mode == JumpMode.SERVER && serversById["cyc-b"]!!.jumpHost.serverId == "cyc-a"
        assertFalse(aPointsB && bPointsA)

        val bbr = repo.state.value.data.templates.first { it.presetId == "tpl-bbr" }
        assertFalse(bbr.command.contains("hijacked"))
        val fake = repo.state.value.data.templates.first { it.name == "Fake Preset" }
        assertEquals("", fake.presetId)
    }
}
