package com.gravityssh.app.vault

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.AppJson
import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.SeenMsgRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncVaultState
import com.gravityssh.app.model.VaultData
import com.yellowdking.synctool.crypto.KeysAndAuth
import com.yellowdking.synctool.envelope.CachedManifestEntry
import com.yellowdking.synctool.envelope.DeviceCard
import com.yellowdking.synctool.envelope.Manifest
import com.yellowdking.synctool.envelope.SenderInfo
import com.yellowdking.synctool.envelope.TransferCheckpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

class VaultAuditAndKeyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun lockNowWipesMemoryAndRunsCallbacksSynchronouslyBeforeAsyncFlush(): Unit {
        val file = File(tempFolder.root, "vault-async-lock.gsv")
        val baseProvider = InMemoryMasterKeyProvider()
        val slowSaveGate = CountDownLatch(1)
        val saveStarted = CountDownLatch(1)
        val slowOnNextSeal = AtomicBoolean(false)

        val gatedProvider = object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey {
                if (slowOnNextSeal.get()) {
                    saveStarted.countDown()
                    slowSaveGate.await(3, TimeUnit.SECONDS)
                }
                return baseProvider.getOrCreateKey()
            }
        }

        val repo = VaultRepository(file, VaultCrypto(gatedProvider))
        repo.open()
        assertTrue(repo.setMasterPassword("", "pw-fast-lock").isSuccess)
        repo.saveServer(ServerConfig(id = "s1", name = "SecretHost", host = "10.9.8.7"))
        repo.recordCommand("cat /etc/hostname", "s1", "SecretHost", "sess-fast")

        val stateObservedInCallback = AtomicReference<Boolean>(false)
        val serversEmptyInCallback = AtomicReference<Boolean>(false)
        val flushCompletedBeforeCallback = AtomicReference<Boolean>(true)

        repo.addOnLockListener {
            stateObservedInCallback.set(repo.state.value.lockState.locked)
            serversEmptyInCallback.set(repo.state.value.data.servers.isEmpty())
            flushCompletedBeforeCallback.set(slowSaveGate.count == 0L)
        }

        slowOnNextSeal.set(true)
        val startNs = System.nanoTime()
        repo.lockNow()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        // lockNow() must complete immediately without blocking on slowSaveGate
        assertTrue("Expected lockNow() < 250ms while flush is gated, took ${elapsedMs}ms", elapsedMs < 250L)
        assertTrue(repo.state.value.lockState.locked)
        assertTrue(repo.state.value.data.servers.isEmpty())
        assertTrue(stateObservedInCallback.get())
        assertTrue(serversEmptyInCallback.get())
        assertFalse(flushCompletedBeforeCallback.get())

        assertTrue(saveStarted.await(2, TimeUnit.SECONDS))
        slowOnNextSeal.set(false)
        slowSaveGate.countDown()

        // Reopen immediately -> VaultPersistence awaits pending async flush and preserves recorded command
        val repo2 = VaultRepository(file, VaultCrypto(baseProvider))
        repo2.open()
        assertTrue(repo2.unlock("pw-fast-lock").isSuccess)
        assertEquals("cat /etc/hostname", repo2.state.value.data.history.first().command)
    }

    @Test
    fun resetCorruptedVaultRecoversFromInvalidatedMasterKeyAndCapsBackupsAtThree(): Unit {
        val file = File(tempFolder.root, "vault-bricked-key.gsv")
        val healthyDelegate = InMemoryMasterKeyProvider()
        val keyInvalidated = AtomicBoolean(false)
        val resetMasterCalls = AtomicBoolean(false)
        val resetBioCalls = AtomicBoolean(false)

        val brokenKey = object : SecretKey {
            override fun getAlgorithm(): String = "AES"
            override fun getFormat(): String = "RAW"
            override fun getEncoded(): ByteArray = ByteArray(1) // Invalid 1-byte AES key -> cipher.init throws InvalidKeyException
        }

        val provider = object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey {
                if (keyInvalidated.get()) return brokenKey
                return healthyDelegate.getOrCreateKey()
            }

            override fun resetMasterKey() {
                resetMasterCalls.set(true)
                keyInvalidated.set(false)
                healthyDelegate.resetMasterKey()
            }

            override fun resetBiometricAuthKey() {
                resetBioCalls.set(true)
                healthyDelegate.resetBiometricAuthKey()
            }
        }

        val repo = VaultRepository(file, VaultCrypto(provider))
        repo.open()
        repo.saveServer(ServerConfig(id = "s1", name = "BeforeBreak", host = "10.0.0.1"))

        // Simulate AndroidKeyStore master key invalidation on reopen
        keyInvalidated.set(true)
        val brokenRepo = VaultRepository(file, VaultCrypto(provider))
        brokenRepo.open()
        assertTrue(brokenRepo.state.value.lockState.locked)
        assertNotNull(brokenRepo.state.value.lockState.startupError)

        // resetCorruptedVault must call resetMasterKey() + resetBiometricAuthKey() and recover
        val resetRes = brokenRepo.resetCorruptedVault()
        assertTrue("Expected resetCorruptedVault to recover: ${resetRes.exceptionOrNull()}", resetRes.isSuccess)
        assertTrue(resetMasterCalls.get())
        assertTrue(resetBioCalls.get())
        assertFalse(brokenRepo.state.value.lockState.locked)
        assertNull(brokenRepo.state.value.lockState.startupError)

        // Trigger 5 corrupt vault resets and verify .corrupt.* files are capped at 3
        repeat(5) { idx ->
            file.writeBytes(byteArrayOf(0x09, idx.toByte()))
            assertTrue(brokenRepo.resetCorruptedVault().isSuccess)
        }
        val vaultBackups = tempFolder.root.listFiles { f -> f.name.startsWith("vault-bricked-key.gsv.corrupt.") }.orEmpty()
        assertEquals(3, vaultBackups.size)

        // Trigger 5 corrupt known_hosts loads and verify .corrupt.* files are capped at 3
        val khFile = File(tempFolder.root, "known_hosts.json")
        val rsaPub = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        repeat(5) { idx ->
            khFile.writeText("{corrupt-$idx")
            val mgr = KnownHostsManager(khFile)
            assertTrue(mgr.verify("host$idx.local", 22, rsaPub))
        }
        val khBackups = tempFolder.root.listFiles { f -> f.name.startsWith("known_hosts.json.corrupt.") }.orEmpty()
        assertEquals(3, khBackups.size)
    }

    @Test
    fun legacySyncRelayUrlMigratesValidSynctoolUriAndClearsStaleBareUrls(): Unit {
        val kp = InMemoryMasterKeyProvider()
        val crypto = VaultCrypto(kp)

        // 1. Stale legacy v1 bare URL is cleared to "" and syncState.customRelayUri stays ""
        val staleFile = File(tempFolder.root, "vault-stale-relay.gsv")
        val staleVault = VaultData(
            settings = AppSettings(syncRelayUrl = "https://43.155.231.14:54843#spki=${"a".repeat(64)}")
        )
        staleFile.writeBytes(crypto.seal(AppJson.json.encodeToString(staleVault).toByteArray(Charsets.UTF_8)))

        val repoStale = VaultRepository(staleFile, crypto)
        repoStale.open()
        assertEquals("", repoStale.state.value.data.settings.syncRelayUrl)
        assertEquals("", repoStale.state.value.data.syncState.customRelayUri)

        // 2. Valid synctool://relay URI in legacy settings.syncRelayUrl migrates into syncState.customRelayUri and clears settings
        val validUri = "synctool://relay?v=1&u=https%3A%2F%2F198.51.100.88%3A54843&pin=${"b".repeat(64)}#k=${"M".repeat(43)}"
        val validFile = File(tempFolder.root, "vault-valid-relay.gsv")
        val validLegacyVault = VaultData(settings = AppSettings(syncRelayUrl = validUri))
        validFile.writeBytes(crypto.seal(AppJson.json.encodeToString(validLegacyVault).toByteArray(Charsets.UTF_8), password = "pw-mig"))

        val repoValid = VaultRepository(validFile, crypto)
        repoValid.open()
        assertTrue(repoValid.unlock("pw-mig").isSuccess)
        assertEquals("", repoValid.state.value.data.settings.syncRelayUrl)
        assertEquals(validUri, repoValid.state.value.data.syncState.customRelayUri)
    }

    @Test
    fun overwriteImportPreservesSyncVaultStateAndVaultSyncKeyStoreRoundTrips(): Unit = runBlocking {
        val file = File(tempFolder.root, "vault-sync-ks.gsv")
        val kp = InMemoryMasterKeyProvider()
        val repo = VaultRepository(file, VaultCrypto(kp))
        repo.open()

        val ks = VaultSyncKeyStore(repo)
        val seed1 = ks.loadOrCreateMasterSeed()
        val seed2 = ks.loadOrCreateMasterSeed()
        assertEquals(32, seed1.size)
        assertArrayEquals(seed1, seed2)

        ks.setLocalLabel("Pixel 9 Pro")
        assertEquals("Pixel 9 Pro", ks.getLocalLabel())

        val peerDk = KeysAndAuth.deriveDeviceKeys(ByteArray(32) { 0x42 })
        val issuedAt = 1_760_000_000L
        val sig = KeysAndAuth.signDeviceCard(peerDk.signSeed, peerDk.kemPubBytes, issuedAt)
        val card = DeviceCard(
            deviceId = peerDk.deviceId,
            label = "Win-Workstation",
            platform = "windows",
            signPk = KeysAndAuth.b64uEncode(peerDk.signPub),
            kemPk = KeysAndAuth.b64uEncode(peerDk.kemPubBytes),
            issuedAt = issuedAt,
            cardSig = KeysAndAuth.b64uEncode(sig),
            relayOrigin = "https://43.155.231.14:54843",
            relayId = KeysAndAuth.deriveRelayId("https://43.155.231.14:54843"),
            pairedAt = issuedAt + 10L
        )
        ks.putDevice(card)
        ks.addPendingTokenDelete("tok-del-1")
        ks.markSeenMsgId("00112233445566778899aabbccddeeff", createdAt = issuedAt, seenAt = issuedAt + 5L)

        // Seal Manifest & Checkpoint into volatile LocalMetaKey cache and verify wipe on clearVolatileCaches()
        val sampleManifest = Manifest(
            msgId = "00112233445566778899aabbccddeeff",
            appId = "com.yellowdking.gravityssh",
            schema = "gravityssh.vault.v1",
            createdAt = issuedAt,
            sender = SenderInfo(deviceId = peerDk.deviceId, label = "Win-Workstation", platform = "windows")
        )
        ks.putCachedManifest(CachedManifestEntry("item-1", issuedAt, issuedAt + 3600L, "a".repeat(64), sampleManifest))
        ks.putCheckpoint(
            TransferCheckpoint(
                direction = "recv",
                msgId = sampleManifest.msgId,
                itemId = "item-1",
                partId = 1,
                nextTxIdx = 2,
                nextBlockIdx = 32L,
                expiresAt = (System.currentTimeMillis() / 1000L) + 3600L
            )
        )
        assertNotNull(ks.getCachedManifest("item-1"))
        assertNotNull(ks.getCheckpoint("recv", sampleManifest.msgId, 1))

        // OVERWRITE SyncBundle import MUST preserve syncState 100% intact!
        val beforeSyncState: SyncVaultState = repo.readSyncState()
        repo.importSyncBundle(
            SyncBundle(
                schemaVersion = 1,
                sourcePlatform = "windows",
                sourceDevice = "Win-Workstation",
                servers = listOf(ServerConfig(id = "srv-over", name = "OverHost", host = "10.20.30.40"))
            ),
            SyncImportMode.OVERWRITE
        )
        assertEquals(beforeSyncState, repo.readSyncState())
        assertEquals(1, ks.listDevices().size)
        assertEquals("Win-Workstation", ks.listDevices().single().label)
        assertEquals(listOf("tok-del-1"), ks.listPendingTokenDeletes())
        assertTrue(ks.hasSeenMsgId("00112233445566778899aabbccddeeff"))

        // exportSyncBundle never exports syncState
        val exportedJson = AppJson.json.encodeToString(repo.exportSyncBundle("Pixel 9 Pro"))
        assertFalse(exportedJson.contains(beforeSyncState.masterSeedB64u))
        assertFalse(exportedJson.contains("tok-del-1"))

        ks.clearVolatileCaches()
        assertNull(ks.getCachedManifest("item-1"))
        assertNull(ks.getCheckpoint("recv", sampleManifest.msgId, 1))
        peerDk.zero()
        Unit
    }
}
