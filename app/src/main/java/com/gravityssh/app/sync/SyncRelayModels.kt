package com.gravityssh.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairInitRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String,
    @SerialName("ttlSeconds") val ttlSeconds: Int = SyncConfig.DEFAULT_TTL_SECONDS
)

@Serializable
data class PairInitResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("expiresAt") val expiresAt: String = ""
)

@Serializable
data class PairRequestBody(
    @SerialName("channelId") val channelId: String,
    @SerialName("requestId") val requestId: String,
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String
)

@Serializable
data class PairRequestResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("expiresAt") val expiresAt: String = ""
)

@Serializable
data class PairSenderTokenRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String
)

@Serializable
data class PairPollResponse(
    @SerialName("state") val state: String = "",
    @SerialName("requestId") val requestId: String = "",
    @SerialName("salt") val salt: String = "",
    @SerialName("nonce") val nonce: String = "",
    @SerialName("ciphertext") val ciphertext: String = ""
)

@Serializable
data class PairApproveRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String,
    @SerialName("requestId") val requestId: String,
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String
)

@Serializable
data class PairRejectRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String,
    @SerialName("requestId") val requestId: String
)

@Serializable
data class PairStatusRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("requestId") val requestId: String
)

@Serializable
data class PairStatusResponse(
    @SerialName("state") val state: String = "",
    @SerialName("salt") val salt: String = "",
    @SerialName("nonce") val nonce: String = "",
    @SerialName("ciphertext") val ciphertext: String = ""
)

@Serializable
internal data class RelayProtoErrorResponse(
    @SerialName("error") val error: String = "",
    @SerialName("minVersion") val minVersion: Int = 0,
    @SerialName("maxVersion") val maxVersion: Int = 0
)
