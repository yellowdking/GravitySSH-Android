package com.gravityssh.app.vault

import com.gravityssh.app.InMemoryMasterKeyProvider
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.JumpHostConfig
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.SSH_PORT_PLACEHOLDER
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.isSshPortTemplate
import com.gravityssh.app.model.resolveSshPortCommand
import com.gravityssh.app.ui.sanitizeUploadDisplayName
import com.gravityssh.app.ui.sync.isValidGravitySshQrPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey

class VaultRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRepo(
        file: File = File(tempFolder.root, "vault.gsv"),
        keyProvider: MasterKeyProvider = InMemoryMasterKeyProvider()
    ): VaultRepository = VaultRepository(file, VaultCrypto(keyProvider))

    @Test
    fun masterPasswordLockUnlockAndPresets() {
        val file = File(tempFolder.root, "vault.gsv")
        val kp = InMemoryMasterKeyProvider()
        val repo = createRepo(file, kp)
        repo.open()

        assertFalse(repo.state.value.lockState.locked)
        assertEquals(6, repo.state.value.data.templates.size)

        // Delete one preset, edit another, then loadPresetTemplates should restore deleted without overwriting edited
        val edited = repo.state.value.data.templates.first { it.presetId == "tpl-bbr" }.copy(name = "Custom BBR")
        repo.saveTemplate(edited)
        repo.deleteTemplate("tpl-swap")
        assertEquals(5, repo.state.value.data.templates.size)

        val loadRes = repo.loadPresetTemplates()
        assertEquals(1, loadRes.added)
        assertEquals(5, loadRes.skipped)
        assertEquals("Custom BBR", repo.state.value.data.templates.first { it.presetId == "tpl-bbr" }.name)

        // Set master password, perform additional writes using cached key, then lock and reopen
        assertTrue(repo.setMasterPassword("", "s3cr3t!").isSuccess)
        repo.saveServer(ServerConfig(id = "s-pw", name = "PW Host", host = "10.0.0.9"))
        repo.recordCommand("ls -la", "s-pw", "PW Host", "sess-1")

        val lockCallbacks = AtomicInteger(0)
        repo.addOnLockListener { lockCallbacks.incrementAndGet() }
        repo.lockNow()
        assertEquals(1, lockCallbacks.get())
        assertTrue(repo.state.value.lockState.locked)
        assertTrue(repo.state.value.data.servers.isEmpty())

        val repo2 = createRepo(file, kp)
        repo2.open()
        assertTrue(repo2.state.value.lockState.locked)
        assertTrue(repo2.state.value.lockState.hasPassword)
        assertTrue(repo2.unlock("wrong").isFailure)
        assertTrue(repo2.unlock("s3cr3t!").isSuccess)
        assertFalse(repo2.state.value.lockState.locked)
        assertEquals(6, repo2.state.value.data.templates.size)
        assertEquals(1, repo2.state.value.data.servers.size)
        assertEquals("ls -la", repo2.state.value.data.history.first().command)
    }

    @Test
    fun biometricLockMutuallyExclusiveWithMasterPassword() {
        val file = File(tempFolder.root, "vault-bio.gsv")
        val kp = InMemoryMasterKeyProvider()
        val repo = createRepo(file, kp)
        repo.open()

        // Without master password, biometric lock works across lock/reopen/unlock
        repo.updateSettings { it.copy(biometricLockEnabled = true) }
        assertTrue(repo.state.value.lockState.biometricEnabled)
        assertFalse(repo.state.value.lockState.hasPassword)

        repo.lockNow()
        assertTrue(repo.state.value.lockState.locked)
        assertTrue(repo.state.value.lockState.biometricEnabled)
        assertTrue(repo.unlockWithBiometric().isSuccess)
        assertFalse(repo.state.value.lockState.locked)

        val reopenedBio = createRepo(file, kp)
        reopenedBio.open()
        assertTrue(reopenedBio.state.value.lockState.locked)
        assertTrue(reopenedBio.state.value.lockState.biometricEnabled)
        assertTrue(reopenedBio.unlockWithBiometric().isSuccess)

        // Setting a master password clears biometricLockEnabled and suppresses biometricEnabled
        assertTrue(reopenedBio.setMasterPassword("", "pw-123").isSuccess)
        assertTrue(reopenedBio.state.value.lockState.hasPassword)
        assertFalse(reopenedBio.state.value.lockState.biometricEnabled)
        assertFalse(reopenedBio.state.value.data.settings.biometricLockEnabled)

        // Attempting to re-enable biometric while master password is set is ignored
        reopenedBio.updateSettings { it.copy(biometricLockEnabled = true) }
        assertFalse(reopenedBio.state.value.lockState.biometricEnabled)
        assertFalse(reopenedBio.state.value.data.settings.biometricLockEnabled)

        // Locking with master password must never advertise biometric unlock
        reopenedBio.lockNow()
        assertTrue(reopenedBio.state.value.lockState.locked)
        assertTrue(reopenedBio.state.value.lockState.hasPassword)
        assertFalse(reopenedBio.state.value.lockState.biometricEnabled)
    }

    @Test
    fun syncImportMergeAndOverwrite() {
        val repo = createRepo()
        repo.open()
        repo.saveServer(ServerConfig(id = "s-local", name = "Local", host = "10.0.0.1", port = 22, username = "root"))
        repo.saveSshKey(SSHKeyRecord(id = "k-local", name = "Key1", fingerprint = "SHA256:aaa"))
        repo.updateSettings { it.copy(terminalFontSize = 16) }

        val incoming = SyncBundle(
            servers = listOf(
                ServerConfig(id = "s-local", name = "Local Updated", host = "10.0.0.1", port = 22, username = "root"),
                ServerConfig(id = "s-remote", name = "Remote", host = "10.0.0.2", port = 2222, username = "ubuntu")
            ),
            sshKeys = listOf(SSHKeyRecord(id = "k-new", name = "Key2", fingerprint = "SHA256:bbb")),
            shortcuts = listOf(CommandShortcut(id = "sc-1", name = "PS", command = "docker ps")),
            templates = listOf(BuiltinTemplate(id = "tpl-x", name = "Custom", command = "echo hi"))
        )

        val mergeSum = repo.importSyncBundle(incoming, SyncImportMode.MERGE)
        assertEquals(1, mergeSum.serversAdded)
        assertEquals(1, mergeSum.serversUpdated)
        assertEquals(2, repo.state.value.data.servers.size)
        assertEquals(2, repo.state.value.data.sshKeys.size)
        assertEquals(16, repo.state.value.data.settings.terminalFontSize)

        val overwriteSum = repo.importSyncBundle(
            SyncBundle(servers = listOf(ServerConfig(id = "only-1", name = "Only", host = "1.2.3.4", port = 22, username = "root"))),
            SyncImportMode.OVERWRITE
        )
        assertEquals(1, overwriteSum.serversAdded)
        assertEquals(1, repo.state.value.data.servers.size)
        assertEquals("only-1", repo.state.value.data.servers[0].id)
        assertEquals(16, repo.state.value.data.settings.terminalFontSize)
    }

    @Test
    fun windowsToAndroidMergeRemapsForeignKeysAndPreservesLocalSecrets() {
        val repo = createRepo()
        repo.open()
        repo.saveSshKey(
            SSHKeyRecord(
                id = "k-android",
                name = "Prod Key",
                fingerprint = "SHA256:SHARED-FP",
                publicKey = "ssh-ed25519 AAAAlocal",
                privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nLOCAL-SECRET\n-----END OPENSSH PRIVATE KEY-----",
                passphrase = "local-key-passphrase"
            )
        )
        repo.saveServer(
            ServerConfig(
                id = "srv-android-jump",
                name = "Bastion Local",
                host = "bastion.example.com",
                port = 22,
                username = "ops",
                authType = AuthType.PASSWORD,
                password = "local-bastion-password",
                proxy = ProxyConfig(type = ProxyType.SOCKS5, host = "127.0.0.1", port = 1080, username = "p", password = "local-proxy-pw"),
                jumpHost = JumpHostConfig(mode = JumpMode.CUSTOM, host = "edge.local", port = 22, password = "local-jump-pw")
            )
        )
        repo.saveTemplate(
            BuiltinTemplate(
                id = "tpl-local-bbr",
                presetId = "preset-bbr-keep",
                name = "Custom BBR Check",
                command = "sysctl net.ipv4.tcp_congestion_control"
            )
        )

        val windowsBundle = SyncBundle(
            schemaVersion = 1,
            sourcePlatform = "windows",
            sshKeys = listOf(
                SSHKeyRecord(
                    id = "k-win",
                    name = "Prod Key Win",
                    fingerprint = "SHA256:shared-fp",
                    privateKeyPem = "",
                    passphrase = ""
                )
            ),
            servers = listOf(
                // App server listed BEFORE jump server to verify order-independent serverIdMap remapping
                ServerConfig(
                    id = "srv-win-app",
                    name = "App Node",
                    host = " 10.0.1.50 ",
                    port = 99999,
                    username = "deploy",
                    authType = "invalid_auth_type",
                    sshKeyId = "k-win",
                    jumpHost = JumpHostConfig(
                        mode = JumpMode.SERVER,
                        serverId = "srv-win-jump",
                        sshKeyId = "k-win"
                    )
                ),
                // Matches srv-android-jump by (host, port, username) with empty local secrets
                ServerConfig(
                    id = "srv-win-jump",
                    name = "Bastion Win",
                    host = "BASTION.EXAMPLE.COM",
                    port = 22,
                    username = "ops",
                    authType = AuthType.KEY,
                    password = "",
                    sshKeyId = "k-win",
                    proxy = ProxyConfig(type = ProxyType.SOCKS5, host = "127.0.0.1", port = 1080, username = "p", password = ""),
                    jumpHost = JumpHostConfig(mode = JumpMode.CUSTOM, host = "edge.local", port = 22, password = "")
                ),
                // Blank host must be skipped
                ServerConfig(id = "srv-blank", name = "Broken", host = "   ", port = 22)
            ),
            templates = listOf(
                BuiltinTemplate(
                    id = "tpl-win-bbr",
                    presetId = "",
                    name = "Custom BBR Check",
                    command = "sysctl -a | grep bbr"
                )
            )
        )

        val summary = repo.importSyncBundle(windowsBundle, SyncImportMode.MERGE)
        assertEquals(1, summary.keysUpdated)
        assertEquals(0, summary.keysAdded)
        assertEquals(1, summary.serversAdded)
        assertEquals(1, summary.serversUpdated)

        val data = repo.state.value.data
        assertEquals(1, data.sshKeys.size)
        val mergedKey = data.sshKeys.single()
        assertEquals("k-android", mergedKey.id)
        assertTrue(mergedKey.privateKeyPem.contains("LOCAL-SECRET"))
        assertEquals("local-key-passphrase", mergedKey.passphrase)

        assertEquals(2, data.servers.size)
        val mergedJump = data.servers.first { it.id == "srv-android-jump" }
        assertEquals("local-bastion-password", mergedJump.password)
        assertEquals("local-proxy-pw", mergedJump.proxy.password)
        assertEquals("local-jump-pw", mergedJump.jumpHost.password)
        assertEquals("k-android", mergedJump.sshKeyId)

        val appServer = data.servers.first { it.id == "srv-win-app" }
        assertEquals("10.0.1.50", appServer.host)
        assertEquals(65535, appServer.port)
        assertEquals(AuthType.PASSWORD, appServer.authType)
        assertEquals("k-android", appServer.sshKeyId)
        assertEquals("k-android", appServer.jumpHost.sshKeyId)
        assertEquals("srv-android-jump", appServer.jumpHost.serverId)

        val mergedTpl = data.templates.first { it.name == "Custom BBR Check" }
        assertEquals("preset-bbr-keep", mergedTpl.presetId)
    }

    @Test
    fun syncImportRejectsUnsupportedSchemaVersion() {
        val repo = createRepo()
        repo.open()
        val err = runCatching {
            repo.importSyncBundle(SyncBundle(schemaVersion = 99), SyncImportMode.MERGE)
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message.orEmpty().contains("schemaVersion=99"))
    }

    @Test
    fun vaultCryptoV1BackwardCompatAndV2HeaderAuthentication() {
        val kp = InMemoryMasterKeyProvider()
        val crypto = VaultCrypto(kp)
        val payload = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)

        // Legacy v1 blobs (keyless and password) still open cleanly
        val v1Keyless = sealV1ForTest(kp, payload, password = null)
        assertEquals(VaultCrypto.VERSION_V1, v1Keyless[4])
        assertArrayEquals(payload, crypto.open(v1Keyless, password = null))

        val v1Password = sealV1ForTest(kp, payload, password = "pw-v1")
        assertEquals(VaultCrypto.VERSION_V1, v1Password[4])
        assertArrayEquals(payload, crypto.open(v1Password, password = "pw-v1"))

        // New v2 blobs open cleanly and authenticate header bytes
        val v2Blob = crypto.seal(payload, password = "pw-v2")
        assertEquals(VaultCrypto.VERSION_V2, v2Blob[4])
        assertArrayEquals(payload, crypto.open(v2Blob, password = "pw-v2"))

        // Flipping mode byte in v2 header must fail GCM authentication
        val tampered = v2Blob.copyOf()
        tampered[5] = VaultCrypto.MODE_KEYSTORE
        val tamperRes = runCatching { crypto.open(tampered, password = null) }
        assertTrue(tamperRes.isFailure)
    }

    @Test
    fun atomicSaveRollbackSafetyAndCorruptVaultRecovery() {
        val file = File(tempFolder.root, "vault.gsv")
        val delegate = InMemoryMasterKeyProvider()
        val shouldFail = AtomicBoolean(false)
        val flakyProvider = object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey {
                if (shouldFail.get()) throw IllegalStateException("Keystore temporarily unavailable")
                return delegate.getOrCreateKey()
            }
        }

        val repo = createRepo(file, flakyProvider)
        repo.open()
        repo.saveServer(ServerConfig(id = "s-good", name = "Good", host = "10.0.0.1"))

        // Simulate write failure -> in-memory state must roll back cleanly
        shouldFail.set(true)
        val failedSave = runCatching {
            repo.saveServer(ServerConfig(id = "s-bad", name = "Bad", host = "10.0.0.2"))
        }
        assertTrue(failedSave.isFailure)
        assertEquals(1, repo.state.value.data.servers.size)
        assertEquals("s-good", repo.state.value.data.servers.single().id)

        // Verify disk copy remained intact
        shouldFail.set(false)
        val reopened = createRepo(file, delegate)
        reopened.open()
        assertEquals(1, reopened.state.value.data.servers.size)
        assertEquals("s-good", reopened.state.value.data.servers.single().id)

        // Corrupt vault file -> open() must not crash and must expose startupError
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val brokenRepo = createRepo(file, delegate)
        brokenRepo.open()
        assertTrue(brokenRepo.state.value.lockState.locked)
        assertNotNull(brokenRepo.state.value.lockState.startupError)

        // Reset backs up corrupt file and recovers
        assertTrue(brokenRepo.resetCorruptedVault().isSuccess)
        assertFalse(brokenRepo.state.value.lockState.locked)
        assertNull(brokenRepo.state.value.lockState.startupError)
        assertTrue(tempFolder.root.listFiles().orEmpty().any { it.name.startsWith("vault.gsv.corrupt.") })
    }

    @Test
    fun knownHostsStoresRfc4253AlgorithmUpdatesFlowAndBacksUpCorruptFile() {
        val file = File(tempFolder.root, "known_hosts.json")
        val mgr = KnownHostsManager(file)
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        assertTrue(mgr.verify("server.local", 22, kp.public))
        assertEquals(1, mgr.knownHosts.value.size)
        assertEquals("ssh-rsa", mgr.knownHosts.value.single().algorithm)
        assertEquals(listOf("ssh-rsa"), mgr.findExistingAlgorithms("server.local", 22))
        assertNull(mgr.lastError.value)

        // Corrupt known_hosts.json -> reload backs it up with timestamp instead of silently discarding
        file.writeText("{not-valid-json")
        val mgr2 = KnownHostsManager(file)
        assertTrue(mgr2.knownHosts.value.isEmpty())
        assertNotNull(mgr2.lastError.value)
        assertTrue(tempFolder.root.listFiles().orEmpty().any { it.name.startsWith("known_hosts.json.corrupt.") })
    }

    @Test
    fun knownHostsRollsBackOnDiskFailureAndPreservesStateOnIoLoadError() {
        val file = File(tempFolder.root, "kh-rollback.json")
        val mgr = KnownHostsManager(file)
        val kp1 = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val kp2 = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        assertTrue(mgr.verify("a.local", 22, kp1.public))
        assertEquals(1, mgr.knownHosts.value.size)

        // Replace target file with a non-empty directory so atomic move fails deterministically across OSes
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        val blocker = File(file, "blocker.txt").apply { writeText("lock") }

        assertTrue(runCatching { mgr.verify("b.local", 22, kp2.public) }.isFailure)
        assertEquals(1, mgr.knownHosts.value.size)
        assertEquals("a.local", mgr.knownHosts.value.single().host)
        assertNotNull(mgr.lastError.value)

        assertTrue(runCatching { mgr.clearHost("a.local", 22) }.isFailure)
        assertEquals(1, mgr.knownHosts.value.size)

        assertTrue(runCatching { mgr.clearHostByKey("a.local:22") }.isFailure)
        assertEquals(1, mgr.knownHosts.value.size)

        assertTrue(runCatching { mgr.clearAll() }.isFailure)
        assertEquals(1, mgr.knownHosts.value.size)

        // Clean up blocker and verify normal operation + error clearing resumes
        assertTrue(blocker.delete())
        assertTrue(file.delete())
        mgr.clearLastError()
        assertNull(mgr.lastError.value)
        assertEquals(1, mgr.clearAll())
        assertTrue(mgr.knownHosts.value.isEmpty())

        // Transient IOException on load (directory at storageFile) must not move/delete file or allow TOFU
        val ioDir = File(tempFolder.root, "kh-ioerr.json").apply {
            mkdir()
            File(this, "keep").writeText("present")
        }
        val mgrIo = KnownHostsManager(ioDir)
        assertTrue(ioDir.exists())
        assertNotNull(mgrIo.lastError.value)
        assertFalse(mgrIo.verify("evil.local", 22, kp1.public))
    }

    @Test
    fun vaultInitializedFlagTransitionsOnOpenAndCorruptOpen() {
        val file = File(tempFolder.root, "vault-init.gsv")
        val kp = InMemoryMasterKeyProvider()
        val repo = createRepo(file, kp)
        assertFalse(repo.state.value.lockState.initialized)
        assertTrue(repo.state.value.lockState.locked)

        repo.open()
        assertTrue(repo.state.value.lockState.initialized)
        assertFalse(repo.state.value.lockState.locked)

        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        val broken = createRepo(file, kp)
        assertFalse(broken.state.value.lockState.initialized)
        broken.open()
        assertTrue(broken.state.value.lockState.initialized)
        assertTrue(broken.state.value.lockState.locked)
        assertNotNull(broken.state.value.lockState.startupError)
    }

    @Test
    fun cachedPasswordKeyVerifiesConstantTimeWithoutPlaintextRetention() {
        val file = File(tempFolder.root, "vault-pw-verify.gsv")
        val kp = InMemoryMasterKeyProvider()
        val crypto = VaultCrypto(kp)
        val cached = crypto.deriveForNewPassword("Alpha#123")
        assertTrue(crypto.verifyPassword("Alpha#123", cached))
        assertFalse(crypto.verifyPassword("Alpha#124", cached))
        cached.clear()
        assertTrue(runCatching { crypto.verifyPassword("Alpha#123", cached) }.isFailure)

        val repo = createRepo(file, kp)
        repo.open()
        assertTrue(repo.setMasterPassword("", "Alpha#123").isSuccess)
        repo.lockNow()

        val repo2 = createRepo(file, kp)
        repo2.open()
        assertTrue(repo2.unlock("Alpha#123").isSuccess)
        assertTrue(repo2.setMasterPassword("WrongOld", "Beta#456").isFailure)
        assertTrue(repo2.setMasterPassword("Alpha#123", "Beta#456").isSuccess)
        repo2.lockNow()

        val repo3 = createRepo(file, kp)
        repo3.open()
        assertTrue(repo3.unlock("Alpha#123").isFailure)
        assertTrue(repo3.unlock("Beta#456").isSuccess)
    }

    @Test
    fun serverValidationRejectsInvalidProxyJumpKeyCyclesAndAlignsBounds() {
        val repo = createRepo()
        repo.open()
        repo.saveSshKey(SSHKeyRecord(id = "k-1", name = "Main Key", publicKey = "ssh-ed25519 AAA"))

        val srvA = repo.saveServer(
            ServerConfig(
                id = "srv-a",
                name = "A",
                host = " 10.0.0.1 ",
                port = 22,
                username = " ops ",
                timeoutSeconds = 1,
                keepAliveSeconds = 450
            )
        )
        assertEquals("10.0.0.1", srvA.host)
        assertEquals("ops", srvA.username)
        assertEquals(3, srvA.timeoutSeconds)
        assertEquals(450, srvA.keepAliveSeconds)

        val srvB = repo.saveServer(
            ServerConfig(
                id = "srv-b",
                name = "B",
                host = "10.0.0.2",
                jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-a"),
                timeoutSeconds = 999,
                keepAliveSeconds = 999
            )
        )
        assertEquals(120, srvB.timeoutSeconds)
        assertEquals(600, srvB.keepAliveSeconds)

        val srvC = repo.saveServer(
            ServerConfig(
                id = "srv-c",
                name = "C",
                host = "10.0.0.3",
                jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "srv-b")
            )
        )

        // Multi-hop jump cycle checks: A -> C -> B -> A must be rejected
        assertTrue(VaultCrudOps.wouldCreateJumpCycle("srv-a", "srv-a", repo.state.value.data.servers))
        assertTrue(VaultCrudOps.wouldCreateJumpCycle("srv-a", "srv-b", repo.state.value.data.servers))
        assertTrue(VaultCrudOps.wouldCreateJumpCycle("srv-a", "srv-c", repo.state.value.data.servers))
        assertFalse(VaultCrudOps.wouldCreateJumpCycle("srv-d", "srv-c", repo.state.value.data.servers))
        assertTrue(
            runCatching {
                repo.saveServer(srvA.copy(jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = srvC.id)))
            }.isFailure
        )

        // Blank proxy host rejected when proxy active
        assertTrue(
            runCatching {
                repo.saveServer(
                    ServerConfig(
                        id = "bad-proxy",
                        host = "10.0.0.9",
                        proxy = ProxyConfig(type = ProxyType.SOCKS5, host = "  ", port = 1080)
                    )
                )
            }.isFailure
        )

        // Blank custom jump host rejected
        assertTrue(
            runCatching {
                repo.saveServer(
                    ServerConfig(
                        id = "bad-jump-custom",
                        host = "10.0.0.9",
                        jumpHost = JumpHostConfig(mode = JumpMode.CUSTOM, host = "   ", port = 22)
                    )
                )
            }.isFailure
        )

        // Nonexistent jump serverId rejected
        assertTrue(
            runCatching {
                repo.saveServer(
                    ServerConfig(
                        id = "bad-jump-srv",
                        host = "10.0.0.9",
                        jumpHost = JumpHostConfig(mode = JumpMode.SERVER, serverId = "missing")
                    )
                )
            }.isFailure
        )

        // Blank or nonexistent sshKeyId rejected when key auth enabled
        assertTrue(
            runCatching {
                repo.saveServer(
                    ServerConfig(id = "bad-key-empty", host = "10.0.0.9", authType = AuthType.KEY, sshKeyId = "")
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                repo.saveServer(
                    ServerConfig(id = "bad-key-missing", host = "10.0.0.9", authType = AuthType.KEY, sshKeyId = "k-missing")
                )
            }.isFailure
        )
        // Existing sshKeyId succeeds
        val validKeySrv = repo.saveServer(
            ServerConfig(id = "good-key", host = "10.0.0.10", authType = AuthType.KEY, sshKeyId = "k-1")
        )
        assertEquals("k-1", validKeySrv.sshKeyId)
    }

    @Test
    fun uploadDisplayNameSanitizationAndQrPrefixValidation() {
        assertEquals("report.pdf", sanitizeUploadDisplayName("report.pdf"))
        assertEquals("report.pdf", sanitizeUploadDisplayName("nested/path/report.pdf"))
        assertEquals("report.pdf", sanitizeUploadDisplayName("C:\\temp\\report.pdf"))
        assertEquals(".bashrc", sanitizeUploadDisplayName(".bashrc"))
        assertEquals("upload.bin", sanitizeUploadDisplayName("."))
        assertEquals("upload.bin", sanitizeUploadDisplayName(".."))
        assertEquals("upload.bin", sanitizeUploadDisplayName("../../.."))
        assertEquals("upload.bin", sanitizeUploadDisplayName("dir/."))
        assertEquals("upload.bin", sanitizeUploadDisplayName("evil\u0000.txt"))
        assertEquals("upload.bin", sanitizeUploadDisplayName("   "))
        assertEquals("upload.bin", sanitizeUploadDisplayName(null))

        assertTrue(isValidGravitySshQrPayload("gssh-sync://v1?code=K7M9-P4X2"))
        assertTrue(isValidGravitySshQrPayload("  GSSH-SYNC://v1?code=K7M9P4X2  "))
        assertFalse(isValidGravitySshQrPayload("https://example.com"))
        assertFalse(isValidGravitySshQrPayload("K7M9-P4X2"))
        assertFalse(isValidGravitySshQrPayload("gssh-sync://v1?code=BAD"))
        assertFalse(isValidGravitySshQrPayload("gssh-sync://v1?code=0000-1111"))
    }

    @Test
    fun keystoreLossOnPasswordAndBiometricVaultSetsStartupErrorAndRecovers() {
        val pwFile = File(tempFolder.root, "vault-ks-pw.gsv")
        val kpOriginal = InMemoryMasterKeyProvider()
        val kpReplaced = InMemoryMasterKeyProvider()

        val repo = createRepo(pwFile, kpOriginal)
        repo.open()
        assertTrue(repo.setMasterPassword("", "s3cr3t!").isSuccess)
        repo.saveServer(ServerConfig(id = "s1", name = "Prod", host = "10.0.0.1"))
        repo.lockNow()

        // Wrong password with intact keystore -> plain SecurityException, startupError stays null
        val intactRepo = createRepo(pwFile, kpOriginal)
        intactRepo.open()
        val wrongPwRes = intactRepo.unlock("wrong-pw")
        assertTrue(wrongPwRes.isFailure)
        assertFalse(wrongPwRes.exceptionOrNull() is VaultKeystoreException)
        assertNull(intactRepo.state.value.lockState.startupError)

        // Replaced keystore key on password vault -> VaultKeystoreException + startupError populated
        val lostKeyPwRepo = createRepo(pwFile, kpReplaced)
        lostKeyPwRepo.open()
        assertTrue(lostKeyPwRepo.state.value.lockState.locked)
        assertTrue(lostKeyPwRepo.state.value.lockState.hasPassword)
        val ksPwRes = lostKeyPwRepo.unlock("s3cr3t!")
        assertTrue(ksPwRes.isFailure)
        assertTrue(ksPwRes.exceptionOrNull() is VaultKeystoreException)
        assertNotNull(lostKeyPwRepo.state.value.lockState.startupError)
        assertTrue(lostKeyPwRepo.resetCorruptedVault().isSuccess)
        assertFalse(lostKeyPwRepo.state.value.lockState.locked)
        assertNull(lostKeyPwRepo.state.value.lockState.startupError)

        // Replaced keystore key on biometric vault -> VaultKeystoreException + startupError populated
        val bioFile = File(tempFolder.root, "vault-ks-bio.gsv")
        val bioRepo = createRepo(bioFile, kpOriginal)
        bioRepo.open()
        bioRepo.updateSettings { it.copy(biometricLockEnabled = true) }
        bioRepo.lockNow()

        val lostKeyBioRepo = createRepo(bioFile, kpReplaced)
        lostKeyBioRepo.open()
        assertTrue(lostKeyBioRepo.state.value.lockState.biometricEnabled)
        val ksBioRes = lostKeyBioRepo.unlockWithBiometric()
        assertTrue(ksBioRes.isFailure)
        assertTrue(ksBioRes.exceptionOrNull() is VaultKeystoreException)
        assertNotNull(lostKeyBioRepo.state.value.lockState.startupError)
        assertTrue(lostKeyBioRepo.resetCorruptedVault().isSuccess)
        assertFalse(lostKeyBioRepo.state.value.lockState.locked)
    }

    @Test
    fun masterPasswordRejectsWhitespaceAndEmptyCreationWhileAllowingRemoval() {
        val repo = createRepo()
        repo.open()

        assertTrue(repo.setMasterPassword("", "").isFailure)
        assertTrue(repo.setMasterPassword("", "   ").isFailure)
        assertTrue(repo.setMasterPassword("", "\t \n").isFailure)
        assertFalse(repo.state.value.lockState.hasPassword)

        assertTrue(repo.setMasterPassword("", "valid-pw").isSuccess)
        assertTrue(repo.state.value.lockState.hasPassword)

        // Cannot replace existing password with whitespace-only
        assertTrue(repo.setMasterPassword("valid-pw", "   ").isFailure)
        assertTrue(repo.state.value.lockState.hasPassword)

        // Removing existing password with empty string still succeeds
        assertTrue(repo.setMasterPassword("valid-pw", "").isSuccess)
        assertFalse(repo.state.value.lockState.hasPassword)
    }

    @Test
    fun lockNowIsIdempotentAndDoesNotRefireCallbacks() {
        val repo = createRepo()
        repo.open()
        assertTrue(repo.setMasterPassword("", "pw-idempotent").isSuccess)

        val callbacks = AtomicInteger(0)
        repo.addOnLockListener { callbacks.incrementAndGet() }

        repo.lockNow()
        assertTrue(repo.state.value.lockState.locked)
        assertEquals(1, callbacks.get())

        // Calling lockNow again while already locked must not re-fire callbacks
        repo.lockNow()
        assertTrue(repo.state.value.lockState.locked)
        assertEquals(1, callbacks.get())
    }

    @Test
    fun builtinTemplatesUseSubshellsAndHardenedSshPortRollback() {
        val all = BuiltinOpsPresets.all()
        assertEquals(6, all.size)
        all.forEach { tpl ->
            val trimmed = tpl.command.trim()
            assertTrue("${tpl.id} must run inside a subshell", trimmed.startsWith("(") && trimmed.endsWith(")"))
        }

        val sshPortTpl = all.first { it.presetId == "tpl-ssh-port" }
        assertTrue(isSshPortTemplate(sshPortTpl))
        assertFalse(sshPortTpl.command.contains("read NEW_PORT"))
        assertTrue(sshPortTpl.command.contains(SSH_PORT_PLACEHOLDER))
        assertTrue(sshPortTpl.command.contains("sudo touch /run/gssh_port_pending"))
        assertTrue(sshPortTpl.command.contains("sudo nohup sh -c"))

        val resolved = resolveSshPortCommand(sshPortTpl.command, 2222)
        assertFalse(resolved.contains(SSH_PORT_PLACEHOLDER))
        assertTrue(resolved.contains("NEW_PORT=\"2222\""))

        // Legacy template with interactive read NEW_PORT also resolves safely into a subshell
        val legacyResolved = resolveSshPortCommand("printf \"port: \"; read NEW_PORT\necho \$NEW_PORT", 22022)
        assertFalse(legacyResolved.contains("read NEW_PORT"))
        assertTrue(legacyResolved.contains("NEW_PORT=\"22022\""))
        assertTrue(legacyResolved.trim().startsWith("(") && legacyResolved.trim().endsWith(")"))
    }

    private fun sealV1ForTest(
        keyProvider: MasterKeyProvider,
        plaintext: ByteArray,
        password: String? = null
    ): ByteArray {
        val rng = SecureRandom()
        val (mode, innerPayload) = if (!password.isNullOrEmpty()) {
            val salt = ByteArray(16).also { rng.nextBytes(it) }
            val iv = ByteArray(12).also { rng.nextBytes(it) }
            val keyBytes = CryptoUtils.pbkdf2Sha256(password, salt, 200_000, 256)
            val enc = try {
                CryptoUtils.aesGcmEncrypt(keyBytes, iv, plaintext, "gssh-android-vault-pw-v1".toByteArray(Charsets.UTF_8))
            } finally {
                CryptoUtils.zero(keyBytes)
            }
            val inner = ByteArrayOutputStream(salt.size + iv.size + enc.size).apply {
                write(salt)
                write(iv)
                write(enc)
            }.toByteArray()
            VaultCrypto.MODE_PASSWORD to inner
        } else {
            VaultCrypto.MODE_KEYSTORE to plaintext
        }
        val cipher = Cipher.getInstance(CryptoUtils.AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateKey())
        cipher.updateAAD("gssh-android-vault-v1".toByteArray(Charsets.UTF_8))
        val outerIv = cipher.iv
        val outerCt = cipher.doFinal(innerPayload)
        return ByteArrayOutputStream(7 + outerIv.size + outerCt.size).apply {
            write(byteArrayOf('G'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), 'A'.code.toByte(), VaultCrypto.VERSION_V1, mode, outerIv.size.toByte()))
            write(outerIv)
            write(outerCt)
        }.toByteArray()
    }
}
