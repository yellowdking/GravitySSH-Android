package com.gravityssh.app.ssh

import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

class ProxySocketFactory(
    private val proxy: ProxyConfig,
    private val timeoutMs: Int = 15_000,
    private val rawTargetHost: String? = null,
    private val rawTargetPort: Int = -1,
    private val onSocketCreated: ((Socket) -> Unit)? = null
) : SocketFactory() {

    override fun createSocket(): Socket =
        ProxyTunnelSocket(proxy, timeoutMs, rawTargetHost, rawTargetPort).also { onSocketCreated?.invoke(it) }

    override fun createSocket(host: String, port: Int): Socket =
        ProxyTunnelSocket(proxy, timeoutMs, rawTargetHost ?: host, if (rawTargetPort > 0) rawTargetPort else port).apply {
            onSocketCreated?.invoke(this)
            connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
        }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(rawTargetHost ?: host.hostAddress ?: host.hostName, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket(address, port)
}

private class ProxyTunnelSocket(
    private val proxy: ProxyConfig,
    private val defaultTimeoutMs: Int,
    private val rawTargetHost: String?,
    private val rawTargetPort: Int
) : Socket() {

    override fun connect(endpoint: SocketAddress) = connect(endpoint, defaultTimeoutMs)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported endpoint address")
        val destHost = rawTargetHost?.takeIf { it.isNotBlank() } ?: target.hostString
        val destPort = if (rawTargetPort > 0) rawTargetPort else target.port
        val effTimeout = if (timeout > 0) timeout else defaultTimeoutMs
        val deadlineNs = System.nanoTime() + effTimeout.toLong() * 1_000_000L
        try {
            val connectRemMs = remainingTimeoutMs(deadlineNs)
            val cleanProxyHost = proxy.host.trim().removeSurrounding("[", "]")
            super.connect(InetSocketAddress(cleanProxyHost, proxy.port), connectRemMs)
            runCatching { tcpNoDelay = true }
            soTimeout = remainingTimeoutMs(deadlineNs)
            when (proxy.type.lowercase()) {
                ProxyType.SOCKS5 -> ProxyHandshakes.socks5(
                    input = getInputStream(),
                    out = getOutputStream(),
                    proxy = proxy,
                    targetHost = destHost,
                    targetPort = destPort,
                    deadlineNs = deadlineNs,
                    setSoTimeout = { ms -> soTimeout = ms }
                )
                ProxyType.HTTP -> ProxyHandshakes.httpConnect(
                    input = getInputStream(),
                    out = getOutputStream(),
                    proxy = proxy,
                    targetHost = destHost,
                    targetPort = destPort,
                    deadlineNs = deadlineNs,
                    setSoTimeout = { ms -> soTimeout = ms }
                )
                else -> throw IOException("不支持的代理类型: ${proxy.type}")
            }
            soTimeout = 0
        } catch (e: Exception) {
            runCatching { close() }
            throw e
        }
    }

    private fun remainingTimeoutMs(deadlineNs: Long): Int {
        val remNs = deadlineNs - System.nanoTime()
        if (remNs <= 0L) throw SocketTimeoutException("代理连接超时")
        return ((remNs + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }
}
