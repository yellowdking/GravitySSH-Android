package com.gravityssh.app.ssh

import android.content.Context
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SessionStatus
import com.gravityssh.app.model.SessionTab
import com.gravityssh.app.terminal.TerminalSessionController
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val worker = SshSessionWorker(
        scope, vaultRepository, connector, ::updateTab, ::syncKeepAliveService
    ) { sid, cmd -> runScript(sid, cmd, autoExecute = true) }

    fun selectSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) _activeSessionId.value = sessionId
    }

    fun getTerminalController(sessionId: String): TerminalSessionController? = sessions[sessionId]?.terminal

    fun openSession(server: ServerConfig, cols: Int = 80, rows: Int = 24): String {
        val sid = UUID.randomUUID().toString()
        val ctrl = ManagedSession.createController(
            sid, server, cols, rows, vaultRepository, { id -> _tabs.value.find { it.sessionId == id } }, ::updateTab
        )
        val managed = ManagedSession(sid, server, ctrl, cols, rows)
        sessions[sid] = managed
        val tab = SessionTab(
            sid, server.id, server.name.ifBlank { server.host },
            server.host, server.port, server.username, SessionStatus.CONNECTING
        )
        _tabs.update { it + tab }
        _activeSessionId.value = sid
        syncKeepAliveService()
        worker.startConnect(managed)
        return sid
    }

    fun reconnectSession(sessionId: String) {
        val managed = sessions[sessionId] ?: return
        vaultRepository.state.value.data.servers.find { it.id == managed.server.id }?.let { managed.server = it }
        managed.closeResources()
        managed.closed = false
        managed.terminal.onRemoteOutput("\r\n\u001B[36m[GravitySSH] 正在重新连接...\u001B[0m\r\n")
        updateTab(sessionId) { it.copy(status = SessionStatus.CONNECTING, sftpReady = false, error = null) }
        worker.startConnect(managed)
    }

    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)?.let { managed ->
            managed.closed = true
            scope.launch { managed.closeResources() }
        }
        _tabs.update { list -> list.filterNot { it.sessionId == sessionId } }
        if (_activeSessionId.value == sessionId) _activeSessionId.value = _tabs.value.lastOrNull()?.sessionId
        syncKeepAliveService()
    }

    fun closeAllSessions() = sessions.keys.toList().forEach { closeSession(it) }
    fun sendInput(sessionId: String, text: String) { sessions[sessionId]?.sendRaw(scope, text) }
    fun runScript(sessionId: String, script: String, autoExecute: Boolean = true) { sessions[sessionId]?.runPacedScript(scope, script, autoExecute) }

    fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        val managed = sessions[sessionId] ?: return
        val c = cols.coerceIn(20, 300); val r = rows.coerceIn(8, 120)
        managed.lastCols = c; managed.lastRows = r
        managed.terminal.resize(c, r)
        val shell = managed.shell ?: return
        scope.launch { runCatching { shell.changeWindowDimensions(c, r, 0, 0) } }
    }

    suspend fun testConnection(server: ServerConfig): Result<String> {
        val data = vaultRepository.state.value.data
        return connector.testConnection(server, data.servers, data.sshKeys)
    }

    internal fun requireClient(sessionId: String): SSHClient =
        sessions[sessionId]?.conn?.client ?: throw IOException("SSH 会话未连接")

    internal fun requireSftp(sessionId: String): SFTPClient {
        val managed = sessions[sessionId] ?: throw IOException("会话不存在")
        managed.sftpClient?.let { return it }
        val sftp = (managed.conn?.client ?: throw IOException("SSH 会话未连接")).newSFTPClient()
        managed.sftpClient = sftp
        updateTab(sessionId) { it.copy(sftpReady = true) }
        return sftp
    }

    private fun updateTab(sessionId: String, transform: (SessionTab) -> SessionTab) {
        _tabs.update { list -> list.map { if (it.sessionId == sessionId) transform(it) else it } }
    }

    private fun syncKeepAliveService() {
        val count = _tabs.value.count { it.status == SessionStatus.CONNECTED || it.status == SessionStatus.CONNECTING }
        SshKeepAliveService.update(appContext, count)
    }
}
