package com.gravityssh.app.ssh

import com.gravityssh.app.model.RemoteFileContent
import com.gravityssh.app.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        val clean = SshPathUtils.normalizePosixPath(path)
        val attrs = sftp.stat(clean)
        if (attrs.size > SftpService.MAX_EDITOR_BYTES) throw IOException("文件超过 1 MB，无法在线编辑")
        val initialCap = attrs.size.toInt().coerceIn(256, SftpService.MAX_EDITOR_BYTES)
        val baos = ByteArrayOutputStream(initialCap)
        sftp.open(clean, EnumSet.of(OpenMode.READ)).use { rf ->
            rf.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED).use { input ->
                copyStreamWithProgress(
                    input = input,
                    output = baos,
                    maxBytes = SftpService.MAX_EDITOR_BYTES.toLong(),
                    limitErrorMsg = "文件超过 1 MB，无法在线编辑"
                )
            }
        }
        val bytes = baos.toByteArray()
        RemoteFileContent(clean, bytes.toString(Charsets.UTF_8), bytes.size.toLong())
    }

    suspend fun writeTextFile(sessionId: String, path: String, content: String) = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > SftpService.MAX_EDITOR_BYTES) throw IOException("内容超过 1 MB 限制")
        val sftp = sessionManager.requireSftp(sessionId)
        val clean = SshPathUtils.normalizePosixPath(path)
        sftp.open(clean, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            rf.RemoteFileOutputStream(0L, MAX_UNCONFIRMED).use { out ->
                var off = 0
                while (off < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val chunk = minOf(IO_BUFFER_SIZE, bytes.size - off)
                    out.write(bytes, off, chunk)
                    off += chunk
                }
                out.flush()
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
        val clean = SshPathUtils.normalizePosixPath(remotePath)
        val emitter = ProgressEmitter(sessionId, "upload", clean, totalBytes, onProgress)
        val sent = sftp.open(clean, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            rf.RemoteFileOutputStream(0L, MAX_UNCONFIRMED).use { out ->
                val written = copyStreamWithProgress(input, out) { emitter.maybeEmit(it) }
                out.flush()
                written
            }
        }
        emitter.emitDone(sent, totalOverride = sent)
    }

    suspend fun downloadStream(
        sessionId: String,
        remotePath: String,
        output: OutputStream,
        onProgress: (TransferProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val sftp = sessionManager.requireSftp(sessionId)
        val clean = SshPathUtils.normalizePosixPath(remotePath)
        val total = runCatching { sftp.stat(clean).size }.getOrDefault(-1L)
        val emitter = ProgressEmitter(sessionId, "download", clean, total, onProgress)
        val readTotal = sftp.open(clean, EnumSet.of(OpenMode.READ)).use { rf ->
            rf.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED).use { input ->
                copyStreamWithProgress(input, output) { emitter.maybeEmit(it) }
            }
        }
        output.flush()
        emitter.emitDone(readTotal, totalOverride = total)
    }

    private suspend fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = -1L,
        limitErrorMsg: String = "",
        onTransferred: ((Long) -> Unit)? = null
    ): Long {
        val buf = ByteArray(IO_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                total += n
                if (maxBytes > 0L && total > maxBytes) {
                    throw IOException(limitErrorMsg)
                }
                output.write(buf, 0, n)
                onTransferred?.invoke(total)
            }
        }
        return total
    }

    private class ProgressEmitter(
        private val sessionId: String,
        private val direction: String,
        private val remotePath: String,
        private val totalBytes: Long,
        private val onProgress: (TransferProgress) -> Unit
    ) {
        private val tid = UUID.randomUUID().toString()
        private val fileName = remotePath.substringAfterLast('/')
        private var lastEmitMs = 0L
        private var lastEmitPct = -1

        fun maybeEmit(transferred: Long) {
            val pct = if (totalBytes > 0) ((transferred * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= PROGRESS_MIN_INTERVAL_MS ||
                (totalBytes > 0 && pct - lastEmitPct >= PROGRESS_MIN_PCT_DELTA)
            ) {
                lastEmitMs = now
                lastEmitPct = pct
                onProgress(TransferProgress(tid, sessionId, direction, fileName, remotePath, transferred, totalBytes, pct, "running"))
            }
        }

        fun emitDone(transferred: Long, totalOverride: Long) {
            onProgress(TransferProgress(tid, sessionId, direction, fileName, remotePath, transferred, totalOverride, 100, "done"))
        }
    }

    companion object {
        private const val MAX_UNCONFIRMED = 16
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_MIN_INTERVAL_MS = 100L
        private const val PROGRESS_MIN_PCT_DELTA = 2
    }
}
