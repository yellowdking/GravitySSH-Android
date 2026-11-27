package com.gravityssh.app.model

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
    val metrics: HostMetrics? = null
)

data class HostMetrics(
    val sessionId: String = "",
    val supported: Boolean = false,
    val latencyMs: Long = 0,
    val cpuPercent: Double = 0.0,
    val memUsedMb: Long = 0,
    val memTotalMb: Long = 0,
    val memPercent: Double = 0.0,
    val diskMount: String = "/",
    val diskPercent: Double = 0.0,
    val netRxKbps: Double = 0.0,
    val netTxKbps: Double = 0.0,
    val load1: Double = 0.0,
    val cores: Int = 1,
    val hasGpu: Boolean = false,
    val gpuUtil: Double = 0.0,
    val gpuName: String = "",
    val updatedAt: String = ""
)

data class SFTPFileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isSymlink: Boolean = false,
    val size: Long = 0,
    val mode: String = "",
    val modTime: String = "",
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
