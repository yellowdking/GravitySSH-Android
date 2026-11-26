package com.gravityssh.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object AuthType {
    const val PASSWORD = "password"
    const val KEY = "key"
    const val BOTH = "both"
}

object ProxyType {
    const val NONE = "none"
    const val SOCKS5 = "socks5"
    const val HTTP = "http"
}

object JumpMode {
    const val NONE = "none"
    const val SERVER = "server"
    const val CUSTOM = "custom"
}

@Serializable
data class ProxyConfig(
    @SerialName("type") val type: String = ProxyType.NONE,
    @SerialName("host") val host: String = "",
    @SerialName("port") val port: Int = 0,
    @SerialName("username") val username: String = "",
    @SerialName("password") val password: String = ""
)

@Serializable
data class JumpHostConfig(
    @SerialName("mode") val mode: String = JumpMode.NONE,
    @SerialName("serverId") val serverId: String = "",
    @SerialName("host") val host: String = "",
    @SerialName("port") val port: Int = 22,
    @SerialName("username") val username: String = "",
    @SerialName("authType") val authType: String = AuthType.PASSWORD,
    @SerialName("password") val password: String = "",
    @SerialName("sshKeyId") val sshKeyId: String = "",
    @SerialName("useMainProxy") val useMainProxy: Boolean = false
)

@Serializable
data class ServerConfig(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("group") val group: String = "默认分组",
    @SerialName("color") val color: String = "#6366F1",
    @SerialName("host") val host: String = "",
    @SerialName("port") val port: Int = 22,
    @SerialName("username") val username: String = "root",
    @SerialName("authType") val authType: String = AuthType.PASSWORD,
    @SerialName("password") val password: String = "",
    @SerialName("sshKeyId") val sshKeyId: String = "",
    @SerialName("proxy") val proxy: ProxyConfig = ProxyConfig(),
    @SerialName("jumpHost") val jumpHost: JumpHostConfig = JumpHostConfig(),
    @SerialName("timeoutSeconds") val timeoutSeconds: Int = 15,
    @SerialName("keepAliveSeconds") val keepAliveSeconds: Int = 30,
    @SerialName("defaultRemotePath") val defaultRemotePath: String = "",
    @SerialName("startupCommand") val startupCommand: String = "",
    @SerialName("notes") val notes: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("updatedAt") val updatedAt: String = "",
    @SerialName("lastConnectedAt") val lastConnectedAt: String? = null
)
