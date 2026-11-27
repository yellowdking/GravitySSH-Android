package com.gravityssh.app.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import java.io.IOException

internal class SftpArchiveOps(
    private val sessionManager: SshSessionManager
) {
    suspend fun compress(sessionId: String, paths: List<String>, archivePath: String) =
        withContext(Dispatchers.IO) {
            require(paths.isNotEmpty()) { "请选择需要打包的文件" }
            val cleanArchive = SshPathUtils.normalizePosixPath(archivePath)
            val parentDir = SshPathUtils.parentDir(paths.first())
            val names = paths.map { SshPathUtils.normalizePosixPath(it).substringAfterLast('/') }.filter { it.isNotEmpty() }
            require(names.isNotEmpty()) { "无效的打包路径" }
            val joinedNames = names.joinToString(" ") { SshPathUtils.shellQuote(it) }
            val qParent = SshPathUtils.shellQuote(parentDir)
            val qArchive = SshPathUtils.shellQuote(cleanArchive)
            val cmd = if (cleanArchive.endsWith(".zip", ignoreCase = true)) {
                "cd $qParent && zip -rq $qArchive -- $joinedNames"
            } else {
                "cd $qParent && tar -czf $qArchive -- $joinedNames"
            }
            runRemoteCommand(sessionId, cmd, 120L)
        }

    suspend fun extract(sessionId: String, archivePath: String, destDir: String) =
        withContext(Dispatchers.IO) {
            val cleanArchive = SshPathUtils.normalizePosixPath(archivePath)
            val cleanDest = SshPathUtils.normalizePosixPath(destDir)
            val qArc = SshPathUtils.shellQuote(cleanArchive)
            val qDest = SshPathUtils.shellQuote(cleanDest)
            val lower = cleanArchive.lowercase()
            val cmd = when {
                lower.endsWith(".zip") -> "mkdir -p $qDest && unzip -oq $qArc -d $qDest"
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "mkdir -p $qDest && tar -xzf $qArc -C $qDest"
                lower.endsWith(".tar.bz2") -> "mkdir -p $qDest && tar -xjf $qArc -C $qDest"
                lower.endsWith(".tar.xz") -> "mkdir -p $qDest && tar -xJf $qArc -C $qDest"
                lower.endsWith(".tar") -> "mkdir -p $qDest && tar -xf $qArc -C $qDest"
                else -> throw IOException("不支持的压缩包格式: ${cleanArchive.substringAfterLast('/')}")
            }
            runRemoteCommand(sessionId, cmd, 120L)
        }

    suspend fun alignPermissions(sessionId: String, remotePath: String): String =
        withContext(Dispatchers.IO) {
            val sftp = sessionManager.requireSftp(sessionId)
            val clean = SshPathUtils.normalizePosixPath(remotePath)
            require(clean != "/") { "不能修改根目录权限" }
            val attrs = sftp.lstat(clean)
            val isDir = attrs.type == FileMode.Type.DIRECTORY
            val parent = SshPathUtils.parentDir(clean)
            val siblings = runCatching { sftp.ls(parent) }.getOrDefault(emptyList())
            val counts = HashMap<Int, Int>()
            for (item in siblings) {
                if (item.name == "." || item.name == ".." || item.path == clean) continue
                val sameKind = (item.attributes.type == FileMode.Type.DIRECTORY) == isDir
                if (!sameKind) continue
                val mode = item.attributes.mode.mask and 0x1FF
                if (mode != 0) counts[mode] = (counts[mode] ?: 0) + 1
            }
            val targetMode = counts.maxByOrNull { it.value }?.key
                ?: if (isDir) 0x1ED else 0x1A4 // 0755 or 0644
            sftp.chmod(clean, targetMode)
            String.format("%04o", targetMode)
        }

    private suspend fun runRemoteCommand(sessionId: String, cmd: String, timeoutSec: Long) {
        val client = sessionManager.requireClient(sessionId)
        val res = SshExecHelper.execCommand(client, cmd, timeoutSec = timeoutSec)
        if (res.exitCode != 0) {
            val err = res.stderr.trim().ifEmpty { res.stdout.trim() }
            throw IOException(err.ifEmpty { "远端命令执行失败 (exit=${res.exitCode})" })
        }
    }

    companion object {
        val SUPPORTED_ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tgz", ".tar.bz2", ".tar.xz", ".zip", ".tar")

        fun isSupportedArchiveName(name: String): Boolean {
            val lower = name.lowercase()
            return SUPPORTED_ARCHIVE_SUFFIXES.any { lower.endsWith(it) }
        }
    }
}
