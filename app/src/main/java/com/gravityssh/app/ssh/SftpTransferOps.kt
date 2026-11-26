package com.gravityssh.app.ssh

import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.OpenMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.UUID

internal class SftpTransferOps(
    private val sessionManager: SshSessionManager
) {
    suspend fun readTextFile(sessionId: String, path: String): RemoteFileContent = withContext(Dispatchers.IO) {
        val sftp = sessionManager.requireSftp(sessionId)
        val clean = SftpService.cleanPath(path)
        val attrs = sftp.stat(clean)
        if (attrs.size > SftpService.MAX_EDITOR_BYTES) throw IOException("文件超过 1 MB，无法在线编辑")
        val baos = ByteArrayOutputStream(attrs.size.toInt().coerceAtLeast(256))
        sftp.open(clean, EnumSet.of(OpenMode.READ)).use { rf ->
            val buf = ByteArray(16384)
            var offset = 0L
            while (true) {
                val n = rf.read(offset, buf, 0, buf.size)
                if (n <= 0) break
                baos.write(buf, 0, n)
                offset += n
            }
        }
        val bytes = baos.toByteArray()
        RemoteFileContent(clean, bytes.toString(Charsets.UTF_8), bytes.size.toLong())
    }

    suspend fun writeTextFile(sessionId: String, path: String, content: String) = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > SftpService.MAX_EDITOR_BYTES) throw IOException("内容超过 1 MB 限制")
        val sftp = sessionManager.requireSftp(sessionId)
        val clean = SftpService.cleanPath(path)
        sftp.open(clean, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            var off = 0
            while (off < bytes.size) {
                val chunk = minOf(32768, bytes.size - off)
                rf.write(off.toLong(), bytes, off, chunk)
                off += chunk
            }
        }
    }

    suspend fun uploadStream(
        sessionId: String,
        remotePath: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (TransferProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val sftp = sessionManager.requireSftp(sessionId)
        val clean = SftpService.cleanPath(remotePath)
        val tid = UUID.randomUUID().toString()
        val fileName = clean.substringAfterLast('/')
        var sent = 0L
        sftp.open(clean, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            val buf = ByteArray(32768)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    rf.write(sent, buf, 0, n)
                    sent += n
                    val pct = if (totalBytes > 0) ((sent * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                    onProgress(TransferProgress(tid, sessionId, "upload", fileName, clean, sent, totalBytes, pct, "running"))
                }
            }
        }
        onProgress(TransferProgress(tid, sessionId, "upload", fileName, clean, sent, sent, 100, "done"))
    }

    suspend fun downloadStream(
        sessionId: String,
        remotePath: String,
        output: OutputStream,
        onProgress: (TransferProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val sftp = sessionManager.requireSftp(sessionId)
        val clean = SftpService.cleanPath(remotePath)
        val total = runCatching { sftp.stat(clean).size }.getOrDefault(-1L)
        val tid = UUID.randomUUID().toString()
        val fileName = clean.substringAfterLast('/')
        var readTotal = 0L
        sftp.open(clean, EnumSet.of(OpenMode.READ)).use { rf ->
            val buf = ByteArray(32768)
            while (true) {
                val n = rf.read(readTotal, buf, 0, buf.size)
                if (n <= 0) break
                output.write(buf, 0, n)
                readTotal += n
                val pct = if (total > 0) ((readTotal * 100) / total).toInt().coerceIn(0, 100) else 0
                onProgress(TransferProgress(tid, sessionId, "download", fileName, clean, readTotal, total, pct, "running"))
            }
        }
        output.flush()
        onProgress(TransferProgress(tid, sessionId, "download", fileName, clean, readTotal, total, 100, "done"))
    }
}
