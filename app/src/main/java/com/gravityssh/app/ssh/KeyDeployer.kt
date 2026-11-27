package com.gravityssh.app.ssh

import com.gravityssh.app.model.KeyDeployResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Base64

class KeyDeployer(
    private val connector: SshConnector
) {
    suspend fun deployToServers(
        key: SSHKeyRecord,
        targets: List<ServerConfig>,
        allServers: List<ServerConfig>,
        allKeys: List<SSHKeyRecord>
    ): List<KeyDeployResult> = withContext(Dispatchers.IO) {
        val pubLine = key.publicKey.trim()
        if (pubLine.isEmpty()) {
            return@withContext targets.map {
                KeyDeployResult(it.id, it.name, false, "公钥内容为空")
            }
        }
        if (pubLine.contains('\n') || pubLine.contains('\r')) {
            return@withContext targets.map {
                KeyDeployResult(it.id, it.name, false, "公钥格式无效（包含换行符）")
            }
        }
        val b64 = Base64.getEncoder().encodeToString(pubLine.toByteArray(Charsets.UTF_8))
        val cmd = """
            sh -c '
              set -e
              umask 077
              mkdir -p ~/.ssh
              chmod 700 ~/.ssh
              PUB=$(printf "%s" "$b64" | base64 -d)
              [ -n "${'$'}PUB" ] || { echo "base64 解码公钥失败" >&2; exit 1; }
              touch ~/.ssh/authorized_keys
              chmod 600 ~/.ssh/authorized_keys
              grep -Fqx "${'$'}PUB" ~/.ssh/authorized_keys || printf "%s\n" "${'$'}PUB" >> ~/.ssh/authorized_keys
            '
        """.trimIndent()

        val semaphore = Semaphore(MAX_CONCURRENT_DEPLOYS)
        coroutineScope {
            targets.map { server ->
                async {
                    semaphore.withPermit {
                        try {
                            connector.connect(server, allServers, allKeys).use { conn ->
                                val res = SshExecHelper.execCommand(conn.client, cmd, timeoutSec = 15L)
                                if (res.exitCode != 0) {
                                    val err = res.stderr.trim().ifEmpty { res.stdout.trim() }
                                    throw IllegalStateException(err.ifEmpty { "退出码 ${res.exitCode}" })
                                }
                            }
                            KeyDeployResult(server.id, server.name, true, "公钥已成功部署至 ~/.ssh/authorized_keys")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            KeyDeployResult(server.id, server.name, false, e.message ?: "部署失败")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    companion object {
        private const val MAX_CONCURRENT_DEPLOYS = 4
    }
}
