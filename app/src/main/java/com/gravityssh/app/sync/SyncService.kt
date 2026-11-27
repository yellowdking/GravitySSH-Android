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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        var approving: Boolean = false,
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
    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lockEpoch: Long = 0L
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

    private fun parseExpiresMs(expiresAt: String): Long =
        runCatching { Instant.parse(expiresAt).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() + SyncConfig.DEFAULT_TTL_SECONDS * 1000L }

    private inline fun <V> evictOldestIfAtCapacity(
        map: MutableMap<String, V>,
        maxSize: Int,
        crossinline expiresAtOf: (V) -> Long
    ) {
        while (map.size >= maxSize) {
            val oldest = map.entries.minByOrNull { expiresAtOf(it.value) }?.key ?: break
            map.remove(oldest)
        }
    }

    suspend fun testRelay(relayUrl: String? = null): Long =
        relayClient.ping(resolveRelayUrl(relayUrl))

    fun generateReceiverCode(): String = SyncCrypto.generateSyncCode()

    suspend fun startSend(relayUrl: String? = null): SyncSendSession {
        val startEpoch = synchronized(sessionLock) {
            requireUnlocked("发送同步数据")
            lockEpoch
        }
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
        val expiresMs = parseExpiresMs(resp.expiresAt)

        val lockedMidFlight = synchronized(sessionLock) {
            if (vaultRepository.state.value.lockState.locked || lockEpoch != startEpoch) {
                true
            } else {
                pruneExpiredLocked()
                evictOldestIfAtCapacity(pendingSends, MAX_PENDING_SESSIONS) { it.expiresAtEpochMs }
                pendingSends[sessionId] = PendingSendSession(
                    sessionId = sessionId,
                    channelId = channelId,
                    senderToken = senderToken,
                    sendCode = code,
                    relayUrl = url,
                    bundle = bundle,
                    expiresAtEpochMs = expiresMs
                )
                false
            }
        }
        if (lockedMidFlight) {
            withContext(NonCancellable) {
                runCatching { relayClient.pairCancel(url, channelId, senderToken) }
            }
            throw SecurityException("保险库已锁定，请先解锁后再发送同步数据")
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
            "waiting" -> {
                val nowApproved = synchronized(sessionLock) { pendingSends[sessionId]?.approved == true }
                if (wasApproved || nowApproved) {
                    SyncSendPollResult(state = SyncSendPollState.APPROVED)
                } else {
                    SyncSendPollResult(state = SyncSendPollState.WAITING)
                }
            }
            "requested" -> {
                val nowApproved = synchronized(sessionLock) { pendingSends[sessionId]?.approved == true }
                if (wasApproved || nowApproved) {
                    return SyncSendPollResult(state = SyncSendPollState.APPROVED, requestId = poll.requestId)
                }
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
                        val approvedDuringDecrypt = synchronized(sessionLock) { pendingSends[sessionId]?.approved == true }
                        if (approvedDuringDecrypt) {
                            SyncSendPollResult(state = SyncSendPollState.APPROVED, requestId = poll.requestId)
                        } else {
                            SyncSendPollResult(
                                state = SyncSendPollState.REQUESTED,
                                requestId = poll.requestId,
                                receiverCode = formattedCode,
                                receiverDevice = payload.receiverDevice.ifBlank { "未知设备" },
                                receiverPlatform = payload.receiverPlatform.ifBlank { "unknown" },
                                requestedAt = payload.requestedAt
                            )
                        }
                    },
                    onFailure = {
                        val approvedDuringDecrypt = synchronized(sessionLock) { pendingSends[sessionId]?.approved == true }
                        if (approvedDuringDecrypt) {
                            SyncSendPollResult(state = SyncSendPollState.APPROVED, requestId = poll.requestId)
                        } else {
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
                val removed = synchronized(sessionLock) { pendingSends.remove(sessionId) }
                val approved = wasApproved || (removed?.approved == true)
                SyncSendPollResult(
                    state = if (approved) SyncSendPollState.CONSUMED else SyncSendPollState.EXPIRED
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

        val startEpoch: Long
        val relayUrl: String
        val channelId: String
        val senderToken: String
        val sendCode: String
        val bundle: SyncBundle
        synchronized(sessionLock) {
            requireUnlocked("同意发送同步数据")
            pruneExpiredLocked()
            val s = pendingSends[sessionId]
                ?: throw IllegalStateException("同步会话已过期，请重新生成同步码")
            if (s.approved || s.bundle == null) {
                throw IllegalStateException("该同步会话已发送，请重新生成同步码")
            }
            if (s.approving) {
                throw IllegalStateException("该同步会话正在处理中，请勿重复发送")
            }
            s.approving = true
            startEpoch = lockEpoch
            relayUrl = s.relayUrl
            channelId = s.channelId
            senderToken = s.senderToken
            sendCode = s.sendCode
            bundle = s.bundle!!
        }

        try {
            val encrypted = withContext(Dispatchers.Default) {
                SyncCrypto.encryptBundleDual(bundle, sendCode, receiverCode, requestId)
            }

            synchronized(sessionLock) {
                if (vaultRepository.state.value.lockState.locked || lockEpoch != startEpoch) {
                    throw SecurityException("保险库已锁定，已中止发送同步数据")
                }
                val cur = pendingSends[sessionId]
                if (cur == null || !cur.approving || cur.approved) {
                    throw IllegalStateException("同步会话已取消或已失效，请重新生成同步码")
                }
            }

            relayClient.pairApprove(
                relayUrl = relayUrl,
                channelId = channelId,
                senderToken = senderToken,
                requestId = requestId,
                salt = encrypted.saltB64,
                nonce = encrypted.nonceB64,
                ciphertext = encrypted.ciphertextB64
            )

            val postRpcAbortReason: String? = synchronized(sessionLock) {
                when {
                    vaultRepository.state.value.lockState.locked || lockEpoch != startEpoch -> {
                        pendingSends.remove(sessionId)
                        "locked"
                    }
                    pendingSends[sessionId] == null -> "cancelled"
                    else -> {
                        val cur = pendingSends[sessionId]!!
                        cur.approving = false
                        cur.approved = true
                        cur.bundle = null
                        null
                    }
                }
            }
            if (postRpcAbortReason != null) {
                withContext(NonCancellable) {
                    runCatching { relayClient.pairCancel(relayUrl, channelId, senderToken) }
                }
                if (postRpcAbortReason == "locked") {
                    throw SecurityException("保险库已锁定，已尝试撤销同步数据")
                } else {
                    throw IllegalStateException("同步会话已取消，已尝试撤销同步数据")
                }
            }
        } catch (t: Throwable) {
            synchronized(sessionLock) {
                pendingSends[sessionId]?.let {
                    if (!it.approved) it.approving = false
                }
            }
            throw t
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

    suspend fun cancelSend(sessionIdOrCode: String) {
        val removed = synchronized(sessionLock) {
            pendingSends.remove(sessionIdOrCode) ?: pendingSends.entries
                .firstOrNull { it.value.sendCode == sessionIdOrCode }
                ?.let { pendingSends.remove(it.key) }
        } ?: return
        try {
            relayClient.pairCancel(removed.relayUrl, removed.channelId, removed.senderToken)
        } catch (_: RelayGoneException) {
            // Already expired or consumed on relay
        }
    }

    suspend fun startReceiveRequest(
        codeOrUri: String,
        receiverCode: String,
        relayUrl: String? = null
    ): SyncReceiveWaitSession {
        val startEpoch = synchronized(sessionLock) {
            requireUnlocked("接收同步数据")
            lockEpoch
        }
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

        synchronized(sessionLock) {
            if (vaultRepository.state.value.lockState.locked || lockEpoch != startEpoch) {
                throw SecurityException("保险库已锁定，请先解锁后再接收同步数据")
            }
        }

        val resp = relayClient.pairRequest(
            relayUrl = url,
            channelId = encrypted.channelId,
            requestId = requestId,
            salt = encrypted.saltB64,
            nonce = encrypted.nonceB64,
            ciphertext = encrypted.ciphertextB64
        )
        val expiresMs = parseExpiresMs(resp.expiresAt)
        val waitId = UUID.randomUUID().toString()

        synchronized(sessionLock) {
            if (vaultRepository.state.value.lockState.locked || lockEpoch != startEpoch) {
                throw SecurityException("保险库已锁定，请先解锁后再接收同步数据")
            }
            pruneExpiredLocked()
            evictOldestIfAtCapacity(pendingReceives, MAX_PENDING_SESSIONS) { it.expiresAtEpochMs }
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

        val (session, startEpoch) = synchronized(sessionLock) {
            pruneExpiredLocked()
            val s = pendingReceives[waitId]
            s to lockEpoch
        }
        if (session == null) {
            return SyncReceivePollResult(state = SyncReceivePollState.EXPIRED)
        }

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
                val preview = synchronized(sessionLock) {
                    pendingReceives.remove(waitId)
                    if (vaultRepository.state.value.lockState.locked || lockEpoch != startEpoch) {
                        throw SecurityException("保险库已锁定，请先解锁后再接收同步数据")
                    }
                    cachePreviewLocked(bundle)
                }
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

    private fun cachePreviewLocked(bundle: SyncBundle): SyncReceivePreview {
        val previewId = UUID.randomUUID().toString()
        pruneExpiredLocked()
        evictOldestIfAtCapacity(previews, MAX_CACHED_PREVIEWS) { it.expiresAtMs }
        previews[previewId] = CachedPreview(
            bundle = bundle,
            expiresAtMs = System.currentTimeMillis() + PREVIEW_TTL_MS
        )

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

    fun clearAllSessions() {
        val toCancel = synchronized(sessionLock) {
            lockEpoch++
            val activeSends = pendingSends.values.map {
                Triple(it.relayUrl, it.channelId, it.senderToken)
            }
            previews.clear()
            pendingSends.clear()
            pendingReceives.clear()
            activeSends
        }
        for ((relayUrl, channelId, senderToken) in toCancel) {
            cancelScope.launch {
                runCatching { relayClient.pairCancel(relayUrl, channelId, senderToken) }
            }
        }
    }

    private fun pruneExpiredLocked() {
        val now = System.currentTimeMillis()
        previews.entries.removeIf { it.value.expiresAtMs < now }
        pendingSends.entries.removeIf { it.value.expiresAtEpochMs + CLOCK_SKEW_GRACE_MS <= now }
        pendingReceives.entries.removeIf { it.value.expiresAtEpochMs + CLOCK_SKEW_GRACE_MS <= now }
    }

    companion object {
        private const val PREVIEW_TTL_MS = 10 * 60 * 1000L
        private const val CLOCK_SKEW_GRACE_MS = 135_000L // App Gate maxClockSkew (120s) + 15s grace
        private const val MAX_CACHED_PREVIEWS = 2
        private const val MAX_PENDING_SESSIONS = 8
    }
}
