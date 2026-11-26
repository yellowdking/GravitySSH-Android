package com.gravityssh.app.vault

import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRepo(file: File = File(tempFolder.root, "vault.gsv"), keyProvider: MasterKeyProvider = InMemoryMasterKeyProvider()): VaultRepository {
        return VaultRepository(file, VaultCrypto(keyProvider))
    }

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

        // Set master password and reopen
        assertTrue(repo.setMasterPassword("", "s3cr3t!").isSuccess)
        val repo2 = createRepo(file, kp)
        repo2.open()
        assertTrue(repo2.state.value.lockState.locked)
        assertTrue(repo2.state.value.lockState.hasPassword)
        assertTrue(repo2.unlock("wrong").isFailure)
        assertTrue(repo2.unlock("s3cr3t!").isSuccess)
        assertFalse(repo2.state.value.lockState.locked)
        assertEquals(6, repo2.state.value.data.templates.size)
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
}
