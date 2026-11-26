package com.gravityssh.app.ssh

import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.SFTPFileEntry
import com.gravityssh.app.model.SFTPListResult
import com.gravityssh.app.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

class SftpService(
    private val sessionManager: SshSessionManager
) {
    private val archiveOps = SftpArchiveOps(sessionManager)
    private val transferOps = SftpTransferOps(sessionManager)

    suspend fun list(sessionId: String, path: String, showHidden: Boolean = false): SFTPListResult =
        withContext(Dispatchers.IO) {
            val sftp = sessionManager.requireSftp(sessionId)
            val resolved = SftpDeleteOps.resolvePath(sftp, path)
            val entries = sftp.ls(resolved)
                .filter { it.name != "." && it.name != ".." }
                .filter { showHidden || !it.name.startsWith(".") }
                .map { info ->
                    val attrs = info.attributes
                    val isSymlink = attrs.type == FileMode.Type.SYMLINK
                    val isDir = info.isDirectory || (isSymlink && runCatching {
                        sftp.stat(info.path).type == FileMode.Type.DIRECTORY
                    }.getOrDefault(false))
                    SFTPFileEntry(
                        name = info.name,
                        path = info.path,
                        isDir = isDir,
                        isSymlink = isSymlink,
                        size = attrs.size,
                        mode = SftpDeleteOps.formatPermissions(attrs.mode.mask),
                        modTime = Instant.ofEpochSecond(attrs.mtime).toString(),
                        modTimeEpochSec = attrs.mtime
                    )
                }
                .sortedWith(compareByDescending<SFTPFileEntry> { it.isDir }.thenBy { it.name.lowercase() })

            val parent = if (resolved == "/") "/" else resolved.substringBeforeLast('/').ifEmpty { "/" }
            SFTPListResult(path = resolved, parent = parent, entries = entries)
        }

    suspend fun mkdir(sessionId: String, path: String) = withContext(Dispatchers.IO) {
        sessionManager.requireSftp(sessionId).mkdirs(cleanPath(path))
    }

    suspend fun rename(sessionId: String, oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val src = cleanPath(oldPath); val dst = cleanPath(newPath)
        require(src != "/" && dst != "/") { "不能重命名根目录" }
        sessionManager.requireSftp(sessionId).rename(src, dst)
    }

    suspend fun delete(sessionId: String, path: String) = withContext(Dispatchers.IO) {
        val target = cleanPath(path)
        require(target != "/" && target.isNotEmpty()) { "拒绝删除根目录" }
        SftpDeleteOps.deleteFastOrRecursive(sessionManager, sessionId, target)
    }

    suspend fun readTextFile(sessionId: String, path: String): RemoteFileContent =
        transferOps.readTextFile(sessionId, path)

    suspend fun writeTextFile(sessionId: String, path: String, content: String) =
        transferOps.writeTextFile(sessionId, path, content)

    suspend fun uploadStream(
        sessionId: String,
        remotePath: String,
        input: InputStream,
        totalBytes: Long = -1L,
        onProgress: (TransferProgress) -> Unit = {}
    ) = transferOps.uploadStream(sessionId, remotePath, input, totalBytes, onProgress)

    suspend fun downloadStream(
        sessionId: String,
        remotePath: String,
        output: OutputStream,
        onProgress: (TransferProgress) -> Unit = {}
    ) = transferOps.downloadStream(sessionId, remotePath, output, onProgress)

    suspend fun compress(sessionId: String, paths: List<String>, archivePath: String) =
        archiveOps.compress(sessionId, paths, archivePath)

    suspend fun extract(sessionId: String, archivePath: String, destDir: String) =
        archiveOps.extract(sessionId, archivePath, destDir)

    suspend fun alignPermissions(sessionId: String, remotePath: String): String =
        archiveOps.alignPermissions(sessionId, remotePath)

    companion object {
        const val MAX_EDITOR_BYTES = 1024 * 1024
        fun cleanPath(path: String): String = ShellPathTracker.normalizePosixPath(path)
        fun shellQuote(s: String): String = "'${s.replace("'", "'\"'\"'")}'"
    }
}
