package com.gravityssh.app.ssh

import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.JumpHostConfig
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket

data class ConnectedSsh(
    val client: SSHClient,
    val jumpClient: SSHClient? = null,
    val forwarder: LocalPortForwarder? = null,
    val forwardSocket: ServerSocket? = null,
    val viaSummary: String = ""
) : Closeable {
    override fun close() {
        runCatching { client.disconnect() }
        runCatching { client.close() }
        runCatching { forwarder?.close() }
        runCatching { forwardSocket?.close() }
        runCatching { jumpClient?.disconnect() }
        runCatching { jumpClient?.close() }
    }
}

internal object SshAuthHelper {
    fun authenticate(client: SSHClient, server: ServerConfig, allKeys: List<SSHKeyRecord>) {
        val user = server.username.trim().ifBlank { "root" }
        val keyRecord = allKeys.find { it.id == server.sshKeyId }
        val tryKey = {
            val rec = keyRecord ?: throw IOException("未找到绑定的 SSH 密钥")
            val finder = if (rec.passphrase.isNotEmpty()) PasswordUtils.createOneOff(rec.passphrase.toCharArray()) else null
            val provider = client.loadKeys(rec.privateKeyPem, null, finder)
            client.authPublickey(user, provider)
        }
        val tryPassword = {
            if (server.password.isEmpty()) throw IOException("密码不能为空")
            client.authPassword(user, server.password)
        }
        when (server.authType) {
            AuthType.KEY -> tryKey()
            AuthType.BOTH -> if (runCatching { tryKey() }.isFailure) tryPassword()
            else -> tryPassword()
        }
    }

    fun resolveJumpServer(
        jump: JumpHostConfig,
        allServers: List<ServerConfig>,
        mainProxy: ProxyConfig
    ): ServerConfig {
        val base = if (jump.mode == JumpMode.SERVER) {
            allServers.find { it.id == jump.serverId } ?: throw IOException("未找到所选跳板服务器")
        } else {
            ServerConfig(
                host = jump.host,
                port = jump.port.coerceIn(1, 65535),
                username = jump.username.ifBlank { "root" },
                authType = jump.authType.ifBlank { AuthType.PASSWORD },
                password = jump.password,
                sshKeyId = jump.sshKeyId
            )
        }
        return if (jump.useMainProxy) base.copy(proxy = mainProxy) else base
    }
}
