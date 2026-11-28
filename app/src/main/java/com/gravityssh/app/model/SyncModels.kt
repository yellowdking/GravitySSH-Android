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
    OVERWRITE;

    companion object {
        fun fromWire(mode: String): SyncImportMode = when (mode.trim().lowercase()) {
            "merge" -> MERGE
            "overwrite" -> OVERWRITE
            else -> throw IllegalArgumentException("不支持的同步导入模式: $mode")
        }
    }
}

data class SyncReceivePreview(
    val previewId: String = "",
    val sourcePlatform: String = "",
    val sourceDevice: String = "",
    val exportedAt: String = "",
    val serverCount: Int = 0,
    val keyCount: Int = 0,
    val shortcutCount: Int = 0,
    val templateCount: Int = 0
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

data class SyncRelayStatusUi(
    val mode: String = "default",
    val endpoint: String = "",
    val redactedUri: String = "",
    val relayId: String = "",
    val tlsMode: String = "spki_pin",
    val spkiPinShort: String = "",
    val certExpiresAt: String = "",
    val certRotateRecommended: Boolean = false
)

data class SyncPingResultUi(
    val rttMs: Long,
    val profile: String,
    val spoolMode: String,
    val clockOffsetSec: Long,
    val certExpiresAt: String = "",
    val certRotateRecommended: Boolean = false
)

data class SyncPairedDeviceUi(
    val deviceId: String,
    val label: String,
    val platform: String,
    val relayId: String,
    val issuedAt: Long
)

data class SyncInboxItemUi(
    val itemId: String,
    val msgId: String,
    val senderDeviceId: String,
    val senderLabel: String,
    val senderPlatform: String,
    val createdAt: Long,
    val expiresAt: Long,
    val totalBytes: Long,
    val serverCount: Int,
    val keyCount: Int,
    val shortcutCount: Int,
    val templateCount: Int,
    val pairingBroken: Boolean = false,
    val errorMessage: String = ""
)

enum class SyncFlowStage {
    IDLE,
    WAITING_PEER,
    AWAITING_SAS_INPUT,
    AWAITING_SAS_CONFIRM,
    TRANSFERRING,
    READY_PREVIEW,
    COMPLETED,
    ERROR
}

data class SyncUiState(
    val localDeviceId: String = "",
    val localDeviceLabel: String = "",
    val relay: SyncRelayStatusUi = SyncRelayStatusUi(),
    val pairedDevices: List<SyncPairedDeviceUi> = emptyList(),
    val inboxItems: List<SyncInboxItemUi> = emptyList(),
    val isRefreshingInbox: Boolean = false,
    val flowStage: SyncFlowStage = SyncFlowStage.IDLE,
    val flowPurpose: String = "",
    val flowRole: String = "",
    val flowMode: Int = 1,
    val pairUri: String = "",
    val roomCode: String = "",
    val expiresAtEpochSec: Long = 0L,
    val sasCode: String = "",
    val sasAttemptsRemaining: Int = 3,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val suggestPipeDeviceId: String? = null,
    val pendingPreview: SyncReceivePreview? = null,
    val pendingInboxItemId: String? = null,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val pingResult: SyncPingResultUi? = null,
    val isPinging: Boolean = false
)
