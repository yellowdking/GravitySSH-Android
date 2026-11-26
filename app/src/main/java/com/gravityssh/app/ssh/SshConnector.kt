package com.gravityssh.app.ssh

import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.vault.KnownHostsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.net.InetAddress
import java.net.ServerSocket
import java.security.PublicKey

class SshConnector(
    private val knownHosts: KnownHostsManager
) {
    suspend fun connect(
        server: ServerConfig,
        allServers: List<ServerConfig>,
        allKeys: List<SSHKeyRecord>
    ): ConnectedSsh = withContext(Dispatchers.IO) {
        val timeoutMs = (server.timeoutSeconds.coerceIn(5, 120)) * 1000
        val viaParts = mutableListOf<String>()

        if (server.jumpHost.mode != JumpMode.NONE) {
            val jumpTarget = SshAuthHelper.resolveJumpServer(server.jumpHost, allServers, server.proxy)
            viaParts.add("跳板 ${jumpTarget.username}@${jumpTarget.host}:${jumpTarget.port}")
            val jumpClient = dialDirect(jumpTarget, allKeys, timeoutMs)
            var ss: ServerSocket? = null
            var forwarder: LocalPortForwarder? = null
            try {
                ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                val params = Parameters("127.0.0.1", ss.localPort, server.host, server.port)
                forwarder = jumpClient.newLocalPortForwarder(params, ss)
                val fwd = forwarder
                Thread({ runCatching { fwd.listen() } }, "gssh-jump-fwd").apply { isDaemon = true; start() }

                val targetClient = buildClient(server.host, server.port, timeoutMs, null)
                targetClient.connect("127.0.0.1", ss.localPort)
                SshAuthHelper.authenticate(targetClient, server, allKeys)
                return@withContext ConnectedSsh(targetClient, jumpClient, forwarder, ss, viaParts.joinToString(" -> "))
            } catch (e: Exception) {
                runCatching { forwarder?.close() }
                runCatching { ss?.close() }
                runCatching { jumpClient.close() }
                throw e
            }
        }

        if (server.proxy.type != ProxyType.NONE && server.proxy.host.isNotBlank()) {
            viaParts.add("${server.proxy.type.uppercase()} ${server.proxy.host}:${server.proxy.port}")
        }
        ConnectedSsh(dialDirect(server, allKeys, timeoutMs), viaSummary = viaParts.joinToString(" -> "))
    }

    suspend fun testConnection(
        server: ServerConfig,
        allServers: List<ServerConfig>,
        allKeys: List<SSHKeyRecord>
    ): Result<String> = runCatching {
        val start = System.currentTimeMillis()
        connect(server, allServers, allKeys).use { conn ->
            val rtt = System.currentTimeMillis() - start
            val via = if (conn.viaSummary.isNotBlank()) " (${conn.viaSummary})" else ""
            "连接成功，握手耗时 ${rtt}ms$via"
        }
    }

    private fun dialDirect(server: ServerConfig, allKeys: List<SSHKeyRecord>, timeoutMs: Int): SSHClient {
        val proxy = server.proxy.takeIf { it.type != ProxyType.NONE && it.host.isNotBlank() && it.port > 0 }
        val client = buildClient(server.host, server.port, timeoutMs, proxy)
        try {
            client.connect(server.host.trim(), server.port)
            SshAuthHelper.authenticate(client, server, allKeys)
            return client
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    private fun buildClient(targetHost: String, targetPort: Int, timeoutMs: Int, proxy: ProxyConfig?): SSHClient {
        val client = SSHClient()
        client.connectTimeout = timeoutMs
        client.timeout = timeoutMs
        if (proxy != null) client.socketFactory = ProxySocketFactory(proxy, timeoutMs)
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                knownHosts.verify(targetHost, targetPort, key)

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                knownHosts.findExistingAlgorithms(targetHost, targetPort)
        })
        return client
    }
}
