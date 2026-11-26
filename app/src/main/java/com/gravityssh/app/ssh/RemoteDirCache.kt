package com.gravityssh.app.ssh

import com.gravityssh.app.model.SFTPFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
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
        getCached(sessionId, normalizeDir(dirPath, homeDir))?.let { filterHidden(it, showHidden) }

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
        val norm = normalizeDir(dirPath, homeDir)
        getCached(sessionId, norm)?.let { return@withContext filterHidden(it, showHidden) }
        val entries = runCatching {
            val sftp = sftpProvider() ?: return@runCatching null
            val resolved = SftpDeleteOps.resolvePath(sftp, norm)
            sftp.ls(resolved).asSequence()
                .filter { it.name != "." && it.name != ".." }
                .map { info ->
                    val a = info.attributes
                    SFTPFileEntry(
                        info.name, info.path, info.isDirectory,
                        a.type == FileMode.Type.SYMLINK, a.size,
                        SftpDeleteOps.formatPermissions(a.mode.mask), "", a.mtime
                    )
                }
                .sortedWith(compareByDescending<SFTPFileEntry> { it.isDir }.thenBy { it.name.lowercase() })
                .take(MAX_LIST_ENTRIES)
                .toList()
        }.getOrNull() ?: return@withContext emptyList()
        put(sessionId, norm, entries)
        filterHidden(entries, showHidden)
    }

    private fun evictIfNeeded(now: Long) {
        cache.entries.removeIf { now - it.value.cachedAtMs >= ttlMs }
        while (cache.size >= MAX_ENTRIES) {
            val oldest = cache.entries.minByOrNull { it.value.cachedAtMs } ?: break
            cache.remove(oldest.key, oldest.value)
        }
    }

    private fun normalizeDir(dirPath: String, homeDir: String): String {
        val raw = dirPath.trim().ifBlank { "/" }
        val expanded = if (raw == "~" || raw.startsWith("~/")) {
            val home = homeDir.ifBlank { "/" }
            if (raw == "~") home else "${home.trimEnd('/')}/${raw.removePrefix("~/")}"
        } else raw
        return ShellPathTracker.normalizePosixPath(expanded)
    }

    private fun filterHidden(entries: List<SFTPFileEntry>, showHidden: Boolean): List<SFTPFileEntry> =
        if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }

    private fun cacheKey(sessionId: String, dirPath: String): String =
        "$sessionId|${ShellPathTracker.normalizePosixPath(dirPath.ifBlank { "/" })}"

    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_LIST_ENTRIES = 200
    }
}
