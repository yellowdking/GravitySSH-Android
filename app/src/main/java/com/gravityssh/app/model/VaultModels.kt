package com.gravityssh.app.model

import com.yellowdking.synctool.envelope.DeviceCard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SSHKeyRecord(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("keyType") val keyType: String = "ed25519",
    @SerialName("fingerprint") val fingerprint: String = "",
    @SerialName("publicKey") val publicKey: String = "",
    @SerialName("privateKeyPem") val privateKeyPem: String = "",
    @SerialName("passphrase") val passphrase: String = "",
    @SerialName("comment") val comment: String = "",
    @SerialName("createdAt") val createdAt: String = ""
)

@Serializable
data class CommandRecord(
    @SerialName("id") val id: String = "",
    @SerialName("command") val command: String = "",
    @SerialName("serverId") val serverId: String = "",
    @SerialName("serverName") val serverName: String = "",
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("executedAt") val executedAt: String = ""
)

@Serializable
data class CommandShortcut(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("command") val command: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("category") val category: String = "常用",
    @SerialName("autoExecute") val autoExecute: Boolean = false,
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("useCount") val useCount: Int = 0,
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("updatedAt") val updatedAt: String = ""
)

@Serializable
data class BuiltinTemplate(
    @SerialName("id") val id: String = "",
    @SerialName("presetId") val presetId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("category") val category: String = "自定义",
    @SerialName("description") val description: String = "",
    @SerialName("safetyNote") val safetyNote: String = "",
    @SerialName("command") val command: String = ""
)

data class PresetLoadResult(
    val added: Int = 0,
    val skipped: Int = 0,
    val templates: List<BuiltinTemplate> = emptyList()
)

@Serializable
data class AppSettings(
    @SerialName("autocompleteEnabled") val autocompleteEnabled: Boolean = true,
    @SerialName("autocompleteFromHistory") val autocompleteFromHistory: Boolean = true,
    @SerialName("terminalFontSize") val terminalFontSize: Int = 13,
    @SerialName("defaultKeepAliveSeconds") val defaultKeepAliveSeconds: Int = 30,
    @SerialName("maxHistoryEntries") val maxHistoryEntries: Int = 2000,
    @SerialName("recordCommands") val recordCommands: Boolean = true,
    @SerialName("monitorBarEnabled") val monitorBarEnabled: Boolean = true,
    @SerialName("pasteGuardEnabled") val pasteGuardEnabled: Boolean = true,
    @SerialName("keywordHighlightEnabled") val keywordHighlightEnabled: Boolean = true,
    @SerialName("checkUpdatesOnStartup") val checkUpdatesOnStartup: Boolean = true,
    @SerialName("biometricLockEnabled") val biometricLockEnabled: Boolean = false,
    @SerialName("syncRelayUrl") val syncRelayUrl: String = "",
    @SerialName("touchCursorMode") val touchCursorMode: String = "auto",
    @SerialName("touchCursorSensitivity") val touchCursorSensitivity: String = "balanced",
    @SerialName("touchCursorFling") val touchCursorFling: Boolean = true,
    @SerialName("autoLockMinutes") val autoLockMinutes: Int = 0
)

@Serializable
data class SeenMsgRecord(
    @SerialName("msgId") val msgId: String = "",
    @SerialName("createdAt") val createdAt: Long = 0L,
    @SerialName("seenAt") val seenAt: Long = 0L
)

@Serializable
data class SyncVaultState(
    @SerialName("masterSeedB64u") val masterSeedB64u: String = "",
    @SerialName("localDeviceLabel") val localDeviceLabel: String = "",
    @SerialName("customRelayUri") val customRelayUri: String = "",
    @SerialName("pairedDevices") val pairedDevices: List<DeviceCard> = emptyList(),
    @SerialName("pendingTokenDeletes") val pendingTokenDeletes: List<String> = emptyList(),
    @SerialName("seenMsgIds") val seenMsgIds: List<SeenMsgRecord> = emptyList(),
    @SerialName("replayWatermarkTs") val replayWatermarkTs: Long = 0L
)

@Serializable
data class VaultData(
    @SerialName("version") val version: Int = 2,
    @SerialName("servers") val servers: List<ServerConfig> = emptyList(),
    @SerialName("sshKeys") val sshKeys: List<SSHKeyRecord> = emptyList(),
    @SerialName("shortcuts") val shortcuts: List<CommandShortcut> = emptyList(),
    @SerialName("templates") val templates: List<BuiltinTemplate> = emptyList(),
    @SerialName("history") val history: List<CommandRecord> = emptyList(),
    @SerialName("settings") val settings: AppSettings = AppSettings(),
    @SerialName("syncState") val syncState: SyncVaultState = SyncVaultState()
)

data class VaultLockState(
    val locked: Boolean = false,
    val hasPassword: Boolean = false,
    val biometricEnabled: Boolean = false,
    val startupError: String? = null,
    val initialized: Boolean = true
)

data class VaultState(
    val lockState: VaultLockState = VaultLockState(),
    val data: VaultData = VaultData()
)
