package com.gravityssh.app.ssh

import com.gravityssh.app.model.SFTPFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient
import java.util.concurrent.ConcurrentHashMap

internal class RemoteDirCache(
    private val ttlMs: Long = 6_000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class CacheEntry(
        val entries: List<SFTPFileEntry>,
        val cachedAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<SFTPFileEntry>>>()

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
        val prefix = "$sessionId|"
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    fun invalidateDir(sessionId: String, dirPath: String) {
        cache.remove(cacheKey(sessionId, dirPath))
    }

    suspend fun listDir(
        sessionId: String,
        dirPath: String,
        homeDir: String,
        showHidden: Boolean,
        sftpProvider: () -> SFTPClient?
    ): List<SFTPFileEntry> = withContext(Dispatchers.IO) {
        val norm = SshPathUtils.expandHome(dirPath, homeDir)
        getCached(sessionId, norm)?.let { return@withContext filterAndLimit(it, showHidden) }

        val k = cacheKey(sessionId, norm)
        val myDeferred = CompletableDeferred<List<SFTPFileEntry>>()
        val existing = inFlight.putIfAbsent(k, myDeferred)
        if (existing != null) {
            return@withContext filterAndLimit(existing.await(), showHidden)
        }

        try {
            val fetched = try {
                val sftp = sftpProvider()
                if (sftp == null) {
                    null
                } else {
                    val resolved = SshPathUtils.resolvePath(sftp, norm)
                    val sorted = sftp.ls(resolved).asSequence()
                        .filter { it.name != "." && it.name != ".." }
                        .map { SshPathUtils.toSftpFileEntry(it, includeModTimeIso = false) }
                        .sortedWith(SshPathUtils.ENTRY_COMPARATOR)
                        .toList()
                    var visCount = 0
                    var hidCount = 0
                    sorted.filter { entry ->
                        if (entry.name.startsWith(".")) {
                            (++hidCount) <= MAX_LIST_ENTRIES
                        } else {
                            (++visCount) <= MAX_LIST_ENTRIES
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (fetched != null) {
                put(sessionId, norm, fetched)
                myDeferred.complete(fetched)
                filterAndLimit(fetched, showHidden)
            } else {
                myDeferred.complete(emptyList())
                emptyList()
            }
        } catch (e: CancellationException) {
            myDeferred.complete(emptyList())
            throw e
        } catch (_: Throwable) {
            myDeferred.complete(emptyList())
            emptyList()
        } finally {
            inFlight.remove(k, myDeferred)
        }
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
    }
}
