package com.gravityssh.app.ssh

import com.gravityssh.app.model.SessionStatus
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class SshSessionWorker(
    private val scope: CoroutineScope,
    private val vaultRepository: VaultRepository,
    private val connector: SshConnector,
    private val updateTab: (String, (SessionTab) -> SessionTab) -> Unit,
    private val syncKeepAlive: () -> Unit,
    private val runStartup: (String, String) -> Unit
) {
    fun startConnect(managed: ManagedSession, gen: Long, closePreviousFirst: Boolean = false) {
        val sid = managed.sessionId
        managed.connectJob = scope.launch {
            var localConn: ConnectedSsh? = null
            var localSess: Session? = null
            var localShell: Session.Shell? = null
            var localSftp: SFTPClient? = null
            var committed = false

            fun closeLocal() {
                runCatching { localSftp?.close() }
                runCatching { localShell?.close() }
                runCatching { localSess?.close() }
                runCatching { localConn?.close() }
                localSftp = null
                localShell = null
                localSess = null
                localConn = null
            }

            try {
                managed.lifecycleMutex.withLock {
                    if (managed.closed || managed.generation.get() != gen) return@launch
                    if (closePreviousFirst) {
                        managed.closeTransportOnly()
                    }
                }

                val data = vaultRepository.state.value.data
                val conn = connector.connect(managed.server, data.servers, data.sshKeys)
                localConn = conn
                if (managed.closed || managed.generation.get() != gen) {
                    closeLocal()
                    return@launch
                }

                val sshSess = conn.client.startSession()
                localSess = sshSess
                runCatching { sshSess.setEnvVar("LANG", "en_US.UTF-8") }
                runCatching { sshSess.setEnvVar("LC_CTYPE", "en_US.UTF-8") }
                sshSess.allocatePTY("xterm-256color", managed.lastCols, managed.lastRows, 0, 0, emptyMap())
                val shell = sshSess.startShell()
                localShell = shell
                runCatching { shell.changeWindowDimensions(managed.lastCols, managed.lastRows, 0, 0) }

                val sftp = runCatching { conn.client.newSFTPClient() }.getOrNull()
                localSftp = sftp
                val home = runCatching { sftp?.canonicalize(".") }.getOrNull().orEmpty().ifBlank { "/" }
                val startDir = managed.server.defaultRemotePath.trim().ifBlank { home }

                managed.lifecycleMutex.withLock {
                    if (managed.closed || managed.generation.get() != gen) {
                        closeLocal()
                        return@launch
                    }
                    managed.conn = conn
                    managed.sshSession = sshSess
                    managed.shell = shell
                    synchronized(managed.sftpLock) {
                        managed.sftpClient = sftp
                    }
                    managed.startWriter(scope, shell)
                    committed = true
                }

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
                startReader(managed, shell, gen)
                startKeepAlive(managed, conn, gen)
                startMetrics(managed, conn.client, gen)

                if (managed.server.startupCommand.isNotBlank()) {
                    delay(250)
                    if (isActive && !managed.closed && managed.generation.get() == gen) {
                        runStartup(sid, managed.server.startupCommand)
                    }
                }
            } catch (e: CancellationException) {
                if (!committed) closeLocal()
                throw e
            } catch (e: Exception) {
                if (!committed) closeLocal()
                if (!managed.closed && managed.generation.get() == gen) {
                    val msg = e.message ?: "连接失败"
                    managed.terminal.onRemoteOutput("\r\n\u001B[31m[连接失败] $msg\u001B[0m\r\n")
                    updateTab(sid) { it.copy(status = SessionStatus.ERROR, error = msg) }
                    syncKeepAlive()
                }
            }
        }
    }

    private fun startReader(managed: ManagedSession, shell: Session.Shell, gen: Long) {
        managed.readerJob = scope.launch {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val rawBuf = ByteArray(8192)
            val inBuf = ByteBuffer.allocate(8192 + 16)
            val outBuf = CharBuffer.allocate(8192 + 16)
            try {
                val input = shell.inputStream
                while (isActive && !managed.closed && managed.generation.get() == gen) {
                    val n = input.read(rawBuf)
                    if (n < 0) {
                        inBuf.flip()
                        outBuf.clear()
                        decoder.decode(inBuf, outBuf, true)
                        decoder.flush(outBuf)
                        outBuf.flip()
                        if (outBuf.hasRemaining()) {
                            managed.terminal.onRemoteOutput(outBuf.toString())
                        }
                        break
                    }
                    if (n > 0) {
                        inBuf.put(rawBuf, 0, n)
                        inBuf.flip()
                        val sb = StringBuilder(n)
                        while (true) {
                            outBuf.clear()
                            val res = decoder.decode(inBuf, outBuf, false)
                            outBuf.flip()
                            if (outBuf.hasRemaining()) {
                                sb.append(outBuf)
                            }
                            if (res.isUnderflow) break
                        }
                        inBuf.compact()
                        if (sb.isNotEmpty()) {
                            managed.terminal.onRemoteOutput(sb.toString())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Stream closed
            } finally {
                if (!managed.closed && managed.generation.get() == gen) {
                    updateTab(managed.sessionId) {
                        if (it.status == SessionStatus.CONNECTED) it.copy(status = SessionStatus.DISCONNECTED) else it
                    }
                    syncKeepAlive()
                    scope.launch {
                        managed.lifecycleMutex.withLock {
                            if (!managed.closed && managed.generation.get() == gen) {
                                managed.closeTransportOnly()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startKeepAlive(managed: ManagedSession, conn: ConnectedSsh, gen: Long) {
        val client = conn.client
        val interval = managed.server.keepAliveSeconds.takeIf { it > 0 }
            ?: vaultRepository.state.value.data.settings.defaultKeepAliveSeconds
        if (interval <= 0) return

        runCatching {
            client.connection.keepAlive.setKeepAliveInterval(interval)
            (client.connection.keepAlive as? KeepAliveRunner)?.setMaxAliveCount(3)
        }
        conn.jumpClient?.let { jump ->
            runCatching {
                jump.connection.keepAlive.setKeepAliveInterval(interval)
                (jump.connection.keepAlive as? KeepAliveRunner)?.setMaxAliveCount(3)
            }
        }

        managed.keepAliveJob = scope.launch {
            val pollMs = interval.coerceIn(5, 30) * 1000L
            while (isActive && !managed.closed && managed.generation.get() == gen) {
                delay(pollMs)
                val alive = runCatching {
                    client.isConnected && client.transport.isRunning
                }.getOrDefault(false)
                if (!alive) {
                    if (!managed.closed && managed.generation.get() == gen) {
                        updateTab(managed.sessionId) {
                            if (it.status == SessionStatus.CONNECTED) {
                                it.copy(status = SessionStatus.DISCONNECTED, error = "心跳超时，连接已断开")
                            } else it
                        }
                        syncKeepAlive()
                        scope.launch {
                            managed.lifecycleMutex.withLock {
                                if (!managed.closed && managed.generation.get() == gen) {
                                    managed.closeTransportOnly()
                                }
                            }
                        }
                    }
                    break
                }
            }
        }
    }

    private fun startMetrics(managed: ManagedSession, client: SSHClient, gen: Long) {
        managed.metricsJob = scope.launch {
            delay(800)
            while (isActive && !managed.closed && managed.generation.get() == gen && client.isConnected) {
                if (vaultRepository.state.value.data.settings.monitorBarEnabled) {
                    managed.metricsSampler.sample(client)?.let { m ->
                        if (!managed.closed && managed.generation.get() == gen) {
                            updateTab(managed.sessionId) { it.copy(metrics = m) }
                        }
                    }
                }
                delay(managed.metricsSampler.recommendedDelayMs())
            }
        }
    }
}
