package com.gravityssh.app.ssh

import com.gravityssh.app.model.KeyDeployResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.concurrent.TimeUnit

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
        val b64 = Base64.getEncoder().encodeToString(pubLine.toByteArray(Charsets.UTF_8))
        val cmd = """
            sh -c '
              umask 077
              mkdir -p ~/.ssh
              chmod 700 ~/.ssh
              PUB=$(printf "%s" "$b64" | base64 -d)
              touch ~/.ssh/authorized_keys
              chmod 600 ~/.ssh/authorized_keys
              grep -Fqx "${'$'}PUB" ~/.ssh/authorized_keys || printf "%s\n" "${'$'}PUB" >> ~/.ssh/authorized_keys
            '
        """.trimIndent()

        targets.map { server ->
            runCatching {
                connector.connect(server, allServers, allKeys).use { conn ->
                    conn.client.startSession().use { session ->
                        val exec = session.exec(cmd)
                        exec.join(15, TimeUnit.SECONDS)
                        val code = exec.exitStatus ?: -1
                        if (code != 0) {
                            val err = exec.errorStream.bufferedReader().readText().trim()
                            throw IllegalStateException(err.ifEmpty { "退出码 $code" })
                        }
                    }
                }
                KeyDeployResult(server.id, server.name, true, "公钥已成功部署至 ~/.ssh/authorized_keys")
            }.getOrElse { e ->
                KeyDeployResult(server.id, server.name, false, e.message ?: "部署失败")
            }
        }
    }
}
