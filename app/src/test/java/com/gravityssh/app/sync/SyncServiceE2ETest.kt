package com.gravityssh.app.sync

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.vault.VaultCrypto
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SyncServiceE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val spawnedProcs = ArrayList<Process>()
    private val tempDirs = ArrayList<File>()

    @After
    fun tearDown(): Unit {
        for (proc in spawnedProcs) {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
        spawnedProcs.clear()
        for (dir in tempDirs) {
            runCatching { dir.deleteRecursively() }
        }
        tempDirs.clear()
    }

    private fun createVaultAndService(
        name: String,
        label: String,
        defaultRelayOverride: (() -> String)? = null
    ): Pair<VaultRepository, SyncService> {
        val vaultFile = File(tempFolder.root, "$name.gsv")
        val tmpDir = File(tempFolder.root, "$name-tmp")
        val repo = VaultRepository(vaultFile, VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        val svc = SyncService(
            vaultRepository = repo,
            cacheBaseDirProvider = { tmpDir },
            defaultDeviceLabelProvider = { label },
            defaultRelayUriOverride = defaultRelayOverride
        )
        return repo to svc
    }

    private suspend fun awaitCondition(timeoutMs: Long = 15_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                delay(40L)
            }
        }
    }

    @Test
    fun syncServiceUnitRelaySaveRedactionLabelAndVaultLockCleanup(): Unit = runBlocking {
        val tmpDir = File(tempFolder.root, "lock-tmp").apply {
            mkdirs()
            File(this, "leftover.part").writeText("sensitive-temp")
        }
        val repo = VaultRepository(File(tempFolder.root, "unit.gsv"), VaultCrypto(InMemoryMasterKeyProvider()))
        repo.open()
        assertTrue(repo.setMasterPassword("", "pw-unit").isSuccess)

        val svc = SyncService(
            vaultRepository = repo,
            cacheBaseDirProvider = { tmpDir },
            defaultDeviceLabelProvider = { "Pixel-Default" }
        )
        awaitCondition { svc.uiState.value.localDeviceId.length == 32 }
        assertEquals("Pixel-Default", svc.uiState.value.localDeviceLabel)
        assertEquals("default", svc.uiState.value.relay.mode)

        // Update device label
        assertTrue(svc.updateDeviceLabel("My Galaxy S25").isSuccess)
        assertEquals("My Galaxy S25", svc.uiState.value.localDeviceLabel)
        assertEquals("My Galaxy S25", repo.readSyncState().localDeviceLabel)

        // Reject invalid custom relay URI (bare IP without SPKI pin or missing #k=)
        assertTrue(svc.saveCustomRelayUri("https://198.51.100.1:54843").isFailure)

        // Save valid custom relay URI -> UI state stays redacted (no #k=)
        val secretKey = "CustomKey_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456"
        val customUri = "synctool://relay?v=1&u=https%3A%2F%2F198.51.100.1%3A54843&pin=${"e".repeat(64)}#k=$secretKey"
        val savedUi = svc.saveCustomRelayUri(customUri).getOrThrow()
        assertEquals("custom", savedUi.mode)
        assertFalse(savedUi.redactedUri.contains(secretKey))
        assertTrue(savedUi.redactedUri.endsWith("#[REDACTED]"))

        // Reset custom relay with "" -> returns to default
        val resetUi = svc.saveCustomRelayUri("").getOrThrow()
        assertEquals("default", resetUi.mode)
        assertEquals("", repo.readSyncState().customRelayUri)

        // Locking vault triggers onVaultLocked -> deletes synctool-tmp and resets uiState
        assertTrue(tmpDir.exists())
        repo.lockNow()
        assertFalse("Expected cacheDir/synctool-tmp to be deleted on vault lock", tmpDir.exists())
        assertEquals("", svc.uiState.value.localDeviceId)
        assertEquals(SyncFlowStage.IDLE, svc.uiState.value.flowStage)

        // Locked calls fail closed
        assertTrue(svc.saveCustomRelayUri(customUri).isFailure)
        svc.startOneShotSend(mode = 1, includeRelay = false)
        assertEquals(SyncFlowStage.ERROR, svc.uiState.value.flowStage)
        Unit
    }

    private fun resolveOrBuildRelayBinary(): File? {
        val envBin = System.getenv("SYNCTOOL_RELAY_BIN")?.trim()?.takeIf { it.isNotEmpty() }
        if (envBin != null && File(envBin).isFile) return File(envBin)

        val synctoolDir = File("../synctool").canonicalFile
        if (!File(synctoolDir, "cmd/synctool-relay/main.go").isFile) return null

        val goCandidates = listOf(
            File("C:/Users/me/AppData/Local/GravitySSHTools/go/bin/go.exe"),
            File("go"),
        )
        val goExe = goCandidates.firstOrNull { it.isFile }?.absolutePath ?: "go"
        val outDir = Files.createTempDirectory("gssh-and-relay-bin-").toFile().also { tempDirs.add(it) }
        val isWin = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val binFile = File(outDir, if (isWin) "synctool-relay.exe" else "synctool-relay")
        return runCatching {
            val proc = ProcessBuilder(goExe, "build", "-o", binFile.absolutePath, "./cmd/synctool-relay")
                .directory(synctoolDir)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().readText()
            if (proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0 && binFile.isFile) binFile else null
        }.getOrNull()
    }

    private fun startRelayInstance(bin: File, spoolMode: String = "disk"): String {
        val dataDir = Files.createTempDirectory("gssh-and-relay-data-").toFile().also { tempDirs.add(it) }
        val port = ServerSocket(0).use { it.localPort }
        val listen = "127.0.0.1:$port"

        val initProc = ProcessBuilder(
            bin.absolutePath, "init",
            "--public-host", "127.0.0.1",
            "--listen", listen,
            "--profile", "standard",
            "--spool", spoolMode,
            "--tls", "selfsigned",
            "--data-dir", dataDir.absolutePath
        ).redirectErrorStream(true).start()
        val initOut = initProc.inputStream.bufferedReader().readText()
        assertTrue(initProc.waitFor(15, TimeUnit.SECONDS) && initProc.exitValue() == 0)
        val uri = Regex("""synctool://relay\?[^\s]+""").find(initOut)!!.value.trim()

        val serveProc = ProcessBuilder(
            bin.absolutePath, "serve",
            "--public-host", "127.0.0.1",
            "--listen", listen,
            "--profile", "standard",
            "--spool", spoolMode,
            "--tls", "selfsigned",
            "--data-dir", dataDir.absolutePath
        ).redirectErrorStream(true).start()
        spawnedProcs.add(serveProc)
        Thread({ runCatching { serveProc.inputStream.bufferedReader().forEachLine {} } }, "relay-log-drain")
            .apply { isDaemon = true; start() }
        return uri
    }

    @Test
    fun liveRelayEndToEndOneShotQrSasBindZeroRttInboxAndPipeFallback(): Unit = runBlocking {
        val relayBin = resolveOrBuildRelayBinary()
        Assume.assumeTrue("Go synctool-relay binary unavailable; skipping live loopback E2E", relayBin != null)

        val diskRelayUri = startRelayInstance(relayBin!!, spoolMode = "disk")

        val (aliceRepo, aliceSvc) = createVaultAndService("alice", "Alice-Android", defaultRelayOverride = { diskRelayUri })
        val (bobRepo, bobSvc) = createVaultAndService("bob", "Bob-NewPhone")

        // Wait for live relay readiness via testRelay
        withTimeout(10_000L) {
            while (aliceSvc.testRelay(diskRelayUri).isFailure) {
                delay(100L)
            }
        }
        val pingUi = aliceSvc.testRelay().getOrThrow()
        assertEquals("standard", pingUi.profile)
        assertEquals("disk", pingUi.spoolMode)

        // 1. 1-Scan Custom Relay Migration (Mode 1 QR One-Shot Send + includeRelay=true)
        assertTrue(aliceSvc.saveCustomRelayUri(diskRelayUri).isSuccess)
        aliceRepo.saveServer(ServerConfig(id = "srv-1", name = "Prod-VPS", host = "10.10.0.1", username = "ubuntu", password = "pw1"))
        aliceRepo.saveSshKey(SSHKeyRecord(id = "key-1", name = "MainEd", fingerprint = "SHA256:fp1", privateKeyPem = "PEM-DATA"))
        aliceRepo.saveShortcut(CommandShortcut(id = "sc-1", name = "Logs", command = "journalctl -xe"))

        aliceSvc.startOneShotSend(mode = 1, includeRelay = true)
        awaitCondition { aliceSvc.uiState.value.pairUri.startsWith("synctool://pair?") }
        val oneScanUri = aliceSvc.uiState.value.pairUri
        assertTrue(oneScanUri.contains("&u="))

        // Brand-new Bob has NO custom relay configured yet -> 1 QR scan adopts custom relay & receives preview!
        bobSvc.startReceive(oneScanUri)
        awaitCondition { bobSvc.uiState.value.flowStage == SyncFlowStage.READY_PREVIEW }
        assertEquals("custom", bobSvc.uiState.value.relay.mode)
        assertNotNull(bobSvc.uiState.value.pendingPreview)
        assertEquals(1, bobSvc.uiState.value.pendingPreview!!.serverCount)
        assertEquals(1, bobSvc.uiState.value.pendingPreview!!.keyCount)

        val oneShotSummary = bobSvc.applyPendingPreview("merge").getOrThrow()
        assertEquals(1, oneShotSummary.serversAdded)
        assertNull(bobSvc.uiState.value.pendingPreview)
        awaitCondition { aliceSvc.uiState.value.flowStage == SyncFlowStage.COMPLETED }

        // 2. Responder SAS Rejection Fail-Closed Gate (`ST-P0-1`)
        aliceSvc.startDeviceBindOffer(mode = 2, includeRelay = false)
        awaitCondition { aliceSvc.uiState.value.roomCode.length == 9 }
        val rejectedRoom = aliceSvc.uiState.value.roomCode
        bobSvc.acceptDeviceBind(rejectedRoom)
        awaitCondition { bobSvc.uiState.value.flowStage == SyncFlowStage.AWAITING_SAS_CONFIRM }
        bobSvc.confirmResponderSas(false)
        awaitCondition { bobSvc.uiState.value.flowStage == SyncFlowStage.ERROR }
        assertTrue(bobSvc.uiState.value.pairedDevices.isEmpty())

        // 3. Mode 2 SAS Device Bind (1 wrong SAS attempt -> remaining=2 -> matching 6-digit SAS -> mutual pairing!)
        aliceSvc.startDeviceBindOffer(mode = 2, includeRelay = false)
        awaitCondition { aliceSvc.uiState.value.roomCode.length == 9 && aliceSvc.uiState.value.flowStage == SyncFlowStage.WAITING_PEER }
        val bindRoom = aliceSvc.uiState.value.roomCode

        bobSvc.acceptDeviceBind(bindRoom)
        awaitCondition {
            bobSvc.uiState.value.flowStage == SyncFlowStage.AWAITING_SAS_CONFIRM &&
                aliceSvc.uiState.value.flowStage == SyncFlowStage.AWAITING_SAS_INPUT
        }
        val responderSas = bobSvc.uiState.value.sasCode
        assertEquals(6, responderSas.length)
        assertEquals(3, aliceSvc.uiState.value.sasAttemptsRemaining)

        // Responder approves SAS; Initiator submits wrong SAS first, then exact SAS
        bobSvc.confirmResponderSas(true)
        val wrongSas = if (responderSas == "000000") "999999" else "000000"
        aliceSvc.submitSasInput(wrongSas)
        awaitCondition { aliceSvc.uiState.value.sasAttemptsRemaining == 2 && aliceSvc.uiState.value.flowStage == SyncFlowStage.AWAITING_SAS_INPUT }
        aliceSvc.submitSasInput(responderSas)

        awaitCondition {
            aliceSvc.uiState.value.flowStage == SyncFlowStage.COMPLETED &&
                bobSvc.uiState.value.flowStage == SyncFlowStage.COMPLETED
        }
        assertEquals(1, aliceSvc.uiState.value.pairedDevices.size)
        assertEquals(1, bobSvc.uiState.value.pairedDevices.size)
        val bobDeviceId = aliceSvc.uiState.value.pairedDevices.single().deviceId

        // 4. Paired 0-RTT Push -> Two-Phase Inbox Preview -> Overwrite Import (preserving syncState!) -> Signed ACK Burn -> Revoke
        aliceRepo.saveServer(ServerConfig(id = "srv-2", name = "DB-Node", host = "10.10.0.2", username = "postgres"))
        assertTrue(aliceSvc.sendToPairedDevice(bobDeviceId, "auto").isSuccess)

        val inboxList = bobSvc.refreshInbox().getOrThrow()
        assertEquals(1, inboxList.size)
        assertEquals(2, inboxList.single().serverCount)

        val itemId = inboxList.single().itemId
        val inboxSummary = bobSvc.applyInboxItem(itemId, "overwrite").getOrThrow()
        assertEquals(2, inboxSummary.serversAdded)
        assertEquals(1, bobRepo.readSyncState().pairedDevices.size) // Overwrite preserved syncState!
        assertTrue(bobSvc.refreshInbox().getOrThrow().isEmpty()) // Relay copy physically burned by signed /ack!

        // Revoke paired device
        assertTrue(aliceSvc.revokePairedDevice(bobDeviceId).isSuccess)
        assertTrue(aliceSvc.uiState.value.pairedDevices.isEmpty())

        // 5. `--spool=off` Relay -> `suggestPipeDeviceId` -> 1-tap `sendToPairedDevice(id, "pipe")`
        val spoolOffRelayUri = startRelayInstance(relayBin, spoolMode = "off")
        val (c1Repo, c1Svc) = createVaultAndService("c1", "Phone-A", defaultRelayOverride = { spoolOffRelayUri })
        val (_, c2Svc) = createVaultAndService("c2", "Phone-B", defaultRelayOverride = { spoolOffRelayUri })
        withTimeout(10_000L) {
            while (c1Svc.testRelay(spoolOffRelayUri).isFailure) delay(100L)
        }
        c1Repo.saveServer(ServerConfig(id = "srv-pipe", name = "PipeHost", host = "10.20.0.9"))

        c1Svc.startDeviceBindOffer(mode = 1, includeRelay = false)
        awaitCondition { c1Svc.uiState.value.pairUri.startsWith("synctool://pair?") }
        c2Svc.acceptDeviceBind(c1Svc.uiState.value.pairUri)
        awaitCondition { c1Svc.uiState.value.pairedDevices.size == 1 && c2Svc.uiState.value.pairedDevices.size == 1 }

        val c2Id = c1Svc.uiState.value.pairedDevices.single().deviceId
        val autoRes = c1Svc.sendToPairedDevice(c2Id, "auto")
        assertTrue(autoRes.isFailure)
        assertEquals(c2Id, c1Svc.uiState.value.suggestPipeDeviceId)

        aliceSvc.onVaultLocked()
        bobSvc.onVaultLocked()
        c1Svc.onVaultLocked()
        c2Svc.onVaultLocked()
        Unit
    }
}
