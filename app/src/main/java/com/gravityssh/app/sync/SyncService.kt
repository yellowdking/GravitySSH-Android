package com.gravityssh.app.sync

import android.os.Build
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePreview
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

    private val previewLock = Any()
    private val previews = LinkedHashMap<String, CachedPreview>()

    init {
        vaultRepository.addOnLockListener { clearPreviews() }
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
        return listOf(mfg, model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Android" }
    }

    suspend fun testRelay(relayUrl: String? = null): Long =
        relayClient.ping(resolveRelayUrl(relayUrl))

    suspend fun startSend(relayUrl: String? = null): SyncSendSession {
        val url = resolveRelayUrl(relayUrl)
        val bundle = vaultRepository.exportSyncBundle(deviceName())
        val code = SyncCrypto.generateSyncCode()
        val encrypted = withContext(Dispatchers.Default) {
            SyncCrypto.encryptBundle(bundle, code)
        }

        val resp = relayClient.drop(
            url,
            DropRequest(
                channelId = encrypted.channelId,
                salt = encrypted.saltB64,
                nonce = encrypted.nonceB64,
                ciphertext = encrypted.ciphertextB64,
                ttlSeconds = SyncConfig.DEFAULT_TTL_SECONDS
            )
        )
        val expiresMs = runCatching { Instant.parse(resp.expiresAt).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() + SyncConfig.DEFAULT_TTL_SECONDS * 1000L }

        return SyncSendSession(
            code = code,
            qrText = SyncCrypto.buildQrUri(code),
            expiresAt = resp.expiresAt.ifEmpty { Instant.ofEpochMilli(expiresMs).toString() },
            expiresAtEpochMs = expiresMs,
            relayUrl = url,
            serverCount = bundle.servers.size,
            keyCount = bundle.sshKeys.size,
            shortcutCount = bundle.shortcuts.size,
            templateCount = bundle.templates.size
        )
    }

    suspend fun cancelSend(code: String, relayUrl: String? = null) {
        val url = resolveRelayUrl(relayUrl)
        val channelId = SyncCrypto.deriveChannelId(code)
        relayClient.cancel(url, channelId)
    }

    suspend fun previewReceive(codeOrUri: String, relayUrl: String? = null): SyncReceivePreview {
        if (vaultRepository.state.value.lockState.locked) {
            throw SecurityException("保险库已锁定，请先解锁后再接收同步数据")
        }
        val url = resolveRelayUrl(relayUrl)
        val canonical = SyncCrypto.canonicalizeCode(codeOrUri)
        val channelId = SyncCrypto.deriveChannelId(canonical)
        val pickup = relayClient.pickup(url, channelId)
        val bundle = withContext(Dispatchers.Default) {
            SyncCrypto.decryptBundle(pickup.salt, pickup.nonce, pickup.ciphertext, canonical)
        }

        val previewId = UUID.randomUUID().toString()
        synchronized(previewLock) {
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
        val cached = synchronized(previewLock) {
            pruneExpiredLocked()
            previews[previewId]
        } ?: throw IllegalStateException("同步预览已过期，请重新获取同步码")

        val summary = vaultRepository.importSyncBundle(cached.bundle, mode)
        synchronized(previewLock) {
            previews.remove(previewId)
        }
        return summary
    }

    fun discardPreview(previewId: String) {
        synchronized(previewLock) {
            previews.remove(previewId)
        }
    }

    fun clearPreviews() {
        synchronized(previewLock) {
            previews.clear()
        }
    }

    private fun pruneExpiredLocked() {
        val now = System.currentTimeMillis()
        previews.entries.removeIf { it.value.expiresAtMs < now }
    }

    companion object {
        private const val PREVIEW_TTL_MS = 10 * 60 * 1000L
        private const val MAX_CACHED_PREVIEWS = 2
    }
}
