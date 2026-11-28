package com.gravityssh.app.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

internal object SshExecHelper {

    const val DEFAULT_MAX_OUTPUT_BYTES = 512 * 1024

    suspend fun execCommand(
        client: SSHClient,
        cmd: String,
        timeoutSec: Long = 15L,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ExecResult = withContext(Dispatchers.IO) {
        val closeLock = Any()
        var closed = false
        var activeSess: Session? = null
        var activeExec: Session.Command? = null

        fun closeActive() {
            val execToClose: Session.Command?
            val sessToClose: Session?
            synchronized(closeLock) {
                closed = true
                execToClose = activeExec
                sessToClose = activeSess
                activeExec = null
                activeSess = null
            }
            runCatching { execToClose?.close() }
            runCatching { sessToClose?.close() }
        }

        fun registerSession(sess: Session) {
            val closeNow = synchronized(closeLock) {
                if (closed) true else { activeSess = sess; false }
            }
            if (closeNow) {
                runCatching { sess.close() }
                throw CancellationException("远端命令执行已取消")
            }
        }

        fun registerExec(exec: Session.Command) {
            val closeNow = synchronized(closeLock) {
                if (closed) true else { activeExec = exec; false }
            }
            if (closeNow) {
                runCatching { exec.close() }
                throw CancellationException("远端命令执行已取消")
            }
        }

        val effectiveTimeoutSec = timeoutSec.coerceAtLeast(1L)
        runBoundedMs(
            timeoutMs = effectiveTimeoutSec * 1000L,
            timeoutMessage = "远端命令执行超时 (${effectiveTimeoutSec}s)",
            onCancelOrTimeout = ::closeActive
        ) { deadlineNs ->
            val sess = runInterruptible(Dispatchers.IO) { client.startSession() }
            registerSession(sess)
            val exec = runInterruptible(Dispatchers.IO) { sess.exec(cmd) }
            registerExec(exec)

            val outDeferred = async(Dispatchers.IO) {
                runInterruptible(Dispatchers.IO) {
                    readStreamBounded(exec.inputStream, maxOutputBytes)
                }
            }
            val errDeferred = async(Dispatchers.IO) {
                runInterruptible(Dispatchers.IO) {
                    readStreamBounded(exec.errorStream, maxOutputBytes)
                }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    val remMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                    exec.join(remMs, TimeUnit.MILLISECONDS)
                }
                val stdout = outDeferred.await()
                val stderr = errDeferred.await()
                ExecResult(exec.exitStatus ?: -1, stdout, stderr)
            } catch (t: Throwable) {
                closeActive()
                throw t
            }
        }
    }

    internal suspend fun <T : Any> runBoundedMs(
        timeoutMs: Long,
        timeoutMessage: String,
        onCancelOrTimeout: () -> Unit,
        block: suspend CoroutineScope.(deadlineNs: Long) -> T
    ): T {
        val clampedMs = timeoutMs.coerceAtLeast(1L)
        val deadlineNs = System.nanoTime() + clampedMs * 1_000_000L
        val result = withTimeoutOrNull(clampedMs) {
            val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) onCancelOrTimeout()
            }
            try {
                coroutineScope {
                    block(deadlineNs)
                }
            } finally {
                handle?.dispose()
                onCancelOrTimeout()
            }
        }
        if (result == null) {
            onCancelOrTimeout()
            throw IOException(timeoutMessage)
        }
        return result
    }

    private fun readStreamBounded(input: InputStream, maxBytes: Int): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                val remaining = maxBytes - total
                if (remaining > 0) {
                    val toWrite = minOf(n, remaining)
                    baos.write(buf, 0, toWrite)
                    total += toWrite
                }
            }
        }
        return baos.toByteArray().toString(Charsets.UTF_8)
    }
}
