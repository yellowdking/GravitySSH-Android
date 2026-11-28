package com.gravityssh.app.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class SshBatch2HardeningTest {

    @Test
    fun keyDeployScriptEnsuresTrailingLfGrepSeparatorAndPortableBase64() {
        val script = KeyDeployer.buildDeployScript("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKey user@host")
        assertTrue(script.contains("(base64 -d 2>/dev/null || base64 -D)"))
        assertTrue(script.contains("grep -qxF --"))
        assertTrue(script.contains("tail -c 1"))
        assertTrue(script.contains("printf \"\\n\""))
        assertTrue(script.contains("umask 077"))
        assertTrue(script.contains("chmod 600"))
    }

    @Test
    fun sftpTransferHelpersBuildHiddenTempPathAndHandleStatFailureTotal() {
        assertEquals(
            "/etc/nginx/.gssh-tmp-abc123",
            SftpTransferOps.buildAtomicTempPath("/etc/nginx/nginx.conf", "abc123")
        )
        assertEquals(
            "/.gssh-tmp-xyz",
            SftpTransferOps.buildAtomicTempPath("/rootfile.txt", "xyz")
        )
        assertEquals(4096L, SftpTransferOps.resolveEffectiveTotal(-1L, 4096L))
        assertEquals(128L, SftpTransferOps.resolveEffectiveTotal(0L, 128L))
        assertEquals(2048L, SftpTransferOps.resolveEffectiveTotal(2048L, 1024L))
        assertEquals(3000L, SftpTransferOps.resolveEffectiveTotal(2048L, 3000L))
    }

    @Test
    fun sftpArchiveCompressValidatesCommonParentAndMatchesTarFlags() {
        val zipCmd = SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt", "/opt/b.txt"), "/opt/out.zip")
        assertTrue(zipCmd.contains("cd '/opt' && zip -rq '/opt/out.zip' -- 'a.txt' 'b.txt'"))

        val gzCmd = SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt"), "/opt/out.tar.gz")
        assertTrue(gzCmd.contains("tar -czf '/opt/out.tar.gz'"))

        val tgzCmd = SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt"), "/opt/out.tgz")
        assertTrue(tgzCmd.contains("tar -czf '/opt/out.tgz'"))

        val bz2Cmd = SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt"), "/opt/out.tar.bz2")
        assertTrue(bz2Cmd.contains("tar -cjf '/opt/out.tar.bz2'"))

        val xzCmd = SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt"), "/opt/out.tar.xz")
        assertTrue(xzCmd.contains("tar -cJf '/opt/out.tar.xz'"))

        val tarCmd = SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt"), "/opt/out.tar")
        assertTrue(tarCmd.contains("tar -cf '/opt/out.tar'"))
        assertFalse(tarCmd.contains("-czf"))

        // Different parent directories must fail
        assertTrue(
            runCatching {
                SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt", "/var/b.txt"), "/opt/out.tar.gz")
            }.exceptionOrNull() is IllegalArgumentException
        )

        // Root directory cannot be archived
        assertTrue(
            runCatching {
                SftpArchiveOps.buildCompressCommand(listOf("/"), "/opt/out.tar.gz")
            }.exceptionOrNull() is IllegalArgumentException
        )

        // Unsupported archive suffix rejected
        assertTrue(
            runCatching {
                SftpArchiveOps.buildCompressCommand(listOf("/opt/a.txt"), "/opt/out.rar")
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun shellPathTrackerHandlesChainedCommandsFlagsAndRejectsExpansions() {
        assertEquals("/opt", ShellPathTracker.resolveNextDir("cd /opt && ls -la", "/root", "/root"))
        assertEquals("/opt/sub", ShellPathTracker.resolveNextDir("cd /opt; cd -P sub || echo fail", "/root", "/root"))
        assertEquals("/var/log", ShellPathTracker.resolveNextDir("cd -L -- /var/log", "/root", "/root"))
        assertEquals("/opt/-weird", ShellPathTracker.resolveNextDir("cd -- -weird", "/opt", "/root"))
        assertEquals("/opt/my dir", ShellPathTracker.resolveNextDir("cd \"/opt/my dir\" && pwd", "/root", "/root"))
        assertEquals("/home/alice", ShellPathTracker.resolveNextDir("cd", "/opt", "/home/alice"))
        assertEquals("/home/alice/app", ShellPathTracker.resolveNextDir("cd ~/app", "/opt", "/home/alice"))

        // Reject shell variable/command/glob expansions, OLDPWD, other users, and piped cd
        assertNull(ShellPathTracker.resolveNextDir("cd \$HOME/app", "/opt", "/root"))
        assertNull(ShellPathTracker.resolveNextDir("cd `pwd`", "/opt", "/root"))
        assertNull(ShellPathTracker.resolveNextDir("cd /tmp/app*", "/opt", "/root"))
        assertNull(ShellPathTracker.resolveNextDir("cd /opt && cd -", "/root", "/root"))
        assertNull(ShellPathTracker.resolveNextDir("cd ~other/app", "/root", "/root"))
        assertNull(ShellPathTracker.resolveNextDir("cd /opt | cat", "/root", "/root"))
    }

    @Test
    fun socks5ConnectRequestEncodesIpv4Ipv6AndDomainProperly() {
        // IPv4 -> ATYP = 0x01
        val v4 = ProxyHandshakes.buildSocks5ConnectRequest("198.51.100.10", 22)
        assertEquals(10, v4.size)
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 198.toByte(), 51, 100, 10, 0x00, 22),
            v4
        )

        // Bracketed IPv6 -> ATYP = 0x04
        val v6 = ProxyHandshakes.buildSocks5ConnectRequest("[2001:db8::1]", 2222)
        assertEquals(22, v6.size)
        assertEquals(0x04.toByte(), v6[3])
        assertEquals(0x20.toByte(), v6[4])
        assertEquals(0x01.toByte(), v6[5])
        assertEquals(0x0d.toByte(), v6[6])
        assertEquals(0xb8.toByte(), v6[7])
        assertEquals(0x01.toByte(), v6[19])
        assertEquals(((2222 ushr 8) and 0xFF).toByte(), v6[20])
        assertEquals((2222 and 0xFF).toByte(), v6[21])

        // Loopback ::1 and IPv4-mapped ::ffff:198.51.100.10
        val lo = ProxyHandshakes.buildSocks5ConnectRequest("::1", 22)
        assertEquals(22, lo.size)
        assertEquals(0x04.toByte(), lo[3])
        assertEquals(0x01.toByte(), lo[19])

        val mapped = ProxyHandshakes.buildSocks5ConnectRequest("::ffff:198.51.100.10", 443)
        assertEquals(22, mapped.size)
        assertEquals(0x04.toByte(), mapped[3])
        assertEquals(0xFF.toByte(), mapped[14])
        assertEquals(0xFF.toByte(), mapped[15])
        assertEquals(198.toByte(), mapped[16])
        assertEquals(51.toByte(), mapped[17])
        assertEquals(100.toByte(), mapped[18])
        assertEquals(10.toByte(), mapped[19])

        // Domain -> ATYP = 0x03
        val dom = ProxyHandshakes.buildSocks5ConnectRequest("ssh.example.com", 22)
        assertEquals(0x03.toByte(), dom[3])
        assertEquals(15.toByte(), dom[4])

        // Malformed IPv6 must not parse as IPv6
        assertNull(ProxyHandshakes.parseIpv6Bytes("1:::2"))
        assertNull(ProxyHandshakes.parseIpv6Bytes("2001:db8:1"))
    }

    @Test
    fun hostMetricsParserUsesExactUnsupportedCheck() {
        val unsupported = HostMetricsParser.parseDetailed("s1", "UNSUPPORTED\n", 10L, null, null, 0L, 1000L)
        assertFalse(unsupported.metrics.supported)

        val validWithWord = """
            ---CPU---
            cpu  100 0 100 800 0 0 0 0
            ---MEM---
            MemTotal: 2048000
            MemAvailable: 1024000
            ---DISK---
            55% /mnt/UNSUPPORTED_VOL
            ---GPU---
            20, NVIDIA UNSUPPORTED
        """.trimIndent()
        val parsed = HostMetricsParser.parseDetailed("s1", validWithWord, 12L, null, null, 0L, 2000L)
        assertTrue(parsed.metrics.supported)
        assertEquals("/mnt/UNSUPPORTED_VOL", parsed.metrics.diskMount)
        assertEquals("NVIDIA UNSUPPORTED", parsed.metrics.gpuName)
    }

    @Test
    fun sshExecHelperRunBoundedTimesOutWithIoExceptionAndCancelsCleanly(): Unit = runBlocking {
        // 1. Timeout throws IOException (not CancellationException) and runs cleanup callback
        val timeoutCleaned = AtomicBoolean(false)
        val timeoutErr = runCatching {
            SshExecHelper.runBoundedMs(
                timeoutMs = 80L,
                timeoutMessage = "远端命令执行超时 (1s)",
                onCancelOrTimeout = { timeoutCleaned.set(true) }
            ) {
                awaitCancellation()
            }
        }.exceptionOrNull()
        assertTrue("Expected IOException on timeout, got $timeoutErr", timeoutErr is IOException)
        assertFalse(timeoutErr is CancellationException)
        assertTrue(timeoutCleaned.get())

        // 2. External cancellation propagates CancellationException and runs cleanup callback
        val entered = CompletableDeferred<Unit>()
        val cancelCleaned = AtomicBoolean(false)
        val caughtCancel = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            try {
                SshExecHelper.runBoundedMs(
                    timeoutMs = 5_000L,
                    timeoutMessage = "timeout",
                    onCancelOrTimeout = { cancelCleaned.set(true) }
                ) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (_: CancellationException) {
                caughtCancel.set(true)
            }
        }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(cancelCleaned.get())
        assertTrue(caughtCancel.get())
    }
}
