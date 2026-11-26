package com.gravityssh.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncBundle(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("exportedAt") val exportedAt: String = "",
    @SerialName("sourcePlatform") val sourcePlatform: String = "android",
    @SerialName("sourceDevice") val sourceDevice: String = "",
    @SerialName("servers") val servers: List<ServerConfig> = emptyList(),
    @SerialName("sshKeys") val sshKeys: List<SSHKeyRecord> = emptyList(),
    @SerialName("shortcuts") val shortcuts: List<CommandShortcut> = emptyList(),
    @SerialName("templates") val templates: List<BuiltinTemplate> = emptyList()
)

enum class SyncImportMode {
    MERGE,
    OVERWRITE
}

data class SyncSendSession(
    val code: String,
    val qrText: String,
    val expiresAt: String,
    val expiresAtEpochMs: Long,
    val relayUrl: String,
    val serverCount: Int,
    val keyCount: Int,
    val shortcutCount: Int,
    val templateCount: Int
)

data class SyncReceivePreview(
    val previewId: String,
    val sourcePlatform: String,
    val sourceDevice: String,
    val exportedAt: String,
    val serverCount: Int,
    val keyCount: Int,
    val shortcutCount: Int,
    val templateCount: Int
)

data class SyncImportSummary(
    val mode: SyncImportMode,
    val serversAdded: Int,
    val serversUpdated: Int,
    val keysAdded: Int,
    val keysUpdated: Int,
    val shortcutsAdded: Int,
    val shortcutsUpdated: Int,
    val templatesAdded: Int,
    val templatesUpdated: Int
)
