package com.gravityssh.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SessionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

data class SessionTab(
    val sessionId: String,
    val serverId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val username: String,
    val status: SessionStatus = SessionStatus.CONNECTING,
    val sftpReady: Boolean = false,
    val homeDir: String = "/",
    val currentDir: String = "/",
    val viaSummary: String = "",
    val error: String? = null,
    val metrics: HostMetrics? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class HostMetrics(
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("supported") val supported: Boolean = false,
    @SerialName("latencyMs") val latencyMs: Long = 0,
    @SerialName("cpuPercent") val cpuPercent: Double = 0.0,
    @SerialName("memUsedMb") val memUsedMb: Long = 0,
    @SerialName("memTotalMb") val memTotalMb: Long = 0,
    @SerialName("memPercent") val memPercent: Double = 0.0,
    @SerialName("diskMount") val diskMount: String = "/",
    @SerialName("diskPercent") val diskPercent: Double = 0.0,
    @SerialName("netRxKbps") val netRxKbps: Double = 0.0,
    @SerialName("netTxKbps") val netTxKbps: Double = 0.0,
    @SerialName("load1") val load1: Double = 0.0,
    @SerialName("cores") val cores: Int = 1,
    @SerialName("hasGpu") val hasGpu: Boolean = false,
    @SerialName("gpuUtil") val gpuUtil: Double = 0.0,
    @SerialName("gpuName") val gpuName: String = "",
    @SerialName("updatedAt") val updatedAt: String = ""
)

@Serializable
data class SFTPFileEntry(
    @SerialName("name") val name: String,
    @SerialName("path") val path: String,
    @SerialName("isDir") val isDir: Boolean,
    @SerialName("isSymlink") val isSymlink: Boolean = false,
    @SerialName("size") val size: Long = 0,
    @SerialName("mode") val mode: String = "",
    @SerialName("modTime") val modTime: String = "",
    val modTimeEpochSec: Long = 0L
)

data class SFTPListResult(
    val path: String,
    val parent: String,
    val entries: List<SFTPFileEntry>
)

data class RemoteFileContent(
    val path: String,
    val content: String,
    val size: Long
)

data class TransferProgress(
    val transferId: String,
    val sessionId: String,
    val direction: String,
    val fileName: String,
    val remotePath: String,
    val transferred: Long,
    val total: Long,
    val percent: Int,
    val status: String,
    val error: String? = null
)

data class KeyDeployResult(
    val serverId: String,
    val serverName: String,
    val ok: Boolean,
    val message: String
)

data class CompletionItem(
    val command: String,
    val description: String,
    val category: String,
    val source: String,
    val insertText: String = command,
    val score: Int = 0
)

data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseUrl: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val publishedAt: String
)
