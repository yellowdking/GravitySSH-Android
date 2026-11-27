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
    val templateCount: Int,
    val sessionId: String = ""
)

@Serializable
data class PairRequestPayload(
    @SerialName("receiverCode") val receiverCode: String,
    @SerialName("receiverDevice") val receiverDevice: String,
    @SerialName("receiverPlatform") val receiverPlatform: String = "android",
    @SerialName("requestedAt") val requestedAt: String = ""
)

enum class SyncSendPollState {
    WAITING,
    REQUESTED,
    APPROVED,
    REJECTED,
    CONSUMED,
    EXPIRED,
    ERROR
}

data class SyncSendPollResult(
    val state: SyncSendPollState,
    val requestId: String = "",
    val receiverCode: String = "",
    val receiverDevice: String = "",
    val receiverPlatform: String = "",
    val requestedAt: String = "",
    val errorMessage: String? = null
)

data class SyncReceiveWaitSession(
    val waitId: String,
    val receiverCode: String,
    val sendCode: String,
    val requestId: String,
    val relayUrl: String,
    val expiresAt: String,
    val expiresAtEpochMs: Long
)

enum class SyncReceivePollState {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}

data class SyncReceivePollResult(
    val state: SyncReceivePollState,
    val preview: SyncReceivePreview? = null
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
