package com.gravityssh.app.ssh

import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.terminal.TerminalSessionController
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

internal class ManagedSession(
    val sessionId: String,
    @Volatile var server: ServerConfig,
    val terminal: TerminalSessionController,
    @Volatile var lastCols: Int = 80,
    @Volatile var lastRows: Int = 24
) {
    @Volatile var conn: ConnectedSsh? = null
    @Volatile var sshSession: Session? = null
    @Volatile var shell: Session.Shell? = null
    @Volatile var sftpClient: SFTPClient? = null
    @Volatile var closed: Boolean = false

    val lifecycleMutex = Mutex()
    val sftpLock = Any()
    val generation = AtomicLong(0L)
    val metricsSampler = HostMetricsSampler(sessionId)

    @Volatile var connectJob: Job? = null
    @Volatile var readerJob: Job? = null
    @Volatile var keepAliveJob: Job? = null
    @Volatile var metricsJob: Job? = null
    @Volatile var scriptJob: Job? = null
    @Volatile var writerJob: Job? = null

    @Volatile private var writeChannel: Channel<ByteArray>? = null

    fun startWriter(scope: CoroutineScope, sh: Session.Shell) {
        writerJob?.cancel()
        writeChannel?.close()
        val ch = Channel<ByteArray>(Channel.UNLIMITED)
        writeChannel = ch
        writerJob = scope.launch {
            try {
                val out = sh.outputStream
                for (bytes in ch) {
                    if (!isActive || closed) break
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Stream closed
            }
        }
    }

    fun sendRaw(text: String) {
        terminal.onLocalInput(text)
        val ch = writeChannel ?: return
        ch.trySend(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun runPacedScript(scope: CoroutineScope, script: String, autoExecute: Boolean) {
        val normalized = script.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        if (!autoExecute || lines.size == 1) {
            sendRaw(if (autoExecute) "${lines.first()}\r" else normalized)
            return
        }
        scriptJob?.cancel()
        scriptJob = scope.launch {
            for (line in lines) {
                if (!isActive || closed) break
                val ch = writeChannel ?: break
                if (ch.trySend("$line\r".toByteArray(StandardCharsets.UTF_8)).isFailure) break
                delay(35L)
            }
        }
    }

    fun cancelJobs(includeConnect: Boolean = true) {
        if (includeConnect) {
            connectJob?.cancel()
            connectJob = null
        }
        readerJob?.cancel(); readerJob = null
        keepAliveJob?.cancel(); keepAliveJob = null
        metricsJob?.cancel(); metricsJob = null
        scriptJob?.cancel(); scriptJob = null
        writerJob?.cancel(); writerJob = null
        writeChannel?.close(); writeChannel = null
    }

    fun closeTransportOnly() {
        cancelJobs(includeConnect = false)
        synchronized(sftpLock) {
            runCatching { sftpClient?.close() }
            sftpClient = null
        }
        runCatching { shell?.close() }
        runCatching { sshSession?.close() }
        runCatching { conn?.close() }
        shell = null
        sshSession = null
        conn = null
        metricsSampler.reset()
    }

    fun closeResources() {
        connectJob?.cancel()
        connectJob = null
        closeTransportOnly()
    }

    companion object {
        fun createController(
            sessionId: String,
            server: ServerConfig,
            cols: Int,
            rows: Int,
            vault: VaultRepository,
            findTab: (String) -> SessionTab?,
            updateTab: (String, (SessionTab) -> SessionTab) -> Unit
        ): TerminalSessionController = TerminalSessionController(
            sessionId = sessionId,
            initialCols = cols,
            initialRows = rows,
            keywordHighlightEnabled = { vault.state.value.data.settings.keywordHighlightEnabled },
            onCwdDetected = { dir -> updateTab(sessionId) { it.copy(currentDir = dir) } },
            onCommandCommitted = { cmd ->
                vault.recordCommand(cmd, server.id, server.name, sessionId)
                findTab(sessionId)?.let { tab ->
                    ShellPathTracker.resolveNextDir(cmd, tab.currentDir, tab.homeDir)?.let { next ->
                        updateTab(sessionId) { it.copy(currentDir = next) }
                    }
                }
            }
        )
    }
}

internal object ShellPathTracker {
    private val CD_RE = Regex("^cd(?:\\s+(.+))?$")

    fun resolveNextDir(command: String, currentDir: String, homeDir: String): String? {
        val m = CD_RE.matchEntire(command.trim()) ?: return null
        val rawArg = m.groupValues.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'").orEmpty()
        if (rawArg.isEmpty() || rawArg == "~") return homeDir.ifBlank { "/" }
        if (rawArg == "-") return null
        val target = when {
            rawArg.startsWith("~/") -> "${homeDir.trimEnd('/')}/${rawArg.removePrefix("~/")}"
            rawArg.startsWith("/") -> rawArg
            else -> "${currentDir.trimEnd('/')}/$rawArg"
        }
        return normalizePosixPath(target)
    }

    fun normalizePosixPath(path: String): String = SshPathUtils.normalizePosixPath(path)
}
