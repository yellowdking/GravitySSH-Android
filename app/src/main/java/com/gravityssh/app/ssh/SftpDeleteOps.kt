package com.gravityssh.app.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient

internal object SftpDeleteOps {

    suspend fun deleteFastOrRecursive(sessionManager: SshSessionManager, sessionId: String, target: String) {
        val fastOk = try {
            deleteViaSshFastPath(sessionManager, sessionId, target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (fastOk) return
        val sftp = sessionManager.requireSftp(sessionId)
        deleteRecursiveSftp(sftp, target)
    }

    private suspend fun deleteViaSshFastPath(sessionManager: SshSessionManager, sessionId: String, target: String): Boolean {
        val client = sessionManager.requireClient(sessionId)
        val q = SshPathUtils.shellQuote(target)
        val cmd = "sh -c 'if [ -e \"${'$'}1\" ] || [ -L \"${'$'}1\" ]; then rm -rf -- \"${'$'}1\" && [ ! -e \"${'$'}1\" ] && [ ! -L \"${'$'}1\" ]; else exit 44; fi' _ $q"
        val res = SshExecHelper.execCommand(client, cmd, timeoutSec = 20L)
        return res.exitCode == 0
    }

    private suspend fun deleteRecursiveSftp(sftp: SFTPClient, target: String) {
        currentCoroutineContext().ensureActive()
        val attrs = runCatching { sftp.lstat(target) }.getOrNull() ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            runCatching { sftp.chmod(target, 0x1ED) }
            for (child in runCatching { sftp.ls(target) }.getOrDefault(emptyList())) {
                if (child.name != "." && child.name != "..") deleteRecursiveSftp(sftp, child.path)
            }
            sftp.rmdir(target)
        } else {
            sftp.rm(target)
        }
    }
}
