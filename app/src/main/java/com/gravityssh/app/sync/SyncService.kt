package com.gravityssh.app.sync

import android.os.Build
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncSendSession
import com.gravityssh.app.vault.VaultRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SyncService(
    private val vaultRepository: VaultRepository,
    private val relayClient: SyncRelayClient = SyncRelayClient()
) {
    private data class CachedPreview(
        val bundle: SyncBundle,
        val expiresAtMs: Long
    )

    private val previews = ConcurrentHashMap<String, CachedPreview>()

    private fun resolveRelayUrl(overrideUrl: String?): String {
        val candidate = overrideUrl?.trim().orEmpty()
        if (candidate.isNotEmpty()) return candidate
        val saved = vaultRepository.state.value.data.settings.syncRelayUrl.trim()
        return saved.ifEmpty { SyncConfig.DEFAULT_RELAY_URL }
    }

    private fun deviceName(): String {
        val mfg = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(mfg, model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Android" }
    }

    suspend fun startSend(relayUrl: String? = null): SyncSendSession {
        val url = resolveRelayUrl(relayUrl)
        val bundle = vaultRepository.exportSyncBundle(deviceName())
        val code = SyncCrypto.generateSyncCode()
        val encrypted = SyncCrypto.encryptBundle(bundle, code)

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
        pruneExpiredPreviews()
        val url = resolveRelayUrl(relayUrl)
        val canonical = SyncCrypto.canonicalizeCode(codeOrUri)
        val channelId = SyncCrypto.deriveChannelId(canonical)
        val pickup = relayClient.pickup(url, channelId)
        val bundle = SyncCrypto.decryptBundle(pickup.salt, pickup.nonce, pickup.ciphertext, canonical)

        val previewId = UUID.randomUUID().toString()
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
        pruneExpiredPreviews()
        val cached = previews.remove(previewId)
            ?: throw IllegalStateException("同步预览已过期，请重新获取同步码")
        return vaultRepository.importSyncBundle(cached.bundle, mode)
    }

    fun discardPreview(previewId: String) {
        previews.remove(previewId)
    }

    private fun pruneExpiredPreviews() {
        val now = System.currentTimeMillis()
        previews.entries.removeIf { it.value.expiresAtMs < now }
    }

    companion object {
        private const val PREVIEW_TTL_MS = 10 * 60 * 1000L
    }
}
