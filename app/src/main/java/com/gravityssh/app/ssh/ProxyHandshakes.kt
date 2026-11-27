package com.gravityssh.app.ssh

import com.gravityssh.app.model.ProxyConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Base64

internal object ProxyHandshakes {

    private val HTTP_OK_RE = Regex("^HTTP/1\\.[01]\\s+200(?:\\s.*)?$")
    internal const val MAX_HTTP_HEADER_LINES = 64
    internal const val MAX_HTTP_HEADER_BYTES = 32 * 1024
    private const val MAX_SINGLE_HEADER_LINE_CHARS = 4096

    fun socks5(
        input: InputStream,
        out: OutputStream,
        proxy: ProxyConfig,
        targetHost: String,
        targetPort: Int,
        deadlineNs: Long = 0L,
        setSoTimeout: ((Int) -> Unit)? = null
    ) {
        val cleanHost = sanitizeTargetHost(targetHost).removeSurrounding("[", "]")
        val hasAuth = proxy.username.isNotEmpty() || proxy.password.isNotEmpty()
        out.write(if (hasAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        val methodResp = readExact(input, 2, deadlineNs, setSoTimeout)
        if (methodResp[0].toInt() != 0x05) throw IOException("SOCKS5 协议版本错误")
        when (methodResp[1].toInt() and 0xFF) {
            0x00 -> Unit
            0x02 -> {
                val u = proxy.username.toByteArray(Charsets.UTF_8)
                val p = proxy.password.toByteArray(Charsets.UTF_8)
                if (u.size > 255 || p.size > 255) throw IOException("SOCKS5 凭据过长")
                val authReq = ByteArray(3 + u.size + p.size)
                authReq[0] = 0x01
                authReq[1] = u.size.toByte()
                System.arraycopy(u, 0, authReq, 2, u.size)
                authReq[2 + u.size] = p.size.toByte()
                System.arraycopy(p, 0, authReq, 3 + u.size, p.size)
                out.write(authReq)
                out.flush()
                if (readExact(input, 2, deadlineNs, setSoTimeout)[1].toInt() != 0x00) {
                    throw IOException("SOCKS5 代理鉴权失败")
                }
            }
            else -> throw IOException("SOCKS5 代理拒绝认证方式")
        }

        val hostBytes = cleanHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) throw IOException("目标主机名过长")
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03; req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val head = readExact(input, 4, deadlineNs, setSoTimeout)
        if (head[1].toInt() != 0x00) throw IOException("SOCKS5 连接目标失败 (code=${head[1].toInt() and 0xFF})")
        val addrLen = when (val atyp = head[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> readExact(input, 1, deadlineNs, setSoTimeout)[0].toInt() and 0xFF
            0x04 -> 16
            else -> throw IOException("SOCKS5 未知地址类型: $atyp")
        }
        readExact(input, addrLen + 2, deadlineNs, setSoTimeout)
    }

    fun httpConnect(
        input: InputStream,
        out: OutputStream,
        proxy: ProxyConfig,
        targetHost: String,
        targetPort: Int,
        deadlineNs: Long = 0L,
        setSoTimeout: ((Int) -> Unit)? = null
    ) {
        val cleanHost = sanitizeTargetHost(targetHost)
        val hostHeader = if (cleanHost.contains(':') && !(cleanHost.startsWith('[') && cleanHost.endsWith(']'))) {
            "[$cleanHost]:$targetPort"
        } else {
            "$cleanHost:$targetPort"
        }
        val sb = StringBuilder()
            .append("CONNECT $hostHeader HTTP/1.1\r\n")
            .append("Host: $hostHeader\r\n")
        if (proxy.username.isNotEmpty() || proxy.password.isNotEmpty()) {
            val token = Base64.getEncoder()
                .encodeToString("${proxy.username}:${proxy.password}".toByteArray(Charsets.UTF_8))
            sb.append("Proxy-Authorization: Basic $token\r\n")
        }
        sb.append("Proxy-Connection: Keep-Alive\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        var totalBytes = 0
        val (statusLine, statusBytes) = readAsciiLine(
            input = input,
            maxBytes = MAX_HTTP_HEADER_BYTES,
            deadlineNs = deadlineNs,
            setSoTimeout = setSoTimeout
        )
        totalBytes += statusBytes
        if (!HTTP_OK_RE.matches(statusLine)) {
            throw IOException("HTTP 代理连接失败: $statusLine")
        }

        var headerLines = 0
        while (true) {
            val remainingBytes = MAX_HTTP_HEADER_BYTES - totalBytes
            val (line, lineBytes) = readAsciiLine(
                input = input,
                maxBytes = remainingBytes,
                deadlineNs = deadlineNs,
                setSoTimeout = setSoTimeout
            )
            totalBytes += lineBytes
            if (line.isEmpty()) break
            headerLines++
            if (headerLines > MAX_HTTP_HEADER_LINES) {
                throw IOException("HTTP 代理响应头行数超过上限 ($MAX_HTTP_HEADER_LINES)")
            }
        }
    }

    private fun sanitizeTargetHost(targetHost: String): String {
        val trimmed = targetHost.trim()
        if (trimmed.isEmpty() || trimmed.any { it == '\r' || it == '\n' || it.isWhitespace() || it.code < 0x20 }) {
            throw IOException("无效的目标主机名")
        }
        return trimmed
    }

    private fun applyDeadline(deadlineNs: Long, setSoTimeout: ((Int) -> Unit)?) {
        if (deadlineNs <= 0L) return
        val remNs = deadlineNs - System.nanoTime()
        if (remNs <= 0L) throw SocketTimeoutException("代理握手超时")
        val remMs = ((remNs + 999_999L) / 1_000_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
        setSoTimeout?.invoke(remMs)
    }

    private fun readExact(
        input: InputStream,
        len: Int,
        deadlineNs: Long,
        setSoTimeout: ((Int) -> Unit)?
    ): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            applyDeadline(deadlineNs, setSoTimeout)
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("代理连接意外关闭")
            off += n
        }
        return buf
    }

    private fun readAsciiLine(
        input: InputStream,
        maxBytes: Int,
        deadlineNs: Long,
        setSoTimeout: ((Int) -> Unit)?
    ): Pair<String, Int> {
        val sb = StringBuilder()
        var bytesRead = 0
        while (true) {
            if (bytesRead >= maxBytes) {
                throw IOException("HTTP 代理响应头超过 32 KiB 上限")
            }
            applyDeadline(deadlineNs, setSoTimeout)
            val b = input.read()
            if (b < 0) throw IOException("HTTP 代理提前断开")
            bytesRead++
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                if (sb.length >= MAX_SINGLE_HEADER_LINE_CHARS) {
                    throw IOException("HTTP 代理响应头单行超过限制")
                }
                sb.append(b.toChar())
            }
        }
        return sb.toString() to bytesRead
    }
}
