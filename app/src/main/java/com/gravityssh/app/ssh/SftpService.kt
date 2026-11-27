package com.gravityssh.app.ssh

import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.SFTPListResult
import com.gravityssh.app.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream

class SftpService(
    private val sessionManager: SshSessionManager
) {
    private val archiveOps = SftpArchiveOps(sessionManager)
    private val transferOps = SftpTransferOps(sessionManager)

    suspend fun list(sessionId: String, path: String, showHidden: Boolean = false): SFTPListResult =
        withContext(Dispatchers.IO) {
            sessionManager.withSftp(sessionId) { sftp ->
                val resolved = SshPathUtils.resolvePath(sftp, path)
                val rawList = SshPathUtils.filterLsItems(sftp.ls(resolved), showHidden)
                val symlinkDirs = resolveSymlinkDirs(sessionId, sftp, rawList)
                val entries = SshPathUtils.mapAndSortEntries(
                    rawList = rawList,
                    showHidden = showHidden,
                    isDirOverride = { info -> info.isDirectory || symlinkDirs[info.path] == true },
                    includeModTimeIso = true
                )
                val parent = SshPathUtils.parentDir(resolved)
                SFTPListResult(path = resolved, parent = parent, entries = entries)
            }
        }

    private suspend fun resolveSymlinkDirs(
        sessionId: String,
        primarySftp: SFTPClient,
        rawList: List<RemoteResourceInfo>
    ): Map<String, Boolean> {
        if (rawList.size > LARGE_DIR_SKIP_SYMLINK_THRESHOLD) return emptyMap()
        val symlinks = rawList.asSequence()
            .filter { !it.isDirectory && it.attributes.type == FileMode.Type.SYMLINK }
            .take(MAX_SYMLINK_STATS)
            .toList()
        if (symlinks.isEmpty()) return emptyMap()
        if (!shouldParallelizeSymlinkStats(symlinks.size)) {
            return statChunk(primarySftp, symlinks)
        }

        val workerCount = minOf(MAX_SYMLINK_STAT_WORKERS, symlinks.size)
        val chunkSize = (symlinks.size + workerCount - 1) / workerCount
        val chunks = symlinks.chunked(chunkSize)
        val sshClient = runCatching { sessionManager.requireClient(sessionId) }.getOrNull()

        val resolvedMap = HashMap<String, Boolean>(symlinks.size)
        coroutineScope {
            val partials = chunks.mapIndexed { idx, chunk ->
                async(Dispatchers.IO) {
                    if (idx == 0 || sshClient == null) {
                        if (idx == 0) statChunk(primarySftp, chunk) else emptyMap()
                    } else {
                        val extraSftp = runCatching { sshClient.newSFTPClient() }.getOrNull()
                        if (extraSftp != null) {
                            extraSftp.use { statChunk(it, chunk) }
                        } else {
                            emptyMap()
                        }
                    }
                }
            }.awaitAll()
            for (part in partials) {
                resolvedMap.putAll(part)
            }
        }
        // Fallback on primarySftp for any chunk whose extra channel failed to open
        for (sym in symlinks) {
            if (!resolvedMap.containsKey(sym.path)) {
                resolvedMap[sym.path] = runCatching {
                    primarySftp.stat(sym.path).type == FileMode.Type.DIRECTORY
                }.getOrDefault(false)
            }
        }
        return resolvedMap
    }

    private fun statChunk(sftp: SFTPClient, chunk: List<RemoteResourceInfo>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>(chunk.size)
        for (info in chunk) {
            out[info.path] = runCatching { sftp.stat(info.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false)
        }
        return out
    }

    suspend fun mkdir(sessionId: String, path: String) = withContext(Dispatchers.IO) {
        val clean = SshPathUtils.normalizePosixPath(path)
        sessionManager.withSftp(sessionId) { it.mkdirs(clean) }
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(clean))
        sessionManager.invalidateDirCache(sessionId, clean)
    }

    suspend fun rename(sessionId: String, oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val src = SshPathUtils.normalizePosixPath(oldPath)
        val dst = SshPathUtils.normalizePosixPath(newPath)
        require(src != "/" && dst != "/") { "不能重命名根目录" }
        sessionManager.withSftp(sessionId) { it.rename(src, dst) }
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(src))
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(dst))
        sessionManager.invalidateDirCache(sessionId, src)
    }

    suspend fun delete(sessionId: String, path: String) = withContext(Dispatchers.IO) {
        val target = SshPathUtils.normalizePosixPath(path)
        require(target != "/" && target.isNotEmpty()) { "拒绝删除根目录" }
        SftpDeleteOps.deleteFastOrRecursive(sessionManager, sessionId, target)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(target))
        sessionManager.invalidateDirCache(sessionId, target)
    }

    suspend fun readTextFile(sessionId: String, path: String): RemoteFileContent =
        transferOps.readTextFile(sessionId, path)

    suspend fun writeTextFile(sessionId: String, path: String, content: String) {
        transferOps.writeTextFile(sessionId, path, content)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(path))
    }

    suspend fun uploadStream(
        sessionId: String,
        remotePath: String,
        input: InputStream,
        totalBytes: Long = -1L,
        onProgress: (TransferProgress) -> Unit = {}
    ) {
        try {
            transferOps.uploadStream(sessionId, remotePath, input, totalBytes, onProgress)
        } finally {
            sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(remotePath))
        }
    }

    suspend fun downloadStream(
        sessionId: String,
        remotePath: String,
        output: OutputStream,
        onProgress: (TransferProgress) -> Unit = {}
    ) = transferOps.downloadStream(sessionId, remotePath, output, onProgress)

    suspend fun compress(sessionId: String, paths: List<String>, archivePath: String) {
        archiveOps.compress(sessionId, paths, archivePath)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(archivePath))
    }

    suspend fun extract(sessionId: String, archivePath: String, destDir: String) {
        archiveOps.extract(sessionId, archivePath, destDir)
        sessionManager.invalidateDirCache(sessionId, destDir)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(destDir))
    }

    suspend fun alignPermissions(sessionId: String, remotePath: String): String {
        val res = archiveOps.alignPermissions(sessionId, remotePath)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(remotePath))
        return res
    }

    companion object {
        const val MAX_EDITOR_BYTES = 1024 * 1024
        internal const val PARALLEL_SYMLINK_STAT_THRESHOLD = 8
        private const val MAX_SYMLINK_STATS = 32
        private const val MAX_SYMLINK_STAT_WORKERS = 4
        private const val LARGE_DIR_SKIP_SYMLINK_THRESHOLD = 256

        internal fun shouldParallelizeSymlinkStats(symlinkCount: Int): Boolean =
            symlinkCount >= PARALLEL_SYMLINK_STAT_THRESHOLD
    }
}
