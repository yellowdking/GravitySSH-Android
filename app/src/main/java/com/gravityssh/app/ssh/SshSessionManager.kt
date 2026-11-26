package com.gravityssh.app.ssh

import android.content.Context
import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.TerminalSessionController
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
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
    private val worker = SshSessionWorker(scope, vaultRepository, connector, ::updateTab, ::syncKeepAlive) { sid, cmd ->
        runScript(sid, cmd, autoExecute = true)
    }

    fun selectSession(sessionId: String) { if (sessions.containsKey(sessionId)) _activeSessionId.value = sessionId }
    fun getTerminalController(sessionId: String): TerminalSessionController? = sessions[sessionId]?.terminal

    fun openSession(server: ServerConfig, cols: Int = 80, rows: Int = 24): String {
        val sid = UUID.randomUUID().toString()
        val ctrl = ManagedSession.createController(sid, server, cols, rows, vaultRepository, { id -> _tabs.value.find { it.sessionId == id } }, ::updateTab)
        val m = ManagedSession(sid, server, ctrl, cols, rows)
        sessions[sid] = m
        val tab = SessionTab(sid, server.id, server.name.ifBlank { server.host }, server.host, server.port, server.username, SessionStatus.CONNECTING)
        _tabs.update { it + tab }
        _activeSessionId.value = sid
        syncKeepAlive()
        worker.startConnect(m)
        return sid
    }

    fun reconnectSession(sessionId: String) {
        val m = sessions[sessionId] ?: return
        invalidateDirCache(sessionId)
        vaultRepository.state.value.data.servers.find { it.id == m.server.id }?.let { m.server = it }
        m.closeResources(); m.closed = false
        m.terminal.onRemoteOutput("\r\n\u001B[36m[GravitySSH] 正在重新连接...\u001B[0m\r\n")
        updateTab(sessionId) { it.copy(status = SessionStatus.CONNECTING, sftpReady = false, error = null) }
        worker.startConnect(m)
    }

    fun closeSession(sessionId: String) {
        invalidateDirCache(sessionId)
        sessions.remove(sessionId)?.let { m -> m.closed = true; scope.launch { m.closeResources() } }
        _tabs.update { it.filterNot { t -> t.sessionId == sessionId } }
        if (_activeSessionId.value == sessionId) _activeSessionId.value = _tabs.value.lastOrNull()?.sessionId
        syncKeepAlive()
    }

    fun closeAllSessions() = sessions.keys.toList().forEach { closeSession(it) }

    fun sendInput(sessionId: String, text: String) {
        if (text.contains('\r') || text.contains('\n')) invalidateDirCache(sessionId)
        sessions[sessionId]?.sendRaw(scope, text)
    }

    fun runScript(sessionId: String, script: String, autoExecute: Boolean = true) {
        if (autoExecute) invalidateDirCache(sessionId)
        sessions[sessionId]?.runPacedScript(scope, script, autoExecute)
    }

    fun invalidateDirCache(sessionId: String) = dirCache.invalidateSession(sessionId)

    fun peekCachedDirEntries(sessionId: String, dirPath: String, showHidden: Boolean = true): List<SFTPFileEntry> =
        dirCache.peekCached(sessionId, dirPath, homeOf(sessionId), showHidden).orEmpty()

    suspend fun listDirForAutocomplete(sessionId: String, dirPath: String, showHidden: Boolean = true): List<SFTPFileEntry> =
        dirCache.listDir(sessionId, dirPath, homeOf(sessionId), showHidden) {
            if (sessions[sessionId]?.conn?.client == null) null else requireSftp(sessionId)
        }

    fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        val m = sessions[sessionId] ?: return
        val c = cols.coerceIn(20, 300); val r = rows.coerceIn(8, 120)
        m.lastCols = c; m.lastRows = r
        m.terminal.resize(c, r)
        val sh = m.shell ?: return
        scope.launch { runCatching { sh.changeWindowDimensions(c, r, 0, 0) } }
    }

    suspend fun testConnection(server: ServerConfig): Result<String> =
        vaultRepository.state.value.data.let { connector.testConnection(server, it.servers, it.sshKeys) }

    internal fun requireClient(sessionId: String): SSHClient =
        sessions[sessionId]?.conn?.client ?: throw IOException("SSH 会话未连接")

    internal fun requireSftp(sessionId: String): SFTPClient {
        val m = sessions[sessionId] ?: throw IOException("会话不存在")
        return m.sftpClient ?: requireClient(sessionId).newSFTPClient().also {
            m.sftpClient = it; updateTab(sessionId) { t -> t.copy(sftpReady = true) }
        }
    }

    private fun homeOf(id: String): String = _tabs.value.find { it.sessionId == id }?.homeDir.orEmpty()

    private fun updateTab(id: String, fn: (SessionTab) -> SessionTab) =
        _tabs.update { l -> l.map { if (it.sessionId == id) fn(it) else it } }

    private fun syncKeepAlive() = SshKeepAliveService.update(
        appContext, _tabs.value.count { it.status == SessionStatus.CONNECTED || it.status == SessionStatus.CONNECTING }
    )
}
