package com.gravityssh.app.sync

import com.gravityssh.app.model.SyncRelayStatusUi
import com.yellowdking.synctool.crypto.Crockford
import com.yellowdking.synctool.envelope.ErrorCode
import com.yellowdking.synctool.envelope.Redact
import com.yellowdking.synctool.envelope.RelayProfile
import com.yellowdking.synctool.envelope.SyncError
import com.yellowdking.synctool.envelope.SyncUri
import java.io.IOException
import java.time.Instant

object SyncConfig {
    const val APP_ID: String = "com.yellowdking.gravityssh"
    const val PARCEL_SCHEMA: String = "gravityssh.vault.v1"

    // Updated in Batch B after live VPS2 synctool-relay v0.3.0 cutover
    const val DEFAULT_RELAY_URI: String =
        "synctool://relay?v=1&u=https%3A%2F%2F43.155.231.14%3A54843&pin=f7757f3863cfd16d6f3c92c36d48d12fc500058f42a8946987056f6263335da8#k=vAs_uYp5mZn187h54j6LsFG-0w3b5qtEQif3C5lcTyQ"

    const val ENV_DEFAULT_RELAY_URI: String = "SYNCTOOL_DEFAULT_RELAY_URI"
    const val DEFAULT_ROOM_TTL_SEC: Int = 300
    const val MAX_INLINE_SUMMARY_BYTES: Int = 512
    const val MAX_BUNDLE_GUNZIP_BYTES: Int = 16 * 1024 * 1024

    fun redactUri(raw: String): String = Redact.redactUri(raw.trim())

    fun parseRelayFromPairURI(raw: String): RelayProfile? = SyncUri.extractRelayFromPairUri(raw)

    fun parseRelayFromPairUri(raw: String): RelayProfile? = SyncUri.extractRelayFromPairUri(raw)

    fun isValidRelayUri(raw: String): Boolean =
        runCatching { SyncUri.parseRelayUri(raw.trim()) }.isSuccess

    fun isValidPairUri(raw: String): Boolean = runCatching {
        val offer = SyncUri.parsePairUri(raw.trim())
        offer.zeroSecrets()
    }.isSuccess

    fun isValidRoomCodeOrPairUri(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return if (trimmed.startsWith("synctool://", ignoreCase = true)) {
            isValidPairUri(trimmed)
        } else {
            runCatching { Crockford.normalizeRoomId(trimmed) }.isSuccess
        }
    }

    fun formatRoomCode(raw: String): String =
        runCatching { Crockford.formatRoomId(raw.trim()) }.getOrDefault(raw.trim())

    fun resolveDefaultRelayUri(overrideDefaultUri: (() -> String)? = null): String {
        val explicit = overrideDefaultUri?.invoke()?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit
        val envVal = System.getenv(ENV_DEFAULT_RELAY_URI)?.trim().orEmpty()
        return envVal.ifEmpty { DEFAULT_RELAY_URI }
    }

    fun resolveRelayProfile(
        customRelayUri: String,
        overrideDefaultUri: (() -> String)? = null
    ): Pair<RelayProfile, Boolean> {
        val trimmedCustom = customRelayUri.trim()
        if (trimmedCustom.isNotEmpty()) {
            val profile = SyncUri.parseRelayUri(trimmedCustom)
            return profile to true
        }
        val defaultUri = resolveDefaultRelayUri(overrideDefaultUri)
        val profile = SyncUri.parseRelayUri(defaultUri).copy(isDefault = true)
        return profile to false
    }

    fun toRelayStatusUi(
        profile: RelayProfile,
        isCustom: Boolean,
        certExpiresAtEpochSec: Long = 0L,
        certRotateRecommended: Boolean = false
    ): SyncRelayStatusUi {
        val redacted = if (isCustom) {
            runCatching { redactUri(SyncUri.formatRelayUri(profile)) }.getOrDefault(redactUri(profile.endpoint))
        } else {
            ""
        }
        val certIso = if (certExpiresAtEpochSec > 0L) {
            runCatching { Instant.ofEpochSecond(certExpiresAtEpochSec).toString() }.getOrDefault("")
        } else {
            ""
        }
        return SyncRelayStatusUi(
            mode = if (isCustom) "custom" else "default",
            endpoint = profile.endpoint,
            redactedUri = redacted,
            relayId = profile.relayId,
            tlsMode = profile.tlsMode,
            spkiPinShort = profile.spkiPin.take(12),
            certExpiresAt = certIso,
            certRotateRecommended = certRotateRecommended
        )
    }

