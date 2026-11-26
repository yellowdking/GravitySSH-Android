package com.gravityssh.app.ssh

import com.gravityssh.app.model.SessionStatus
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.channel.direct.Session
import java.nio.charset.StandardCharsets

internal class SshSessionWorker(
    private val scope: CoroutineScope,
    private val vaultRepository: VaultRepository,
    private val connector: SshConnector,
    private val updateTab: (String, (SessionTab) -> SessionTab) -> Unit,
    private val syncKeepAlive: () -> Unit,
    private val runStartup: (String, String) -> Unit
) {
    fun startConnect(managed: ManagedSession) {
        val sid = managed.sessionId
        managed.connectJob = scope.launch {
            try {
                val data = vaultRepository.state.value.data
                val conn = connector.connect(managed.server, data.servers, data.sshKeys)
                if (managed.closed) { conn.close(); return@launch }
                managed.conn = conn

                val sshSess = conn.client.startSession()
                managed.sshSession = sshSess
                runCatching { sshSess.setEnvVar("LANG", "en_US.UTF-8") }
                runCatching { sshSess.setEnvVar("LC_CTYPE", "en_US.UTF-8") }
                sshSess.allocatePTY("xterm-256color", managed.lastCols, managed.lastRows, 0, 0, emptyMap())
                val shell = sshSess.startShell()
                managed.shell = shell
                runCatching { shell.changeWindowDimensions(managed.lastCols, managed.lastRows, 0, 0) }

                val sftp = runCatching { conn.client.newSFTPClient() }.getOrNull()
                managed.sftpClient = sftp
                val home = runCatching { sftp?.canonicalize(".") }.getOrNull().orEmpty().ifBlank { "/" }
                val startDir = managed.server.defaultRemotePath.trim().ifBlank { home }

                vaultRepository.markServerConnected(managed.server.id)
                updateTab(sid) {
                    it.copy(
                        status = SessionStatus.CONNECTED,
                        sftpReady = sftp != null,
                        homeDir = home,
                        currentDir = startDir,
                        viaSummary = conn.viaSummary,
                        error = null
                    )
                }
                syncKeepAlive()
                startReader(managed, shell)
                startKeepAlive(managed, conn.client)
                startMetrics(managed, conn.client)

                if (managed.server.startupCommand.isNotBlank()) {
                    delay(250)
                    runStartup(sid, managed.server.startupCommand)
                }
            } catch (e: Exception) {
                if (!managed.closed) {
                    val msg = e.message ?: "连接失败"
                    managed.terminal.onRemoteOutput("\r\n\u001B[31m[连接失败] $msg\u001B[0m\r\n")
                    updateTab(sid) { it.copy(status = SessionStatus.ERROR, error = msg) }
                    syncKeepAlive()
                }
            }
        }
    }

    private fun startReader(managed: ManagedSession, shell: Session.Shell) {
        managed.readerJob = scope.launch {
            val buf = ByteArray(8192)
            try {
                val input = shell.inputStream
                while (isActive && !managed.closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) managed.terminal.onRemoteOutput(String(buf, 0, n, StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {
                // Stream closed
            } finally {
                if (!managed.closed) {
                    updateTab(managed.sessionId) {
                        if (it.status == SessionStatus.CONNECTED) it.copy(status = SessionStatus.DISCONNECTED) else it
                    }
                    syncKeepAlive()
                }
            }
        }
    }

    private fun startKeepAlive(managed: ManagedSession, client: SSHClient) {
        val interval = managed.server.keepAliveSeconds.takeIf { it > 0 }
            ?: vaultRepository.state.value.data.settings.defaultKeepAliveSeconds
        if (interval <= 0) return
        managed.keepAliveJob = scope.launch {
            var failures = 0
            while (isActive && !managed.closed) {
                delay(interval * 1000L)
                val ok = runCatching {
                    client.isConnected && client.transport.isRunning && run {
                        client.transport.write(SSHPacket(Message.IGNORE))
                        true
                    }
                }.getOrDefault(false)
                if (ok) failures = 0 else if (++failures >= 3) {
                    updateTab(managed.sessionId) {
                        it.copy(status = SessionStatus.DISCONNECTED, error = "心跳超时，连接已断开")
                    }
                    break
                }
            }
        }
    }

    private fun startMetrics(managed: ManagedSession, client: SSHClient) {
        managed.metricsJob = scope.launch {
            delay(800)
            while (isActive && !managed.closed && client.isConnected) {
                if (vaultRepository.state.value.data.settings.monitorBarEnabled) {
                    managed.metricsSampler.sample(client)?.let { m ->
                        updateTab(managed.sessionId) { it.copy(metrics = m) }
                    }
                }
                delay(managed.metricsSampler.recommendedDelayMs())
            }
        }
    }
}
