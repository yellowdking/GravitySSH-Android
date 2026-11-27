package com.gravityssh.app.sync

import android.os.Build
import com.gravityssh.app.model.PairRequestPayload
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePollResult
import com.gravityssh.app.model.SyncReceivePollState
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncReceiveWaitSession
import com.gravityssh.app.model.SyncSendPollResult
import com.gravityssh.app.model.SyncSendPollState
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class SyncService(
    private val vaultRepository: VaultRepository,
    private val relayClient: SyncRelayClient = SyncRelayClient()
) {
    private data class CachedPreview(
        val bundle: SyncBundle,
        val expiresAtMs: Long
    )

    private data class PendingSendSession(
        val sessionId: String,
        val channelId: String,
        val senderToken: String,
        val sendCode: String,
        val relayUrl: String,
        var bundle: SyncBundle?,
        val expiresAtEpochMs: Long,
        var approved: Boolean = false
    )

    private data class PendingReceiveSession(
        val waitId: String,
        val channelId: String,
        val sendCode: String,
        val recvCode: String,
        val requestId: String,
        val relayUrl: String,
        val expiresAtEpochMs: Long
    )

    private val sessionLock = Any()
    private val previews = LinkedHashMap<String, CachedPreview>()
    private val pendingSends = LinkedHashMap<String, PendingSendSession>()
    private val pendingReceives = LinkedHashMap<String, PendingReceiveSession>()

    init {
        vaultRepository.addOnLockListener { clearAllSessions() }
    }

    private fun resolveRelayUrl(overrideUrl: String?): String {
        val candidate = overrideUrl?.trim().orEmpty()
        if (candidate.isNotEmpty()) {
            return SyncConfig.normalizeRelayUrl(candidate).ifEmpty { SyncConfig.DEFAULT_RELAY_URL }
        }
        val saved = vaultRepository.state.value.data.settings.syncRelayUrl.trim()
        return if (saved.isNotEmpty()) {
            SyncConfig.normalizeRelayUrl(saved).ifEmpty { SyncConfig.DEFAULT_RELAY_URL }
        } else {
            SyncConfig.DEFAULT_RELAY_URL
        }
    }

    private fun deviceName(): String {
        val mfg = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(mfg, model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Android" }.take(64)
    }

    private fun requireUnlocked(actionDesc: String) {
        if (vaultRepository.state.value.lockState.locked) {
            throw SecurityException("保险库已锁定，请先解锁后再$actionDesc")
        }
    }

    suspend fun testRelay(relayUrl: String? = null): Long =
        relayClient.ping(resolveRelayUrl(relayUrl))

    fun generateReceiverCode(): String = SyncCrypto.generateSyncCode()

    suspend fun startSend(relayUrl: String? = null): SyncSendSession {
        val url = resolveRelayUrl(relayUrl)
        val bundle = vaultRepository.exportSyncBundle(deviceName())
        val code = SyncCrypto.generateSyncCode()
        val channelId = SyncCrypto.deriveChannelId(code)
        val senderToken = SyncCrypto.generateHexToken32()
        val sessionId = UUID.randomUUID().toString()

        val resp = relayClient.pairInit(
            relayUrl = url,
            channelId = channelId,
            senderToken = senderToken,
            ttlSeconds = SyncConfig.DEFAULT_TTL_SECONDS
        )
        val expiresMs = runCatching { Instant.parse(resp.expiresAt).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() + SyncConfig.DEFAULT_TTL_SECONDS * 1000L }

        synchronized(sessionLock) {
            pruneExpiredLocked()
            while (pendingSends.size >= MAX_PENDING_SESSIONS) {
                val oldest = pendingSends.entries.minByOrNull { it.value.expiresAtEpochMs }?.key ?: break
                pendingSends.remove(oldest)
            }
            pendingSends[sessionId] = PendingSendSession(
                sessionId = sessionId,
                channelId = channelId,
                senderToken = senderToken,
                sendCode = code,
                relayUrl = url,
                bundle = bundle,
                expiresAtEpochMs = expiresMs
            )
        }

        return SyncSendSession(
            code = code,
            qrText = SyncCrypto.buildQrUri(code),
            expiresAt = resp.expiresAt.ifEmpty { Instant.ofEpochMilli(expiresMs).toString() },
            expiresAtEpochMs = expiresMs,
            relayUrl = url,
            serverCount = bundle.servers.size,
            keyCount = bundle.sshKeys.size,
            shortcutCount = bundle.shortcuts.size,
            templateCount = bundle.templates.size,
            sessionId = sessionId
        )
    }

    suspend fun pollSend(sessionId: String): SyncSendPollResult {
        val snapshot = synchronized(sessionLock) {
            pruneExpiredLocked()
            pendingSends[sessionId]?.let {
                Triple(it.relayUrl, it.channelId, it.senderToken) to (it.sendCode to it.approved)
            }
        } ?: return SyncSendPollResult(state = SyncSendPollState.EXPIRED)

        val (route, meta) = snapshot
        val (relayUrl, channelId, senderToken) = route
        val (sendCode, wasApproved) = meta

        val poll = relayClient.pairPoll(relayUrl, channelId, senderToken)
        return when (poll.state) {
            "waiting" -> SyncSendPollResult(state = SyncSendPollState.WAITING)
            "requested" -> {
                val decoded = runCatching {
                    val payload = withContext(Dispatchers.Default) {
                        SyncCrypto.decryptPairRequest(sendCode, poll.salt, poll.nonce, poll.ciphertext)
                    }
                    val formattedCode = SyncCrypto.formatCode(payload.receiverCode)
                    require(poll.requestId.isNotBlank()) { "请求 ID 缺失" }
                    payload to formattedCode
                }
                decoded.fold(
                    onSuccess = { (payload, formattedCode) ->
                        SyncSendPollResult(
                            state = SyncSendPollState.REQUESTED,
                            requestId = poll.requestId,
                            receiverCode = formattedCode,
                            receiverDevice = payload.receiverDevice.ifBlank { "未知设备" },
                            receiverPlatform = payload.receiverPlatform.ifBlank { "unknown" },
                            requestedAt = payload.requestedAt
                        )
                    },
                    onFailure = {
                        runCatching {
                            if (poll.requestId.isNotBlank()) {
                                relayClient.pairReject(relayUrl, channelId, senderToken, poll.requestId)
                            } else {
                                relayClient.pairCancel(relayUrl, channelId, senderToken)
                            }
                        }
                        synchronized(sessionLock) { pendingSends.remove(sessionId) }
                        SyncSendPollResult(
                            state = SyncSendPollState.ERROR,
                            errorMessage = "接收端验证请求解密失败，已自动拒绝此次配对"
                        )
                    }
                )
            }
            "approved" -> SyncSendPollResult(
                state = SyncSendPollState.APPROVED,
                requestId = poll.requestId
            )
            "rejected" -> {
                synchronized(sessionLock) { pendingSends.remove(sessionId) }
                SyncSendPollResult(
                    state = SyncSendPollState.REJECTED,
                    requestId = poll.requestId
                )
            }
            "gone" -> {
                synchronized(sessionLock) { pendingSends.remove(sessionId) }
                SyncSendPollResult(
                    state = if (wasApproved) SyncSendPollState.CONSUMED else SyncSendPollState.EXPIRED
                )
            }
            else -> SyncSendPollResult(
                state = SyncSendPollState.ERROR,
                errorMessage = "未知中继状态: ${poll.state}"
            )
        }
    }

    suspend fun approveSend(sessionId: String, requestId: String, receiverCode: String) {
        requireUnlocked("同意发送同步数据")
        require(requestId.isNotBlank()) { "请求 ID 不能为空" }

        val (session, bundle) = synchronized(sessionLock) {
            pruneExpiredLocked()
            val s = pendingSends[sessionId]
                ?: throw IllegalStateException("同步会话已过期，请重新生成同步码")
            val b = s.bundle
                ?: throw IllegalStateException("该同步会话已发送，请重新生成同步码")
            s to b
        }

        val encrypted = withContext(Dispatchers.Default) {
            SyncCrypto.encryptBundleDual(bundle, session.sendCode, receiverCode, requestId)
        }

        relayClient.pairApprove(
            relayUrl = session.relayUrl,
            channelId = session.channelId,
            senderToken = session.senderToken,
            requestId = requestId,
            salt = encrypted.saltB64,
            nonce = encrypted.nonceB64,
            ciphertext = encrypted.ciphertextB64
        )

        synchronized(sessionLock) {
            pendingSends[sessionId]?.let {
                it.approved = true
                it.bundle = null
            }
        }
    }

    suspend fun rejectSend(sessionId: String, requestId: String) {
        val removed = synchronized(sessionLock) { pendingSends.remove(sessionId) } ?: return
        try {
            if (requestId.isNotBlank()) {
                relayClient.pairReject(removed.relayUrl, removed.channelId, removed.senderToken, requestId)
            } else {
                relayClient.pairCancel(removed.relayUrl, removed.channelId, removed.senderToken)
            }
        } catch (_: RelayGoneException) {
            // Already expired or consumed on relay
        }
    }

    suspend fun cancelSend(sessionIdOrCode: String, relayUrl: String? = null) {
        val removed = synchronized(sessionLock) {
            pendingSends.remove(sessionIdOrCode) ?: pendingSends.entries
                .firstOrNull { it.value.sendCode == sessionIdOrCode }
                ?.let { pendingSends.remove(it.key) }
        }
        if (removed != null) {
            try {
                relayClient.pairCancel(removed.relayUrl, removed.channelId, removed.senderToken)
            } catch (_: RelayGoneException) {
                // Already expired or consumed on relay
            }
        } else {
            val canonical = runCatching { SyncCrypto.canonicalizeCode(sessionIdOrCode) }.getOrNull() ?: return
            val url = resolveRelayUrl(relayUrl)
            val channelId = SyncCrypto.deriveChannelId(canonical)
            try {
                relayClient.cancel(url, channelId)
            } catch (_: RelayGoneException) {
                // Already expired or consumed on relay
            }
        }
    }

    suspend fun startReceiveRequest(
        codeOrUri: String,
        receiverCode: String,
        relayUrl: String? = null
    ): SyncReceiveWaitSession {
        requireUnlocked("接收同步数据")
        val url = resolveRelayUrl(relayUrl)
        val canonicalSend = SyncCrypto.canonicalizeCode(codeOrUri)
        val canonicalRecv = SyncCrypto.canonicalizeCode(receiverCode)
        val formattedSend = SyncCrypto.formatCode(canonicalSend)
        val formattedRecv = SyncCrypto.formatCode(canonicalRecv)
        val requestId = SyncCrypto.generateHexToken32()
        val nowIso = Instant.now().toString()

        val encrypted = withContext(Dispatchers.Default) {
            SyncCrypto.encryptPairRequest(
                sendCode = canonicalSend,
                info = PairRequestPayload(
                    receiverCode = formattedRecv,
                    receiverDevice = deviceName(),
                    receiverPlatform = "android",
                    requestedAt = nowIso
                )
            )
        }

        val resp = relayClient.pairRequest(
            relayUrl = url,
            channelId = encrypted.channelId,
            requestId = requestId,
            salt = encrypted.saltB64,
            nonce = encrypted.nonceB64,
            ciphertext = encrypted.ciphertextB64
        )
        val expiresMs = runCatching { Instant.parse(resp.expiresAt).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() + SyncConfig.DEFAULT_TTL_SECONDS * 1000L }
        val waitId = UUID.randomUUID().toString()

        synchronized(sessionLock) {
            pruneExpiredLocked()
            while (pendingReceives.size >= MAX_PENDING_SESSIONS) {
                val oldest = pendingReceives.entries.minByOrNull { it.value.expiresAtEpochMs }?.key ?: break
                pendingReceives.remove(oldest)
            }
            pendingReceives[waitId] = PendingReceiveSession(
                waitId = waitId,
                channelId = encrypted.channelId,
                sendCode = formattedSend,
                recvCode = formattedRecv,
                requestId = requestId,
                relayUrl = url,
                expiresAtEpochMs = expiresMs
            )
        }

        return SyncReceiveWaitSession(
            waitId = waitId,
            receiverCode = formattedRecv,
            sendCode = formattedSend,
            requestId = requestId,
            relayUrl = url,
            expiresAt = resp.expiresAt.ifEmpty { Instant.ofEpochMilli(expiresMs).toString() },
            expiresAtEpochMs = expiresMs
        )
    }

    suspend fun pollReceiveStatus(waitId: String): SyncReceivePollResult {
        if (vaultRepository.state.value.lockState.locked) {
            cancelReceiveWait(waitId)
            throw SecurityException("保险库已锁定，请先解锁后再接收同步数据")
        }

        val session = synchronized(sessionLock) {
            pruneExpiredLocked()
            pendingReceives[waitId]
        } ?: return SyncReceivePollResult(state = SyncReceivePollState.EXPIRED)

        val statusResp = try {
            relayClient.pairStatus(session.relayUrl, session.channelId, session.requestId)
        } catch (_: RelayGoneException) {
            synchronized(sessionLock) { pendingReceives.remove(waitId) }
            return SyncReceivePollResult(state = SyncReceivePollState.EXPIRED)
        }

        return when (statusResp.state) {
            "pending" -> SyncReceivePollResult(state = SyncReceivePollState.PENDING)
            "rejected" -> {
                synchronized(sessionLock) { pendingReceives.remove(waitId) }
                SyncReceivePollResult(state = SyncReceivePollState.REJECTED)
            }
            "gone", "expired" -> {
                synchronized(sessionLock) { pendingReceives.remove(waitId) }
                SyncReceivePollResult(state = SyncReceivePollState.EXPIRED)
            }
            "approved" -> {
                val bundle = withContext(Dispatchers.Default) {
                    SyncCrypto.decryptBundleDual(
                        saltB64 = statusResp.salt,
                        nonceB64 = statusResp.nonce,
                        ctB64 = statusResp.ciphertext,
                        sendCode = session.sendCode,
                        recvCode = session.recvCode,
                        requestId = session.requestId
                    )
                }
                synchronized(sessionLock) { pendingReceives.remove(waitId) }
                val preview = cachePreview(bundle)
                SyncReceivePollResult(state = SyncReceivePollState.APPROVED, preview = preview)
            }
            else -> throw IllegalStateException("未知中继状态: ${statusResp.state}")
        }
    }

    fun cancelReceiveWait(waitId: String) {
        synchronized(sessionLock) {
            pendingReceives.remove(waitId)
        }
    }

    suspend fun previewReceive(codeOrUri: String, relayUrl: String? = null): SyncReceivePreview {
        requireUnlocked("接收同步数据")
        val url = resolveRelayUrl(relayUrl)
        val canonical = SyncCrypto.canonicalizeCode(codeOrUri)
        val channelId = SyncCrypto.deriveChannelId(canonical)
        val pickup = relayClient.pickup(url, channelId)
        val bundle = withContext(Dispatchers.Default) {
            SyncCrypto.decryptBundle(pickup.salt, pickup.nonce, pickup.ciphertext, canonical)
        }
        return cachePreview(bundle)
    }

    private fun cachePreview(bundle: SyncBundle): SyncReceivePreview {
        val previewId = UUID.randomUUID().toString()
        synchronized(sessionLock) {
            pruneExpiredLocked()
            while (previews.size >= MAX_CACHED_PREVIEWS) {
                val oldestKey = previews.entries.minByOrNull { it.value.expiresAtMs }?.key ?: break
                previews.remove(oldestKey)
            }
            previews[previewId] = CachedPreview(
                bundle = bundle,
                expiresAtMs = System.currentTimeMillis() + PREVIEW_TTL_MS
            )
        }

        return SyncReceivePreview(
            previewId = previewId,
            sourcePlatform = bundle.sourcePlatform,
            sourceDevice = bundle.sourceDevice,
            exportedAt = bundle.exportedAt,
            serverCount = bundle.servers.size,
            keyCount = bundle.sshKeys.size,
            shortcutCount = bundle.shortcuts.size,
            templateCount = bundle.templates.size
        )
    }

    fun applyReceive(previewId: String, mode: SyncImportMode): SyncImportSummary {
        val cached = synchronized(sessionLock) {
            pruneExpiredLocked()
            previews[previewId]
        } ?: throw IllegalStateException("同步预览已过期，请重新获取同步码")

        val summary = vaultRepository.importSyncBundle(cached.bundle, mode)
        synchronized(sessionLock) {
            previews.remove(previewId)
        }
        return summary
    }

    fun discardPreview(previewId: String) {
        synchronized(sessionLock) {
            previews.remove(previewId)
        }
    }

    fun clearPreviews() {
        synchronized(sessionLock) {
            previews.clear()
        }
    }

    fun clearAllSessions() {
        synchronized(sessionLock) {
            previews.clear()
            pendingSends.clear()
            pendingReceives.clear()
        }
    }

    private fun pruneExpiredLocked() {
        val now = System.currentTimeMillis()
        previews.entries.removeIf { it.value.expiresAtMs < now }
        pendingSends.entries.removeIf { it.value.expiresAtEpochMs <= now }
        pendingReceives.entries.removeIf { it.value.expiresAtEpochMs <= now }
    }

    companion object {
        private const val PREVIEW_TTL_MS = 10 * 60 * 1000L
        private const val MAX_CACHED_PREVIEWS = 2
        private const val MAX_PENDING_SESSIONS = 8
    }
}
