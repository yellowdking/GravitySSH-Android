package com.gravityssh.app.ssh

import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class ProxySocketFactory(
    private val proxy: ProxyConfig,
    private val timeoutMs: Int = 15_000
) : SocketFactory() {

    override fun createSocket(): Socket = ProxyTunnelSocket(proxy, timeoutMs)

    override fun createSocket(host: String, port: Int): Socket =
        ProxyTunnelSocket(proxy, timeoutMs).apply {
            connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
        }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: host.hostName, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket(address, port)
}

private class ProxyTunnelSocket(
    private val proxy: ProxyConfig,
    private val defaultTimeoutMs: Int
) : Socket() {

    override fun connect(endpoint: SocketAddress) = connect(endpoint, defaultTimeoutMs)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported endpoint address")
        val effTimeout = if (timeout > 0) timeout else defaultTimeoutMs
        super.connect(InetSocketAddress(proxy.host.trim(), proxy.port), effTimeout)
        soTimeout = effTimeout
        when (proxy.type.lowercase()) {
            ProxyType.SOCKS5 -> ProxyHandshakes.socks5(getInputStream(), getOutputStream(), proxy, target.hostString, target.port)
            ProxyType.HTTP -> ProxyHandshakes.httpConnect(getInputStream(), getOutputStream(), proxy, target.hostString, target.port)
            else -> throw IOException("不支持的代理类型: ${proxy.type}")
        }
        soTimeout = 0
    }
}
