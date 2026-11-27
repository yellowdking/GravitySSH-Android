package com.gravityssh.app.ssh

import com.gravityssh.app.model.SFTPFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class RemoteDirCache(
    private val ttlMs: Long = 6_000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class CacheEntry(
        val entries: List<SFTPFileEntry>,
        val cachedAtMs: Long
    )

    private object LeaderCancelledException : Exception()

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<SFTPFileEntry>>>()
    private val sessionEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val keyEpochs = ConcurrentHashMap<String, AtomicLong>()
    internal var onCoalescedWait: (() -> Unit)? = null

    private fun sessionEpoch(sessionId: String): Long = sessionEpochs[sessionId]?.get() ?: 0L
    private fun keyEpoch(key: String): Long = keyEpochs[key]?.get() ?: 0L

    fun getCached(sessionId: String, dirPath: String): List<SFTPFileEntry>? {
        val k = cacheKey(sessionId, dirPath)
        val item = cache[k] ?: return null
        if (nowMs() - item.cachedAtMs >= ttlMs) {
            cache.remove(k, item)
            return null
        }
        return item.entries
    }

    fun peekCached(
        sessionId: String,
        dirPath: String,
        homeDir: String = "/",
        showHidden: Boolean = true
    ): List<SFTPFileEntry>? =
        getCached(sessionId, SshPathUtils.expandHome(dirPath, homeDir))?.let { filterAndLimit(it, showHidden) }

    fun put(sessionId: String, dirPath: String, entries: List<SFTPFileEntry>) {
        val k = cacheKey(sessionId, dirPath)
        val now = nowMs()
        if (!cache.containsKey(k) && cache.size >= MAX_ENTRIES) {
            evictIfNeeded(now)
        }
        cache[k] = CacheEntry(entries, now)
    }

    fun invalidateSession(sessionId: String) {
        sessionEpochs.computeIfAbsent(sessionId) { AtomicLong(0L) }.incrementAndGet()
        val prefix = "$sessionId|"
        cache.keys.removeIf { it.startsWith(prefix) }
        inFlight.keys.removeIf { it.startsWith(prefix) }
        keyEpochs.keys.removeIf { it.startsWith(prefix) }
    }

    fun invalidateDir(sessionId: String, dirPath: String) {
        val k = cacheKey(sessionId, dirPath)
        keyEpochs.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
        cache.remove(k)
        inFlight.remove(k)
    }

    suspend fun listDir(
        sessionId: String,
        dirPath: String,
        homeDir: String,
        showHidden: Boolean,
        sftpProvider: () -> SFTPClient?,
        onSftpError: ((SFTPClient, Throwable) -> Unit)? = null
    ): List<SFTPFileEntry> = listDirWithFetcher(
        sessionId = sessionId,
        dirPath = dirPath,
        homeDir = homeDir,
        showHidden = showHidden
    ) { norm ->
        val sftp = sftpProvider() ?: return@listDirWithFetcher null
        try {
            val resolved = SshPathUtils.resolvePath(sftp, norm)
            val sorted = SshPathUtils.mapAndSortEntries(
                rawList = sftp.ls(resolved),
                showHidden = true,
                includeModTimeIso = false
            )
            var visCount = 0
            var hidCount = 0
            sorted.filter { entry ->
                if (entry.name.startsWith(".")) {
                    (++hidCount) <= MAX_LIST_ENTRIES
                } else {
                    (++visCount) <= MAX_LIST_ENTRIES
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onSftpError?.invoke(sftp, e)
            null
        }
    }

    internal suspend fun listDirWithFetcher(
        sessionId: String,
        dirPath: String,
        homeDir: String,
        showHidden: Boolean,
        fetcher: suspend (normalizedDir: String) -> List<SFTPFileEntry>?
    ): List<SFTPFileEntry> = withContext(Dispatchers.IO) {
        val norm = SshPathUtils.expandHome(dirPath, homeDir)
        val k = cacheKey(sessionId, norm)

        repeat(MAX_WAIT_RETRIES) {
            getCached(sessionId, norm)?.let { return@withContext filterAndLimit(it, showHidden) }

            val myDeferred = CompletableDeferred<List<SFTPFileEntry>>()
            val existing = inFlight.putIfAbsent(k, myDeferred)
            if (existing != null) {
                try {
                    onCoalescedWait?.invoke()
                    return@withContext filterAndLimit(existing.await(), showHidden)
                } catch (_: LeaderCancelledException) {
                    currentCoroutineContext().ensureActive()
                    return@repeat
                }
            }

            val startSessEpoch = sessionEpoch(sessionId)
            val startKeyEpoch = keyEpoch(k)
            try {
                val fetched = try {
                    fetcher(norm)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (fetched != null) {
                    if (sessionEpoch(sessionId) == startSessEpoch && keyEpoch(k) == startKeyEpoch) {
                        put(sessionId, norm, fetched)
                    }
                    inFlight.remove(k, myDeferred)
                    myDeferred.complete(fetched)
                    return@withContext filterAndLimit(fetched, showHidden)
                } else {
                    inFlight.remove(k, myDeferred)
                    myDeferred.complete(emptyList())
                    return@withContext emptyList()
                }
            } catch (e: CancellationException) {
                inFlight.remove(k, myDeferred)
                myDeferred.completeExceptionally(LeaderCancelledException)
                throw e
            } catch (_: Throwable) {
                inFlight.remove(k, myDeferred)
                myDeferred.complete(emptyList())
                return@withContext emptyList()
            } finally {
                inFlight.remove(k, myDeferred)
            }
        }
        emptyList()
    }

    private fun evictIfNeeded(now: Long) {
        cache.entries.removeIf { now - it.value.cachedAtMs >= ttlMs }
        while (cache.size >= MAX_ENTRIES) {
            val oldest = cache.entries.minByOrNull { it.value.cachedAtMs } ?: break
            cache.remove(oldest.key, oldest.value)
        }
    }

    private fun filterAndLimit(entries: List<SFTPFileEntry>, showHidden: Boolean): List<SFTPFileEntry> {
        val filtered = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        return if (filtered.size <= MAX_LIST_ENTRIES) filtered else filtered.take(MAX_LIST_ENTRIES)
    }

    private fun cacheKey(sessionId: String, dirPath: String): String =
        "$sessionId|${SshPathUtils.normalizePosixPath(dirPath.ifBlank { "/" })}"

    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_WAIT_RETRIES = 4
    }
}
