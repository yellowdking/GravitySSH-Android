package com.gravityssh.app.ssh

import android.content.Context
import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.TerminalSessionController
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SshSessionManager(
    private val appContext: Context,
    private val vaultRepository: VaultRepository,
    private val connector: SshConnector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ManagedSession>()
    private val dirCache = RemoteDirCache()
    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()
    private val _appForeground = MutableStateFlow(true)
    private val _metricsActiveSessionId = MutableStateFlow<String?>(null)
    private val worker = SshSessionWorker(
        scope = scope,
        vaultRepository = vaultRepository,
        connector = connector,
        appForegroundFlow = _appForeground.asStateFlow(),
        activeSessionIdFlow = _metricsActiveSessionId.asStateFlow(),
        updateTab = ::updateTab,
        syncKeepAlive = ::syncKeepAlive
    ) { sid, cmd ->
        runScript(sid, cmd, autoExecute = true)
    }

    fun selectSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) {
            _activeSessionId.value = sessionId
            _metricsActiveSessionId.value = sessionId
        }
    }

    fun setActiveSessionId(sessionId: String?) {
        _metricsActiveSessionId.value = sessionId
    }

    fun setAppForeground(foreground: Boolean) {
        _appForeground.value = foreground
        if (foreground) syncKeepAlive()
    }

    fun getTerminalController(sessionId: String): TerminalSessionController? = sessions[sessionId]?.terminal

    fun openSession(server: ServerConfig, cols: Int = 80, rows: Int = 24): String {
        val sid = UUID.randomUUID().toString()
        val ctrl = ManagedSession.createController(
            sessionId = sid,
            server = server,
            cols = cols,
            rows = rows,
            vault = vaultRepository,
            findTab = { id -> _tabs.value.find { it.sessionId == id } },
            updateTab = ::updateTab,
            onDirDirty = { invalidateDirCache(sid) }
        )
        val m = ManagedSession(sid, server, ctrl, cols, rows)
        val gen = m.generation.incrementAndGet()
        sessions[sid] = m
        val tab = SessionTab(sid, server.id, server.name.ifBlank { server.host }, server.host, server.port, server.username, SessionStatus.CONNECTING)
        _tabs.update { it + tab }
        _activeSessionId.value = sid
        _metricsActiveSessionId.value = sid
        syncKeepAlive()
        worker.startConnect(m, gen, closePreviousFirst = false)
        return sid
    }

    fun reconnectSession(sessionId: String) {
        val m = sessions[sessionId] ?: return
        invalidateDirCache(sessionId)
        vaultRepository.state.value.data.servers.find { it.id == m.server.id }?.let { m.server = it }
        val gen = m.generation.incrementAndGet()
        m.cancelJobs(includeConnect = true)
        updateTab(sessionId) { it.copy(status = SessionStatus.CONNECTING, sftpReady = false, error = null) }
        syncKeepAlive()
        worker.startConnect(m, gen, closePreviousFirst = true)
    }

    fun closeSession(sessionId: String) {
        closeSessionInternal(sessionId, updateKeepAlive = true)
    }

    fun closeAllSessions() {
        val ids = sessions.keys.toList()
        for (id in ids) {
            closeSessionInternal(id, updateKeepAlive = false)
        }
        syncKeepAlive()
    }

    private fun closeSessionInternal(sessionId: String, updateKeepAlive: Boolean) {
        invalidateDirCache(sessionId)
        sessions.remove(sessionId)?.let { m ->
            m.closed = true
            m.generation.incrementAndGet()
            m.cancelJobs(includeConnect = true)
            scope.launch {
                m.lifecycleMutex.withLock {
                    m.closeResources()
                }
            }
        }
        _tabs.update { it.filterNot { t -> t.sessionId == sessionId } }
        if (_activeSessionId.value == sessionId) _activeSessionId.value = _tabs.value.lastOrNull()?.sessionId
        if (_metricsActiveSessionId.value == sessionId) _metricsActiveSessionId.value = _activeSessionId.value
        if (updateKeepAlive) syncKeepAlive()
    }

    fun sendInput(sessionId: String, text: String) {
        sessions[sessionId]?.sendRaw(text)
    }

    fun runScript(sessionId: String, script: String, autoExecute: Boolean = true) {
        if (autoExecute) invalidateDirCache(sessionId)
        sessions[sessionId]?.runPacedScript(scope, script, autoExecute)
    }

    fun invalidateDirCache(sessionId: String) = dirCache.invalidateSession(sessionId)

    internal fun invalidateDirCache(sessionId: String, dirPath: String) = dirCache.invalidateDir(sessionId, dirPath)

    fun peekCachedDirEntries(sessionId: String, dirPath: String, showHidden: Boolean = true): List<SFTPFileEntry> =
        dirCache.peekCached(sessionId, dirPath, homeOf(sessionId), showHidden).orEmpty()

    suspend fun listDirForAutocomplete(sessionId: String, dirPath: String, showHidden: Boolean = true): List<SFTPFileEntry> =
        dirCache.listDir(
            sessionId = sessionId,
            dirPath = dirPath,
            homeDir = homeOf(sessionId),
            showHidden = showHidden,
            sftpProvider = {
                val client = sessions[sessionId]?.conn?.client
                if (client == null || !client.isConnected) null else requireSftp(sessionId)
            },
            onSftpError = { sftp, err ->
                if (isSftpChannelBroken(sftp, err)) {
                    invalidateSftp(sessionId, sftp)
                }
            }
        )

    fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        val m = sessions[sessionId] ?: return
        val c = cols.coerceIn(20, 300); val r = rows.coerceIn(8, 120)
        if (m.lastCols == c && m.lastRows == r) return
        m.lastCols = c; m.lastRows = r
        m.terminal.resize(c, r)
        if (m.shell == null) return
        scope.launch {
            m.resizeMutex.withLock {
                val targetCols = m.lastCols
                val targetRows = m.lastRows
                if (m.lastSentCols == targetCols && m.lastSentRows == targetRows) return@withLock
                val curShell = m.shell ?: return@withLock
                runCatching {
                    curShell.changeWindowDimensions(targetCols, targetRows, 0, 0)
                    m.lastSentCols = targetCols
                    m.lastSentRows = targetRows
                }
            }
        }
    }

    suspend fun testConnection(server: ServerConfig): Result<String> =
        vaultRepository.state.value.data.let { connector.testConnection(server, it.servers, it.sshKeys) }

    internal fun requireClient(sessionId: String): SSHClient {
        val client = sessions[sessionId]?.conn?.client
        if (client == null || !client.isConnected) throw IOException("SSH 会话未连接")
        return client
    }

    internal fun isSftpOpen(sftp: SFTPClient): Boolean =
        runCatching { sftp.getSFTPEngine().getSubsystem().isOpen }.getOrDefault(false)

    internal fun isSftpChannelBroken(sftp: SFTPClient, error: Throwable): Boolean {
        if (!isSftpOpen(sftp)) return true
        return error is IOException && error !is SFTPException
    }

    internal fun invalidateSftp(sessionId: String, failed: SFTPClient? = null) {
        val m = sessions[sessionId] ?: return
        val toClose = synchronized(m.sftpLock) {
            val cur = m.sftpClient
            if (cur != null && (failed == null || cur === failed)) {
                m.sftpClient = null
                cur
            } else {
                null
            }
        }
        if (toClose != null) {
            runCatching { toClose.close() }
        }
    }

    internal inline fun <T> withSftp(sessionId: String, block: (SFTPClient) -> T): T {
        val sftp = requireSftp(sessionId)
        return try {
            block(sftp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isSftpChannelBroken(sftp, e)) {
                invalidateSftp(sessionId, sftp)
            }
            throw e
        }
    }

    internal fun requireSftp(sessionId: String): SFTPClient {
        val m = sessions[sessionId] ?: throw IOException("会话不存在")
        val gen = m.generation.get()
        var staleSftp: SFTPClient? = null
        var cached: SFTPClient? = null
        val client: SSHClient = synchronized(m.sftpLock) {
            val c = m.conn?.client
            if (m.closed || c == null || !c.isConnected) {
                staleSftp = m.sftpClient
                m.sftpClient = null
                null
            } else {
                cached = m.sftpClient
                c
            }
        } ?: run {
            runCatching { staleSftp?.close() }
            throw IOException("SSH 会话未连接")
        }

        if (cached != null) {
            if (isSftpOpen(cached!!)) {
                return cached!!
            }
            val detached = synchronized(m.sftpLock) {
                if (m.sftpClient === cached) {
                    m.sftpClient = null
                    cached
                } else {
                    null
                }
            }
            runCatching { detached?.close() }
        }

        val created = client.newSFTPClient()
        var adopted = false
        var replacedOld: SFTPClient? = null
        val result = synchronized(m.sftpLock) {
            if (m.closed || m.generation.get() != gen || m.conn?.client !== client || !client.isConnected) {
                null
            } else {
                val existing = m.sftpClient
                if (existing != null && isSftpOpen(existing)) {
                    existing
                } else {
                    if (existing != null) replacedOld = existing
                    m.sftpClient = created
                    adopted = true
                    created
                }
            }
        }
        runCatching { replacedOld?.close() }
        if (result == null) {
            runCatching { created.close() }
            throw IOException("SSH 会话已断开")
        }
        if (!adopted) {
            runCatching { created.close() }
        } else {
            updateTab(sessionId) { t -> t.copy(sftpReady = true) }
        }
        return result
    }

    private fun homeOf(id: String): String = _tabs.value.find { it.sessionId == id }?.homeDir.orEmpty()

    private fun updateTab(id: String, fn: (SessionTab) -> SessionTab) =
        _tabs.update { l -> l.map { if (it.sessionId == id) fn(it) else it } }

    private fun syncKeepAlive() = SshKeepAliveService.update(
        appContext,
        _tabs.value.count { it.status == SessionStatus.CONNECTED || it.status == SessionStatus.CONNECTING }
    )
}
