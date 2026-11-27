package com.gravityssh.app.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.ByteArrayOutputStream
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
        client.startSession().use { sess ->
            val exec = sess.exec(cmd)
            try {
                coroutineScope {
                    val outDeferred = async(Dispatchers.IO) {
                        readStreamBounded(exec.inputStream, maxOutputBytes)
                    }
                    val errDeferred = async(Dispatchers.IO) {
                        readStreamBounded(exec.errorStream, maxOutputBytes)
                    }
                    try {
                        runInterruptible(Dispatchers.IO) {
                            exec.join(timeoutSec, TimeUnit.SECONDS)
                        }
                        val stdout = outDeferred.await()
                        val stderr = errDeferred.await()
                        ExecResult(exec.exitStatus ?: -1, stdout, stderr)
                    } catch (t: Throwable) {
                        // Close channel & session immediately so blocked stream reads unblock
                        runCatching { exec.close() }
                        runCatching { sess.close() }
                        throw t
                    }
                }
            } catch (e: CancellationException) {
                runCatching { exec.close() }
                throw e
            } finally {
                runCatching { exec.close() }
            }
        }
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
