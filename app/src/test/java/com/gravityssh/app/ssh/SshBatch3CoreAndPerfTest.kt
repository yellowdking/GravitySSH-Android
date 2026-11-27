package com.gravityssh.app.ssh

import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SshBatch3CoreAndPerfTest {

    @Test
    fun hostMetricsParserCoversMemFallbackMultiDiskSpacesNetWrapAndCoresFallback() {
        val raw1 = """
            ---CPU---
            cpu  200 0 100 700 0 0 0 0
            ---MEM---
            MemTotal: 4096000
            MemFree: 512000
            Buffers: 256000
            Cached: 256000
            ---DISK---
            35% /
            88% /mnt/my data drive
            12% /boot
            ---NET---
            500000 600000
            ---LOAD---
            1.25 0.90 0.60 2/150 4321
        """.trimIndent()

        val p1 = HostMetricsParser.parseDetailed(
            sessionId = "s1",
            raw = raw1,
            latencyMs = 15L,
            prevCpu = null,
            prevNet = null,
            prevSampleMs = 0L,
            nowMs = 10_000L,
            fallbackCores = 8
        )
        assertTrue(p1.metrics.supported)
        assertEquals(0.0, p1.metrics.cpuPercent, 0.001)
        assertEquals(4000L, p1.metrics.memTotalMb)
        assertEquals(3000L, p1.metrics.memUsedMb)
        assertEquals(75.0, p1.metrics.memPercent, 0.01)
        assertEquals(88.0, p1.metrics.diskPercent, 0.01)
        assertEquals("/mnt/my data drive", p1.metrics.diskMount)
        assertEquals(1.25, p1.metrics.load1, 0.001)
        assertEquals(8, p1.metrics.cores)
        assertEquals(0, p1.parsedCores)
        assertFalse(p1.metrics.hasGpu)

        // Counter wrap-around on rx (cur < prev) must clamp to 0.0 while tx computes normally
        val raw2 = raw1
            .replace("200 0 100 700", "250 0 150 800")
            .replace("500000 600000", "100000 804800")
        val p2 = HostMetricsParser.parseDetailed(
            sessionId = "s1",
            raw = raw2,
            latencyMs = 14L,
            prevCpu = p1.cpu,
            prevNet = p1.net,
            prevSampleMs = 10_000L,
            nowMs = 12_000L,
            fallbackCores = 8
        )
        assertEquals(50.0, p2.metrics.cpuPercent, 0.5)
        assertEquals(0.0, p2.metrics.netRxKbps, 0.001)
        assertEquals(100.0, p2.metrics.netTxKbps, 0.5)

        // Too-short sampling interval (<= 0.2s) keeps net rates at 0.0
        val pShort = HostMetricsParser.parseDetailed(
            sessionId = "s1",
            raw = raw2,
            latencyMs = 14L,
            prevCpu = p1.cpu,
            prevNet = p1.net,
            prevSampleMs = 10_000L,
            nowMs = 10_100L,
            fallbackCores = 8
        )
        assertEquals(0.0, pShort.metrics.netTxKbps, 0.001)
    }

    @Test
    fun proxySocketFactoryPerformsHttpConnectAndSocks5HandshakesOverLoopback() {
        // 1. HTTP CONNECT with Basic Auth
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val capturedLines = AtomicReference<List<String>>(emptyList())
            val serverThread = Thread {
                runCatching {
                    server.accept().use { sock ->
                        sock.soTimeout = 3_000
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                        val lines = ArrayList<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            lines.add(line)
                        }
                        capturedLines.set(lines)
                        val out = sock.getOutputStream()
                        out.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.write("SSH-2.0-Test\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val createdCount = AtomicInteger(0)
            val factory = ProxySocketFactory(
                proxy = ProxyConfig(
                    type = ProxyType.HTTP,
                    host = "127.0.0.1",
                    port = server.localPort,
                    username = "alice",
                    password = "secret"
                ),
                timeoutMs = 3_000,
                rawTargetHost = "target.internal",
                rawTargetPort = 2222,
                onSocketCreated = { createdCount.incrementAndGet() }
            )

            factory.createSocket("ignored.local", 22).use { tunnel ->
                tunnel.soTimeout = 3_000
                val banner = BufferedReader(InputStreamReader(tunnel.getInputStream(), Charsets.US_ASCII)).readLine()
                assertEquals("SSH-2.0-Test", banner)
            }
            serverThread.join(3_000)

            assertEquals(1, createdCount.get())
            val lines = capturedLines.get()
            assertEquals("CONNECT target.internal:2222 HTTP/1.1", lines.firstOrNull())
            val expectedAuth = "Proxy-Authorization: Basic " +
                Base64.getEncoder().encodeToString("alice:secret".toByteArray(Charsets.UTF_8))
            assertTrue("Expected Basic auth header in $lines", lines.contains(expectedAuth))
        }

        // 2. SOCKS5 with username/password auth + domain target
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val receivedConnectReq = AtomicReference<ByteArray?>(null)
            val serverThread = Thread {
                runCatching {
                    server.accept().use { sock ->
                        sock.soTimeout = 3_000
                        val inp = sock.getInputStream()
                        val out = sock.getOutputStream()

                        val greet = ByteArray(4)
                        readFully(inp, greet)
                        if (!greet.contentEquals(byteArrayOf(0x05, 0x02, 0x00, 0x02))) return@use
                        out.write(byteArrayOf(0x05, 0x02))
                        out.flush()

                        val authHead = ByteArray(2)
                        readFully(inp, authHead)
                        val uLen = authHead[1].toInt() and 0xFF
                        val userBytes = ByteArray(uLen)
                        readFully(inp, userBytes)
                        val pLen = inp.read()
                        val passBytes = ByteArray(pLen)
                        readFully(inp, passBytes)
                        if (String(userBytes, Charsets.UTF_8) != "bob" || String(passBytes, Charsets.UTF_8) != "pw") {
                            out.write(byteArrayOf(0x01, 0x01))
                            out.flush()
                            return@use
                        }
                        out.write(byteArrayOf(0x01, 0x00))
                        out.flush()

                        val reqHead = ByteArray(5)
                        readFully(inp, reqHead)
                        val domLen = reqHead[4].toInt() and 0xFF
                        val rest = ByteArray(domLen + 2)
                        readFully(inp, rest)
                        receivedConnectReq.set(reqHead + rest)

                        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x16))
                        out.write("OK\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val factory = ProxySocketFactory(
                proxy = ProxyConfig(
                    type = ProxyType.SOCKS5,
                    host = "127.0.0.1",
                    port = server.localPort,
                    username = "bob",
                    password = "pw"
                ),
                timeoutMs = 3_000,
                rawTargetHost = "ssh.example.com",
                rawTargetPort = 22
            )

            factory.createSocket("ssh.example.com", 22).use { tunnel ->
                tunnel.soTimeout = 3_000
                val line = BufferedReader(InputStreamReader(tunnel.getInputStream(), Charsets.US_ASCII)).readLine()
                assertEquals("OK", line)
            }
            serverThread.join(3_000)
            assertArrayEquals(
                ProxyHandshakes.buildSocks5ConnectRequest("ssh.example.com", 22),
                receivedConnectReq.get()
            )
        }

        // 3. Unsupported proxy type throws IOException and closes socket
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepter = Thread {
                runCatching { server.accept().close() }
            }.apply { isDaemon = true; start() }

            val tracked = AtomicReference<Socket?>(null)
            val factory = ProxySocketFactory(
                proxy = ProxyConfig(type = "unsupported", host = "127.0.0.1", port = server.localPort),
                timeoutMs = 2_000,
                onSocketCreated = { tracked.set(it) }
            )
            val err = runCatching { factory.createSocket("example.com", 22) }.exceptionOrNull()
            assertTrue(err is IOException)
            assertTrue(tracked.get()?.isClosed == true)
            accepter.join(2_000)
        }
    }

    @Test
    fun sftpServiceParallelSymlinkThresholdIsEight() {
        assertEquals(8, SftpService.PARALLEL_SYMLINK_STAT_THRESHOLD)
        for (count in 0..7) {
            assertFalse("Expected serial stat for $count symlinks", SftpService.shouldParallelizeSymlinkStats(count))
        }
        for (count in listOf(8, 9, 16, 32)) {
            assertTrue("Expected parallel stat for $count symlinks", SftpService.shouldParallelizeSymlinkStats(count))
        }
    }

    @Test
    fun shellPathTrackerCoversEscapedSpacesRedirsAndReadOnlyClassification() {
        assertEquals("/opt/my app", ShellPathTracker.resolveNextDir("cd /opt/my\\ app", "/root", "/root"))
        assertEquals("/var/log", ShellPathTracker.resolveNextDir("cd /var/log 2>/dev/null", "/root", "/root"))
        assertEquals("/usr/bin", ShellPathTracker.resolveNextDir("cd ../bin", "/usr/local", "/root"))
        assertNull(ShellPathTracker.resolveNextDir("ls -la /tmp", "/root", "/root"))

        assertTrue(ShellPathTracker.isLikelyReadOnlyCommand("ls -la"))
        assertTrue(ShellPathTracker.isLikelyReadOnlyCommand("/usr/bin/cat /etc/os-release"))
        assertTrue(ShellPathTracker.isLikelyReadOnlyCommand("git status"))
        assertTrue(ShellPathTracker.isLikelyReadOnlyCommand("docker ps"))
        assertTrue(ShellPathTracker.isLikelyReadOnlyCommand("systemctl is-active sshd"))

        assertFalse(ShellPathTracker.isLikelyReadOnlyCommand("rm -rf /tmp/build"))
        assertFalse(ShellPathTracker.isLikelyReadOnlyCommand("echo hi > out.txt"))
        assertFalse(ShellPathTracker.isLikelyReadOnlyCommand("ls | tee files.txt"))
        assertFalse(ShellPathTracker.isLikelyReadOnlyCommand("git commit -m test"))
        assertFalse(ShellPathTracker.isLikelyReadOnlyCommand("docker rm app"))
        assertFalse(ShellPathTracker.isLikelyReadOnlyCommand("echo \$(whoami)"))
    }

    @Test
    fun sshSessionWorkerMetricsPausePredicateAndFlowWaitSuspendCleanly() = runBlocking {
        // Pure predicate truth table
        assertTrue(SshSessionWorker.shouldSampleMetrics("s1", "s1", appForeground = true, monitorBarEnabled = true))
        assertFalse(SshSessionWorker.shouldSampleMetrics("s1", "s2", appForeground = true, monitorBarEnabled = true))
        assertFalse(SshSessionWorker.shouldSampleMetrics("s1", null, appForeground = true, monitorBarEnabled = true))
        assertFalse(SshSessionWorker.shouldSampleMetrics("s1", "s1", appForeground = false, monitorBarEnabled = true))
        assertFalse(SshSessionWorker.shouldSampleMetrics("s1", "s1", appForeground = true, monitorBarEnabled = false))

        // Flow-based wait remains suspended while backgrounded, inactive, or disabled, and wakes immediately on resume
        val activeFlow = MutableStateFlow<String?>("s1")
        val fgFlow = MutableStateFlow(false)
        val enabledFlow = MutableStateFlow(true)
        val woke = CompletableDeferred<Unit>()

        val waiter = async(Dispatchers.Default) {
            SshSessionWorker.awaitMetricsSamplingAllowed("s1", activeFlow, fgFlow, enabledFlow)
            woke.complete(Unit)
        }

        delay(40L)
        assertFalse(woke.isCompleted)

        // Returning to foreground while another tab is active keeps s1 paused
        activeFlow.value = "s2"
        fgFlow.value = true
        delay(40L)
        assertFalse(woke.isCompleted)

        // Switching to s1 while monitor bar is disabled keeps s1 paused
        enabledFlow.value = false
        activeFlow.value = "s1"
        delay(40L)
        assertFalse(woke.isCompleted)

        // Navigating away (activeSessionId = null) with monitor enabled keeps s1 paused
        activeFlow.value = null
        enabledFlow.value = true
        delay(40L)
        assertFalse(woke.isCompleted)

        // Returning to s1 wakes promptly
        activeFlow.value = "s1"
        withTimeout(2_000L) { woke.await() }
        waiter.await()
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("EOF")
            off += n
        }
    }
}