    fun toFriendlyException(error: Throwable): Exception {
        if (error is SecurityException || error is IllegalArgumentException || error is IllegalStateException) {
            val safe = Redact.redactMessage(error.message.orEmpty())
            return when (error) {
                is SecurityException -> SecurityException(safe.ifEmpty { "安全校验未通过" })
                is IllegalArgumentException -> IllegalArgumentException(safe.ifEmpty { "同步参数无效" })
                else -> IllegalStateException(safe.ifEmpty { "同步状态异常" })
            }
        }
        if (error is SyncError) {
            val detail = error.cleanMessage
            val msg = when (error.code) {
                ErrorCode.ERR_TLS_PIN_MISMATCH ->
                    "中继服务器 TLS SPKI 指纹不匹配，已阻断连接以防中间人攻击"
                ErrorCode.ERR_TLS_CERT_INVALID ->
                    "中继服务器 TLS 证书无效或已过期"
                ErrorCode.ERR_AUTH_FAILED ->
                    "中继访问密钥（#k=）认证失败，请核对中继 URI"
                ErrorCode.ERR_UNAUTHORIZED_TOKEN ->
                    "投递令牌或房间凭证已失效（可能已被对端吊销）"
                ErrorCode.ERR_URI_INVALID ->
                    "同步 URI 格式无效（需为 synctool://relay?...#k=... 或 synctool://pair?...）"
                ErrorCode.ERR_SAS_MISMATCH ->
                    "6 位安全核验码不一致或已拒绝，配对房间已自动销毁"
                ErrorCode.ERR_ROOM_BURNED ->
                    "配对房间已被销毁或已过期，请重新发起"
                ErrorCode.ERR_ROOM_BUSY ->
                    "该配对房间已被其他设备占用，请重新生成"
                ErrorCode.ERR_HANDSHAKE_TIMEOUT,
                ErrorCode.ERR_PIPE_PEER_TIMEOUT ->
                    "等待对端响应超时，请确认双方网络畅通后重试"
                ErrorCode.ERR_SPOOL_DISABLED ->
                    "当前中继未启用离线信箱暂存（--spool=off），请改用实时直传"
                ErrorCode.ERR_OFFLINE_QUOTA_EXCEEDED ->
                    "离线信箱配额已满或配置包超出上限，请清理收件箱或改用实时直传"
                ErrorCode.ERR_MSG_REPLAY_DETECTED,
                ErrorCode.ERR_REPLAY_DETECTED ->
                    "检测到重复或过期的同步包裹，已自动拦截重放"
                ErrorCode.ERR_DEVICE_UNTRUSTED ->
                    "发送端设备未配对或已被吊销"
                ErrorCode.ERR_CRYPTO_DECRYPT_FAILED,
                ErrorCode.ERR_CRYPTO_SIGNATURE_INVALID,
                ErrorCode.ERR_CRYPTO_HANDSHAKE_FAILED,
                ErrorCode.ERR_HASH_MISMATCH,
                ErrorCode.ERR_STREAM_TRUNCATED ->
                    "端到端加密签名或完整性校验失败，已拒绝导入"
                ErrorCode.ERR_UNSUPPORTED_VERSION ->
                    "同步协议版本不兼容，请将两端 GravitySSH 升级至最新版本"
                ErrorCode.ERR_RATE_LIMITED ->
                    "中继请求过于频繁，请稍后再试"
                ErrorCode.ERR_CLOCK_SKEW ->
                    "本机系统时间偏差过大，请开启自动对时后重试"
                ErrorCode.ERR_INTERRUPTED_RESUMABLE ->
                    "传输中断（已保存断点，重新执行可续传）"
                ErrorCode.ERR_NOT_FOUND ->
                    "同步房间或待收项目不存在（可能已过期或已销毁）"
                ErrorCode.ERR_RELAY_UNREACHABLE ->
                    "无法连接同步中继服务器，请检查网络或中继地址"
                else -> if (detail.isNotEmpty()) "同步失败 (${error.code}): $detail" else "同步失败 (${error.code})"
            }
            return IOException(Redact.redactMessage(msg), error)
        }
        val safeMsg = Redact.redactMessage(error.message.orEmpty()).ifEmpty { error.javaClass.simpleName }
        return IOException(safeMsg, error)
    }
}
