package com.gravityssh.app.ssh

import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.terminal.TerminalSessionController
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import java.nio.charset.StandardCharsets

internal class ManagedSession(
    val sessionId: String,
    var server: ServerConfig,
    val terminal: TerminalSessionController,
    var lastCols: Int = 80,
    var lastRows: Int = 24
) {
    @Volatile var conn: ConnectedSsh? = null
    @Volatile var sshSession: Session? = null
    @Volatile var shell: Session.Shell? = null
    @Volatile var sftpClient: SFTPClient? = null
    @Volatile var closed: Boolean = false
    val metricsSampler = HostMetricsSampler(sessionId)

    var connectJob: Job? = null; var readerJob: Job? = null
    var keepAliveJob: Job? = null; var metricsJob: Job? = null; var scriptJob: Job? = null

    fun sendRaw(scope: CoroutineScope, text: String) {
        terminal.onLocalInput(text)
        val sh = shell ?: return
        scope.launch {
            runCatching {
                sh.outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
                sh.outputStream.flush()
            }
        }
    }

    fun runPacedScript(scope: CoroutineScope, script: String, autoExecute: Boolean) {
        val normalized = script.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        if (!autoExecute || lines.size == 1) {
            sendRaw(scope, if (autoExecute) "${lines.first()}\r" else normalized)
            return
        }
        scriptJob?.cancel()
        scriptJob = scope.launch {
            for (line in lines) {
                if (!isActive || closed) break
                val sh = shell ?: break
                runCatching {
                    sh.outputStream.write("$line\r".toByteArray(StandardCharsets.UTF_8))
                    sh.outputStream.flush()
                }
                delay(35L)
            }
        }
    }

    fun closeResources() {
        connectJob?.cancel(); readerJob?.cancel(); keepAliveJob?.cancel(); metricsJob?.cancel(); scriptJob?.cancel()
        runCatching { sftpClient?.close() }
        runCatching { shell?.close() }
        runCatching { sshSession?.close() }
        runCatching { conn?.close() }
        sftpClient = null; shell = null; sshSession = null; conn = null
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

    fun normalizePosixPath(path: String): String {
        val parts = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }
}
