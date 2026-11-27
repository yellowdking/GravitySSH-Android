package com.gravityssh.app.ssh

import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.vault.KnownHostsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private class ConnectCancellationGuard {
        private val lock = Any()
        private var cancelled = false
        private val sockets = ArrayList<Socket>(2)
        private val clients = ArrayList<SSHClient>(2)
        private val directConns = ArrayList<DirectConnection>(1)

        fun isCancelled(): Boolean = synchronized(lock) { cancelled }

        fun registerSocket(socket: Socket) {
            val closeNow = synchronized(lock) {
                if (cancelled) true else { sockets.add(socket); false }
            }
            if (closeNow) runCatching { socket.close() }
        }

        fun registerClient(client: SSHClient) {
            val closeNow = synchronized(lock) {
                if (cancelled) true else { clients.add(client); false }
            }
            if (closeNow) {
                runCatching { client.disconnect() }
                runCatching { client.close() }
            }
        }

        fun registerDirectConn(conn: DirectConnection) {
            val closeNow = synchronized(lock) {
                if (cancelled) true else { directConns.add(conn); false }
            }
            if (closeNow) runCatching { conn.close() }
        }

        fun cancelAll() {
            val snapSockets: List<Socket>
            val snapClients: List<SSHClient>
            val snapDirects: List<DirectConnection>
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                snapSockets = sockets.toList()
                snapClients = clients.toList()
                snapDirects = directConns.toList()
            }
            for (s in snapSockets) runCatching { s.close() }
            for (d in snapDirects) runCatching { d.close() }
            for (c in snapClients) {
                runCatching { c.disconnect() }
                runCatching { c.close() }
            }
        }
    }

    suspend fun connect(
        server: ServerConfig,
        allServers: List<ServerConfig>,
        allKeys: List<SSHKeyRecord>
    ): ConnectedSsh = withContext(Dispatchers.IO) {
        val guard = ConnectCancellationGuard()
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) guard.cancelAll()
        }
        try {
            coroutineContext.ensureActive()
            val timeoutMs = (server.timeoutSeconds.coerceIn(5, 120)) * 1000
            val targetHost = server.host.trim()
            val viaParts = mutableListOf<String>()

            if (server.jumpHost.mode != JumpMode.NONE) {
                val jumpTarget = SshAuthHelper.resolveJumpServer(server.jumpHost, allServers, server.proxy)
                val jumpUser = jumpTarget.username.trim().ifBlank { "root" }
                val jumpHost = jumpTarget.host.trim()
                viaParts.add("跳板 $jumpUser@$jumpHost:${jumpTarget.port}")
                val jumpClient = dialDirect(jumpTarget, allKeys, timeoutMs, guard)
                var directConn: DirectConnection? = null
                var targetClient: SSHClient? = null
                try {
                    coroutineContext.ensureActive()
                    val cleanTargetHost = targetHost.removeSurrounding("[", "]")
                    directConn = jumpClient.newDirectConnection(cleanTargetHost, server.port)
                    guard.registerDirectConn(directConn)
                    coroutineContext.ensureActive()
                    targetClient = buildClient(targetHost, server.port, timeoutMs, null, guard)
                    guard.registerClient(targetClient)
                    targetClient.connectVia(directConn)
                    SshAuthHelper.authenticate(targetClient, server, allKeys)
                    if (guard.isCancelled()) {
                        throw CancellationException("连接已取消")
                    }
                    return@withContext ConnectedSsh(
                        client = targetClient,
                        jumpClient = jumpClient,
                        directConnection = directConn,
                        viaSummary = viaParts.joinToString(" -> ")
                    )
                } catch (e: Throwable) {
                    runCatching { targetClient?.disconnect() }
                    runCatching { targetClient?.close() }
                    runCatching { directConn?.close() }
                    runCatching { jumpClient.disconnect() }
                    runCatching { jumpClient.close() }
                    if (guard.isCancelled() && e !is CancellationException) {
                        throw CancellationException("连接已取消").also { it.initCause(e) }
                    }
                    throw e
                }
            }

            if (server.proxy.type != ProxyType.NONE && server.proxy.host.isNotBlank() && server.proxy.port > 0) {
                viaParts.add("${server.proxy.type.uppercase()} ${server.proxy.host.trim()}:${server.proxy.port}")
            }
            val directClient = dialDirect(server, allKeys, timeoutMs, guard)
            if (guard.isCancelled()) {
                runCatching { directClient.disconnect() }
                runCatching { directClient.close() }
                throw CancellationException("连接已取消")
            }
            ConnectedSsh(directClient, viaSummary = viaParts.joinToString(" -> "))
        } catch (e: Throwable) {
            if (guard.isCancelled() && e !is CancellationException) {
                throw CancellationException("连接已取消").also { it.initCause(e) }
            }
            throw e
        } finally {
            cancelHandle?.dispose()
        }
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

    private fun dialDirect(
        server: ServerConfig,
        allKeys: List<SSHKeyRecord>,
        timeoutMs: Int,
        guard: ConnectCancellationGuard
    ): SSHClient {
        val targetHost = server.host.trim()
        val cleanTargetHost = targetHost.removeSurrounding("[", "]")
        val proxy = server.proxy.takeIf { it.type != ProxyType.NONE && it.host.isNotBlank() && it.port > 0 }
        val client = buildClient(targetHost, server.port, timeoutMs, proxy, guard)
        guard.registerClient(client)
        try {
            if (guard.isCancelled()) throw CancellationException("连接已取消")
            if (proxy != null) {
                // Skip local DNS resolution; ProxySocketFactory passes raw targetHost to the proxy
                client.connect(InetAddress.getByAddress(cleanTargetHost, byteArrayOf(0, 0, 0, 0)), server.port)
            } else {
                client.connect(cleanTargetHost, server.port)
            }
            if (guard.isCancelled()) throw CancellationException("连接已取消")
            SshAuthHelper.authenticate(client, server, allKeys)
            return client
        } catch (e: Throwable) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw e
        }
    }

    private fun buildClient(
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int,
        proxy: ProxyConfig?,
        guard: ConnectCancellationGuard
    ): SSHClient {
        val config = DefaultConfig().apply {
            setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE)
        }
        val client = SSHClient(config)
        client.connectTimeout = timeoutMs
        client.timeout = timeoutMs
        client.socketFactory = if (proxy != null) {
            ProxySocketFactory(proxy, timeoutMs, targetHost, targetPort, guard::registerSocket)
        } else {
            TcpNoDelaySocketFactory(guard::registerSocket)
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

    private class TcpNoDelaySocketFactory(
        private val onSocketCreated: ((Socket) -> Unit)? = null
    ) : SocketFactory() {
        private val delegate = getDefault()

        private fun track(socket: Socket): Socket = socket.also { onSocketCreated?.invoke(it) }

        override fun createSocket(): Socket = track(TcpNoDelaySocket())

        override fun createSocket(host: String, port: Int): Socket =
            track(delegate.createSocket(host, port).apply { runCatching { tcpNoDelay = true } })

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            track(delegate.createSocket(host, port, localHost, localPort).apply { runCatching { tcpNoDelay = true } })

        override fun createSocket(host: InetAddress, port: Int): Socket =
            track(delegate.createSocket(host, port).apply { runCatching { tcpNoDelay = true } })

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            track(delegate.createSocket(address, port, localAddress, localPort).apply { runCatching { tcpNoDelay = true } })
    }
}