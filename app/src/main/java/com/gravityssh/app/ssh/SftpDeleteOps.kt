package com.gravityssh.app.ssh

import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import java.util.concurrent.TimeUnit

internal object SftpDeleteOps {

    fun deleteFastOrRecursive(sessionManager: SshSessionManager, sessionId: String, target: String) {
        val sftp = sessionManager.requireSftp(sessionId)
        val fastOk = runCatching { deleteViaSshFastPath(sessionManager, sessionId, target) }.getOrDefault(false)
        if (fastOk && runCatching { sftp.lstat(target); true }.getOrDefault(false).not()) return
        deleteRecursiveSftp(sftp, target)
    }

    private fun deleteViaSshFastPath(sessionManager: SshSessionManager, sessionId: String, target: String): Boolean {
        val client = sessionManager.requireClient(sessionId)
        val q = SftpService.shellQuote(target)
        val cmd = "sh -c 'if [ -e \"${'$'}1\" ] || [ -L \"${'$'}1\" ]; then rm -rf -- \"${'$'}1\" && [ ! -e \"${'$'}1\" ] && [ ! -L \"${'$'}1\" ]; else exit 44; fi' _ $q"
        client.startSession().use { sess ->
            val exec = sess.exec(cmd)
            exec.join(20, TimeUnit.SECONDS)
            return exec.exitStatus == 0
        }
    }

    private fun deleteRecursiveSftp(sftp: SFTPClient, target: String) {
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

    fun resolvePath(sftp: SFTPClient, raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t == "~") return runCatching { sftp.canonicalize(".") }.getOrDefault("/")
        if (t.startsWith("~/")) {
            val home = runCatching { sftp.canonicalize(".") }.getOrDefault("/")
            return SftpService.cleanPath("${home.trimEnd('/')}/${t.removePrefix("~/")}")
        }
        return SftpService.cleanPath(t)
    }

    fun formatPermissions(mask: Int): String {
        val perm = mask and 0x1FF
        val chars = charArrayOf('r', 'w', 'x')
        val sb = StringBuilder(9)
        for (i in 8 downTo 0) sb.append(if ((perm and (1 shl i)) != 0) chars[(8 - i) % 3] else '-')
        return sb.toString()
    }
}
