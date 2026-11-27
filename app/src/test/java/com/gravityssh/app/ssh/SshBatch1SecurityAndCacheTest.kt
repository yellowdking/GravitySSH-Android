package com.gravityssh.app.ssh

import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SFTPFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SshBatch1SecurityAndCacheTest {

    @Test
    fun safeFillPayloadNeverAutoExecutesMultiLineOrTrailingNewline() {
        // Single-line trailing newline stripped
        assertEquals("ls -la", ManagedSession.formatSafeFillPayload("ls -la\n", bracketedPasteEnabled = false))
        assertEquals("ls -la", ManagedSession.formatSafeFillPayload("ls -la\r\n", bracketedPasteEnabled = true))

        // Multi-line with bracketed paste wraps & loop-strips nested breakout sequences
        val bpPayload = ManagedSession.formatSafeFillPayload(
            "echo 1\n\u001B[20\u001B[201~1~\necho 2\n",
            bracketedPasteEnabled = true
        )
        assertTrue(bpPayload.startsWith("\u001B[200~"))
        assertTrue(bpPayload.endsWith("\u001B[201~"))
        val inner = bpPayload.removePrefix("\u001B[200~").removeSuffix("\u001B[201~")
        assertFalse(inner.contains("\u001B[201~"))
        assertFalse(inner.contains("\u001B[200~"))

        // Multi-line without bracketed paste collapses safely with zero \r or \n
        val flatPayload = ManagedSession.formatSafeFillPayload("cd /opt\nls -la\npwd\n", bracketedPasteEnabled = false)
        assertFalse(flatPayload.contains('\n'))
        assertFalse(flatPayload.contains('\r'))
        assertEquals("cd /opt; ls -la; pwd", flatPayload)
    }

    @Test
    fun httpConnectCapsHeaderLinesBytesAndCumulativeDeadline() {
        val proxy = ProxyConfig(type = ProxyType.HTTP, host = "127.0.0.1", port = 8080)

        // Valid response passes
        val okResp = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n"
        ProxyHandshakes.httpConnect(
            ByteArrayInputStream(okResp.toByteArray(Charsets.US_ASCII)),
            ByteArrayOutputStream(),
            proxy,
            "example.com",
            22
        )

        // >64 header lines rejected
        val tooManyHeaders = buildString {
            append("HTTP/1.1 200 OK\r\n")
            repeat(ProxyHandshakes.MAX_HTTP_HEADER_LINES + 2) { append("X-H-$it: v\r\n") }
            append("\r\n")
        }
        assertTrue(
            runCatching {
                ProxyHandshakes.httpConnect(
                    ByteArrayInputStream(tooManyHeaders.toByteArray(Charsets.US_ASCII)),
                    ByteArrayOutputStream(),
                    proxy,
                    "example.com",
                    22
                )
            }.exceptionOrNull() is IOException
        )

        // >32 KiB total header bytes rejected
        val hugeHeaders = buildString {
            append("HTTP/1.1 200 OK\r\n")
            repeat(20) { append("X-Big-$it: ").append("a".repeat(2000)).append("\r\n") }
            append("\r\n")
        }
        assertTrue(
            runCatching {
                ProxyHandshakes.httpConnect(
                    ByteArrayInputStream(hugeHeaders.toByteArray(Charsets.US_ASCII)),
                    ByteArrayOutputStream(),
                    proxy,
                    "example.com",
                    22
                )
            }.exceptionOrNull() is IOException
        )

        // Expired cumulative deadline throws SocketTimeoutException
        val expiredDeadline = System.nanoTime() - 1_000_000L
        assertTrue(
            runCatching {
                ProxyHandshakes.httpConnect(
                    ByteArrayInputStream(okResp.toByteArray(Charsets.US_ASCII)),
                    ByteArrayOutputStream(),
                    proxy,
                    "example.com",
                    22,
                    deadlineNs = expiredDeadline
                )
            }.exceptionOrNull() is SocketTimeoutException
        )
    }

    @Test
    fun remoteDirCacheEpochPreventsStaleRepopulationAndLeaderCancelRetries() = runTest {
        val cache = RemoteDirCache(ttlMs = 60_000L)

        // 1. Invalidation mid-fetch prevents stale result from populating cache
        val fetchStarted = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        val staleFetch = async {
            cache.listDirWithFetcher("s1", "/opt", "/", true) {
                fetchStarted.complete(Unit)
                allowFinish.await()
                listOf(SFTPFileEntry("stale.txt", "/opt/stale.txt", isDir = false))
            }
        }
        fetchStarted.await()
        cache.invalidateDir("s1", "/opt")
        allowFinish.complete(Unit)
        assertEquals(1, staleFetch.await().size)
        assertNull(cache.getCached("s1", "/opt"))

        // 2. Leader cancellation wakes non-cancelled waiter to retry instead of getting emptyList()
        val leaderEntered = CompletableDeferred<Unit>()
        val leaderHold = CompletableDeferred<Unit>()
        val waiterJoined = CompletableDeferred<Unit>()
        val attempts = AtomicInteger(0)
        cache.onCoalescedWait = { waiterJoined.complete(Unit) }

        val leader = async {
            cache.listDirWithFetcher("s2", "/var", "/", true) {
                if (attempts.incrementAndGet() == 1) {
                    leaderEntered.complete(Unit)
                    leaderHold.await()
                }
                listOf(SFTPFileEntry("fresh.log", "/var/fresh.log", isDir = false))
            }
        }
        leaderEntered.await()

        val waiter = async {
            cache.listDirWithFetcher("s2", "/var", "/", true) {
                attempts.incrementAndGet()
                listOf(SFTPFileEntry("fresh.log", "/var/fresh.log", isDir = false))
            }
        }
        waiterJoined.await()

        // Cancel leader while waiter is coalesced on its deferred
        leader.cancel(CancellationException("cancelled leader"))
        val waiterResult = waiter.await()
        cache.onCoalescedWait = null
        assertEquals(listOf("fresh.log"), waiterResult.map { it.name })
        assertTrue(attempts.get() >= 2)
    }
}
