package com.gravityssh.app.ssh

import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.vault.KnownHostsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.PublicKey
import javax.net.SocketFactory

class SshConnector(
    private val knownHosts: KnownHostsManager
) {
    suspend fun connect(
        server: ServerConfig,
        allServers: List<ServerConfig>,
        allKeys: List<SSHKeyRecord>
    ): ConnectedSsh = withContext(Dispatchers.IO) {
        val timeoutMs = (server.timeoutSeconds.coerceIn(5, 120)) * 1000
        val targetHost = server.host.trim()
        val viaParts = mutableListOf<String>()

        if (server.jumpHost.mode != JumpMode.NONE) {
            val jumpTarget = SshAuthHelper.resolveJumpServer(server.jumpHost, allServers, server.proxy)
            val jumpUser = jumpTarget.username.trim().ifBlank { "root" }
            val jumpHost = jumpTarget.host.trim()
            viaParts.add("跳板 $jumpUser@$jumpHost:${jumpTarget.port}")
            val jumpClient = dialDirect(jumpTarget, allKeys, timeoutMs)
            var directConn: DirectConnection? = null
            var targetClient: SSHClient? = null
            try {
                directConn = jumpClient.newDirectConnection(targetHost, server.port)
                targetClient = buildClient(targetHost, server.port, timeoutMs, null)
                targetClient.connectVia(directConn)
                SshAuthHelper.authenticate(targetClient, server, allKeys)
                return@withContext ConnectedSsh(
                    client = targetClient,
                    jumpClient = jumpClient,
                    directConnection = directConn,
                    viaSummary = viaParts.joinToString(" -> ")
                )
            } catch (e: Exception) {
                runCatching { targetClient?.disconnect() }
                runCatching { targetClient?.close() }
                runCatching { directConn?.close() }
                runCatching { jumpClient.disconnect() }
                runCatching { jumpClient.close() }
                throw e
            }
        }

        if (server.proxy.type != ProxyType.NONE && server.proxy.host.isNotBlank() && server.proxy.port > 0) {
            viaParts.add("${server.proxy.type.uppercase()} ${server.proxy.host.trim()}:${server.proxy.port}")
        }
        ConnectedSsh(dialDirect(server, allKeys, timeoutMs), viaSummary = viaParts.joinToString(" -> "))
    }

    suspend fun testConnection(
        server: ServerConfig,
        allServers: List<ServerConfig>,
        allKeys: List<SSHKeyRecord>
    ): Result<String> = try {
        val start = System.currentTimeMillis()
        connect(server, allServers, allKeys).use { conn ->
            val rtt = System.currentTimeMillis() - start
            val via = if (conn.viaSummary.isNotBlank()) " (${conn.viaSummary})" else ""
            Result.success("连接成功，握手耗时 ${rtt}ms$via")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun dialDirect(server: ServerConfig, allKeys: List<SSHKeyRecord>, timeoutMs: Int): SSHClient {
        val targetHost = server.host.trim()
        val proxy = server.proxy.takeIf { it.type != ProxyType.NONE && it.host.isNotBlank() && it.port > 0 }
        val client = buildClient(targetHost, server.port, timeoutMs, proxy)
        try {
            if (proxy != null) {
                // Skip local DNS resolution; ProxySocketFactory passes raw targetHost to the proxy
                client.connect(InetAddress.getByAddress(targetHost, byteArrayOf(0, 0, 0, 0)), server.port)
            } else {
                client.connect(targetHost, server.port)
            }
            SshAuthHelper.authenticate(client, server, allKeys)
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw e
        }
    }

    private fun buildClient(targetHost: String, targetPort: Int, timeoutMs: Int, proxy: ProxyConfig?): SSHClient {
        val config = DefaultConfig().apply {
            setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE)
        }
        val client = SSHClient(config)
        client.connectTimeout = timeoutMs
        client.timeout = timeoutMs
        client.socketFactory = if (proxy != null) {
            ProxySocketFactory(proxy, timeoutMs, targetHost, targetPort)
        } else {
            TcpNoDelaySocketFactory
        }
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                knownHosts.verify(targetHost, targetPort, key)

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                knownHosts.findExistingAlgorithms(targetHost, targetPort)
        })
        return client
    }

    private class TcpNoDelaySocket : Socket() {
        override fun connect(endpoint: SocketAddress) {
            super.connect(endpoint)
            runCatching { tcpNoDelay = true }
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(endpoint, timeout)
            runCatching { tcpNoDelay = true }
        }
    }

    private object TcpNoDelaySocketFactory : SocketFactory() {
        private val delegate = getDefault()

        override fun createSocket(): Socket = TcpNoDelaySocket()

        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(host, port).apply { runCatching { tcpNoDelay = true } }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort).apply { runCatching { tcpNoDelay = true } }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port).apply { runCatching { tcpNoDelay = true } }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort).apply { runCatching { tcpNoDelay = true } }
    }
}
