package com.gravityssh.app.ssh

import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.SFTPListResult
import com.gravityssh.app.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import java.io.InputStream
import java.io.OutputStream

class SftpService(
    private val sessionManager: SshSessionManager
) {
    private val archiveOps = SftpArchiveOps(sessionManager)
    private val transferOps = SftpTransferOps(sessionManager)

    suspend fun list(sessionId: String, path: String, showHidden: Boolean = false): SFTPListResult =
        withContext(Dispatchers.IO) {
            val sftp = sessionManager.requireSftp(sessionId)
            val resolved = SshPathUtils.resolvePath(sftp, path)
            val rawList = sftp.ls(resolved)
                .filter { it.name != "." && it.name != ".." }
                .filter { showHidden || !it.name.startsWith(".") }
            val allowSymlinkStat = rawList.size <= LARGE_DIR_SKIP_SYMLINK_THRESHOLD
            var symlinkStatCount = 0
            val entries = rawList.map { info ->
                val isSymlink = info.attributes.type == FileMode.Type.SYMLINK
                val isDir = info.isDirectory || (
                    isSymlink && allowSymlinkStat && symlinkStatCount < MAX_SYMLINK_STATS && run {
                        symlinkStatCount++
                        runCatching { sftp.stat(info.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false)
                    }
                )
                SshPathUtils.toSftpFileEntry(info, isDirOverride = isDir, includeModTimeIso = true)
            }.sortedWith(SshPathUtils.ENTRY_COMPARATOR)

            val parent = SshPathUtils.parentDir(resolved)
            SFTPListResult(path = resolved, parent = parent, entries = entries)
        }

    suspend fun mkdir(sessionId: String, path: String) = withContext(Dispatchers.IO) {
        val clean = cleanPath(path)
        sessionManager.requireSftp(sessionId).mkdirs(clean)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(clean))
        sessionManager.invalidateDirCache(sessionId, clean)
    }

    suspend fun rename(sessionId: String, oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val src = cleanPath(oldPath); val dst = cleanPath(newPath)
        require(src != "/" && dst != "/") { "不能重命名根目录" }
        sessionManager.requireSftp(sessionId).rename(src, dst)
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(src))
        sessionManager.invalidateDirCache(sessionId, SshPathUtils.parentDir(dst))
        sessionManager.invalidateDirCache(sessionId, src)
    }

    suspend fun delete(sessionId: String, path: String) = withContext(Dispatchers.IO) {
        val target = cleanPath(path)
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
        private const val MAX_SYMLINK_STATS = 32
        private const val LARGE_DIR_SKIP_SYMLINK_THRESHOLD = 256
        fun cleanPath(path: String): String = SshPathUtils.normalizePosixPath(path)
        fun shellQuote(s: String): String = SshPathUtils.shellQuote(s)
    }
}
