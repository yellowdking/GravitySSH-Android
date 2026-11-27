package com.gravityssh.app.ssh

import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.terminal.PasteGuard
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
    @Volatile var lastSentCols: Int = -1
    @Volatile var lastSentRows: Int = -1

    val lifecycleMutex = Mutex()
    val resizeMutex = Mutex()
    val sftpLock = Any()
    val generation = AtomicLong(0L)
    val metricsSampler = HostMetricsSampler(sessionId)

    @Volatile var connectJob: Job? = null
    @Volatile var sftpInitJob: Job? = null
    @Volatile var readerJob: Job? = null
    @Volatile var lastReaderJob: Job? = null
    @Volatile var keepAliveJob: Job? = null
    @Volatile var metricsJob: Job? = null
    @Volatile var scriptJob: Job? = null
    @Volatile var writerJob: Job? = null

    @Volatile private var writeChannel: Channel<ByteArray>? = null

    init {
        terminal.setInputSender(::sendRaw)
    }

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
        if (!autoExecute) {
            val fillPayload = formatSafeFillPayload(normalized, terminal.isBracketedPasteEnabled())
            if (fillPayload.isNotEmpty()) {
                sendRaw(fillPayload)
            }
            return
        }
        if (lines.size == 1) {
            sendRaw("${lines.first()}\r")
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
        sftpInitJob?.cancel(); sftpInitJob = null
        readerJob?.cancel(); readerJob = null
        keepAliveJob?.cancel(); keepAliveJob = null
        metricsJob?.cancel(); metricsJob = null
        scriptJob?.cancel(); scriptJob = null
        writerJob?.cancel(); writerJob = null
        writeChannel?.close(); writeChannel = null
    }

    fun closeTransportOnly() {
        cancelJobs(includeConnect = false)
        val oldSftp = synchronized(sftpLock) {
            sftpClient.also { sftpClient = null }
        }
        val oldShell = shell.also { shell = null }
        val oldSshSession = sshSession.also { sshSession = null }
        val oldConn = conn.also { conn = null }
        lastSentCols = -1
        lastSentRows = -1
        metricsSampler.reset()
        // Close transport first so any in-flight SFTP/channel I/O unblocks immediately
        runCatching { oldConn?.close() }
        runCatching { oldSftp?.close() }
        runCatching { oldShell?.close() }
        runCatching { oldSshSession?.close() }
    }

    fun closeResources() {
        connectJob?.cancel()
        connectJob = null
        closeTransportOnly()
        lastReaderJob = null
    }

    companion object {
        internal fun formatSafeFillPayload(script: String, bracketedPasteEnabled: Boolean): String {
            val normalized = script.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')
            if (normalized.isEmpty()) return ""
            if ('\n' !in normalized) return normalized
            if (bracketedPasteEnabled) {
                val safe = PasteGuard.sanitizePasteText(normalized)
                return "\u001B[200~$safe\u001B[201~"
            }
            val parts = normalized.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            val sb = StringBuilder(normalized.length)
            for (i in parts.indices) {
                val part = parts[i]
                if (i > 0) {
                    val prev = parts[i - 1]
                    val needsSemi = !(prev.endsWith(";") || prev.endsWith("&&") || prev.endsWith("||") ||
                        prev.endsWith("|") || prev.endsWith("{") || prev.endsWith("(") ||
                        prev.endsWith("then") || prev.endsWith("do") || prev.endsWith("else") ||
                        prev.endsWith("\\"))
                    sb.append(if (needsSemi) "; " else " ")
                }
                sb.append(part)
            }
            return sb.toString()
        }

        fun createController(
            sessionId: String,
            server: ServerConfig,
            cols: Int,
            rows: Int,
            vault: VaultRepository,
            findTab: (String) -> SessionTab?,
            updateTab: (String, (SessionTab) -> SessionTab) -> Unit,
            onDirDirty: () -> Unit = {}
        ): TerminalSessionController = TerminalSessionController(
            sessionId = sessionId,
            initialCols = cols,
            initialRows = rows,
            keywordHighlightEnabled = { vault.state.value.data.settings.keywordHighlightEnabled },
            onCwdDetected = { dir -> updateTab(sessionId) { it.copy(currentDir = dir) } },
            onCommandCommitted = { cmd ->
                val leadingSpace = cmd.firstOrNull()?.let { it == ' ' || it == '\t' } == true
                if (!leadingSpace && cmd.isNotBlank()) {
                    vault.recordCommand(cmd, server.id, server.name, sessionId)
                }
                findTab(sessionId)?.let { tab ->
                    ShellPathTracker.resolveNextDir(cmd, tab.currentDir, tab.homeDir)?.let { next ->
                        updateTab(sessionId) { it.copy(currentDir = next) }
                    }
                }
                if (!ShellPathTracker.isLikelyReadOnlyCommand(cmd)) {
                    onDirDirty()
                }
            }
        )
    }
}

internal object ShellPathTracker {
    private val CD_RE = Regex("^cd(?:\\s+(.+))?$")
    private val WS_RE = Regex("\\s+")
    private val READ_ONLY_CMDS = setOf(
        "ls", "ll", "la", "dir", "cd", "pwd", "cat", "head", "tail", "less", "more",
        "grep", "egrep", "fgrep", "rg", "ag", "find", "locate", "which", "whereis", "type",
        "file", "stat", "wc", "du", "df", "free", "uptime", "uname", "hostname", "whoami",
        "id", "groups", "ps", "top", "htop", "pgrep", "ss", "netstat", "lsof", "ip",
        "ifconfig", "ping", "traceroute", "dig", "nslookup", "host", "echo", "printf",
        "date", "cal", "env", "printenv", "history", "clear", "exit", "logout", "man",
        "help", "tree", "bat", "fd", "jq", "journalctl", "dmesg", "lsblk", "lscpu",
        "lsusb", "lspci"
    )
    private val READ_ONLY_SUBCMDS = mapOf(
        "git" to setOf("status", "log", "diff", "show", "branch", "tag", "remote", "rev-parse"),
        "docker" to setOf("ps", "images", "logs", "stats", "inspect", "version", "info"),
        "systemctl" to setOf("status", "list-units", "list-unit-files", "is-active", "is-enabled")
    )

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
        return SshPathUtils.normalizePosixPath(target)
    }

    fun isLikelyReadOnlyCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.any { it == '>' || it == ';' || it == '|' || it == '&' || it == '`' } || trimmed.contains("$(")) {
            return false
        }
        val tokens = trimmed.split(WS_RE)
        val cmd = tokens.firstOrNull()?.substringAfterLast('/') ?: return true
        if (cmd in READ_ONLY_CMDS) return true
        val allowedSubs = READ_ONLY_SUBCMDS[cmd] ?: return false
        val sub = tokens.getOrNull(1) ?: return false
        return sub in allowedSubs
    }
}
