package com.gravityssh.app.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SftpArchiveOps(
    private val sessionManager: SshSessionManager
) {
    suspend fun compress(sessionId: String, paths: List<String>, archivePath: String) =
        withContext(Dispatchers.IO) {
            require(paths.isNotEmpty()) { "请选择需要打包的文件" }
            val cleanArchive = SftpService.cleanPath(archivePath)
            val parentDir = SftpService.cleanPath(paths.first()).substringBeforeLast('/').ifEmpty { "/" }
            val names = paths.map { SftpService.cleanPath(it).substringAfterLast('/') }.filter { it.isNotEmpty() }
            require(names.isNotEmpty()) { "无效的打包路径" }
            val joinedNames = names.joinToString(" ") { SftpService.shellQuote(it) }
            val qParent = SftpService.shellQuote(parentDir)
            val qArchive = SftpService.shellQuote(cleanArchive)
            val cmd = if (cleanArchive.endsWith(".zip", ignoreCase = true)) {
                "cd $qParent && zip -rq $qArchive $joinedNames"
            } else {
                "cd $qParent && tar -czf $qArchive -- $joinedNames"
            }
            runRemoteCommand(sessionId, cmd, 120)
        }

    suspend fun extract(sessionId: String, archivePath: String, destDir: String) =
        withContext(Dispatchers.IO) {
            val cleanArchive = SftpService.cleanPath(archivePath)
            val cleanDest = SftpService.cleanPath(destDir)
            val qArc = SftpService.shellQuote(cleanArchive)
            val qDest = SftpService.shellQuote(cleanDest)
            val lower = cleanArchive.lowercase()
            val cmd = when {
                lower.endsWith(".zip") -> "mkdir -p $qDest && unzip -o $qArc -d $qDest"
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "mkdir -p $qDest && tar -xzf $qArc -C $qDest"
                lower.endsWith(".tar.bz2") -> "mkdir -p $qDest && tar -xjf $qArc -C $qDest"
                lower.endsWith(".tar.xz") -> "mkdir -p $qDest && tar -xJf $qArc -C $qDest"
                lower.endsWith(".tar") -> "mkdir -p $qDest && tar -xf $qArc -C $qDest"
                else -> throw IOException("不支持的压缩包格式: ${cleanArchive.substringAfterLast('/')}")
            }
            runRemoteCommand(sessionId, cmd, 120)
        }

    suspend fun alignPermissions(sessionId: String, remotePath: String): String =
        withContext(Dispatchers.IO) {
            val sftp = sessionManager.requireSftp(sessionId)
            val clean = SftpService.cleanPath(remotePath)
            require(clean != "/") { "不能修改根目录权限" }
            val attrs = sftp.lstat(clean)
            val isDir = attrs.type == FileMode.Type.DIRECTORY
            val parent = clean.substringBeforeLast('/').ifEmpty { "/" }
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

    private fun runRemoteCommand(sessionId: String, cmd: String, timeoutSec: Long) {
        val client = sessionManager.requireClient(sessionId)
        client.startSession().use { sess ->
            val exec = sess.exec(cmd)
            exec.join(timeoutSec, TimeUnit.SECONDS)
            val code = exec.exitStatus ?: -1
            if (code != 0) {
                val err = exec.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
                throw IOException(err.ifEmpty { "远端命令执行失败 (exit=$code)" })
            }
        }
    }
}
