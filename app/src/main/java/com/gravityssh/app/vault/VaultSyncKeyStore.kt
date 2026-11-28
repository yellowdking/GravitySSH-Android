package com.gravityssh.app.vault

import com.gravityssh.app.model.SeenMsgRecord
import com.yellowdking.synctool.crypto.KeysAndAuth
import com.yellowdking.synctool.envelope.CachedManifestEntry
import com.yellowdking.synctool.envelope.DEFAULT_SEEN_MSG_MIN_ENTRIES
import com.yellowdking.synctool.envelope.DEFAULT_SEEN_MSG_RETENTION_DAYS
import com.yellowdking.synctool.envelope.DeviceCard
import com.yellowdking.synctool.envelope.KeyStore
import com.yellowdking.synctool.envelope.LocalMetaCacheCodec
import com.yellowdking.synctool.envelope.RelayProfile
import com.yellowdking.synctool.envelope.SyncUri
import com.yellowdking.synctool.envelope.TransferCheckpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Vault-backed `synctool-v1` [KeyStore] persisting identity seed, custom relay URI, paired devices,
 * 2-phase pending deposit-token revocations, and anti-replay message watermarks inside the
 * AndroidKeyStore/MasterPassword-encrypted `vault.gsv` ([VaultData.syncState]).
 *
 * Ephemeral `Manifest` previews and chunk `TransferCheckpoint`s live strictly in volatile RAM
 * sealed with HKDF-derived `localMetaKey` (AES-256-GCM) and are wiped immediately on vault lock.
 */
class VaultSyncKeyStore(
    private val vaultRepository: VaultRepository
) : KeyStore {

    private val cacheLock = Any()
    private val mutex = Mutex()
    private var cachedLocalMetaKey: ByteArray? = null
    private val sealedManifestCache = LinkedHashMap<String, String>()
    private val sealedCheckpoints = LinkedHashMap<String, String>()

    fun clearVolatileCaches() {
        synchronized(cacheLock) {
            KeysAndAuth.zeroBytes(cachedLocalMetaKey)
            cachedLocalMetaKey = null
            sealedManifestCache.clear()
            sealedCheckpoints.clear()
        }
    }

    private fun decodeValidSeedOrNull(seedB64u: String): ByteArray? {
        if (seedB64u.isBlank()) return null
        val decoded = runCatching { KeysAndAuth.b64uDecode(seedB64u.trim()) }.getOrNull() ?: return null
        if (decoded.size != KeysAndAuth.MASTER_SEED_SIZE) {
            KeysAndAuth.zeroBytes(decoded)
            return null
        }
        return decoded
    }

    private fun deriveAndCacheMetaKey(seed32: ByteArray): ByteArray {
        val dk = KeysAndAuth.deriveDeviceKeys(seed32)
        val meta = dk.localMetaKey.copyOf()
        dk.zero()
        synchronized(cacheLock) {
            KeysAndAuth.zeroBytes(cachedLocalMetaKey)
            cachedLocalMetaKey = meta.copyOf()
        }
        return meta
    }

    private fun ensureMetaKey(): ByteArray {
        synchronized(cacheLock) {
            cachedLocalMetaKey?.let { return it.copyOf() }
        }
        val seed = loadOrCreateMasterSeedSync()
        return try {
            deriveAndCacheMetaKey(seed)
        } finally {
            KeysAndAuth.zeroBytes(seed)
        }
    }

    private fun loadOrCreateMasterSeedSync(): ByteArray {
        val snap = vaultRepository.readSyncState()
        decodeValidSeedOrNull(snap.masterSeedB64u)?.let { existing ->
            synchronized(cacheLock) {
                if (cachedLocalMetaKey == null) {
                    val meta = deriveAndCacheMetaKey(existing)
                    KeysAndAuth.zeroBytes(meta)
                }
            }
            return existing
        }

        val generated = KeysAndAuth.generateMasterSeed()
        val chosen = try {
            vaultRepository.mutateSyncState { cur ->
                val raced = decodeValidSeedOrNull(cur.masterSeedB64u)
                if (raced != null) {
                    cur to raced
                } else {
                    val encoded = KeysAndAuth.b64uEncode(generated)
                    cur.copy(masterSeedB64u = encoded) to generated.copyOf()
                }
            }
        } finally {
            KeysAndAuth.zeroBytes(generated)
        }
        val meta = deriveAndCacheMetaKey(chosen)
        KeysAndAuth.zeroBytes(meta)
        return chosen
    }

    override suspend fun loadOrCreateMasterSeed(): ByteArray = mutex.withLock {
        loadOrCreateMasterSeedSync()
    }

    override suspend fun getLocalLabel(): String? = mutex.withLock {
        vaultRepository.readSyncState().localDeviceLabel.trim().takeIf { it.isNotEmpty() }
    }

    override suspend fun setLocalLabel(label: String) = mutex.withLock {
        val clean = label.trim().take(64)
        vaultRepository.updateSyncState { it.copy(localDeviceLabel = clean) }
        Unit
    }

    override suspend fun getRelayProfile(relayIdOrAlias: String): RelayProfile? = mutex.withLock {
        val custom = getDefaultRelayProfileLocked() ?: return@withLock null
        if (custom.relayId == relayIdOrAlias ||
            (custom.alias.isNotEmpty() && custom.alias.equals(relayIdOrAlias, ignoreCase = true))
        ) {
            custom
        } else {
            null
        }
    }

    override suspend fun getDefaultRelayProfile(): RelayProfile? = mutex.withLock {
        getDefaultRelayProfileLocked()
    }

    override suspend fun listRelayProfiles(): List<RelayProfile> = mutex.withLock {
        listOfNotNull(getDefaultRelayProfileLocked())
    }

    override suspend fun saveRelayProfile(profile: RelayProfile) = mutex.withLock {
        val uri = SyncUri.formatRelayUri(profile)
        vaultRepository.updateSyncState { it.copy(customRelayUri = uri) }
        Unit
    }

    private fun getDefaultRelayProfileLocked(): RelayProfile? {
        val raw = vaultRepository.readSyncState().customRelayUri.trim()
        if (raw.isEmpty()) return null
        return runCatching { SyncUri.parseRelayUri(raw).copy(isDefault = true) }.getOrNull()
    }

    override suspend fun listDevices(): List<DeviceCard> = mutex.withLock {
        vaultRepository.readSyncState().pairedDevices
            .filter { !it.revoked }
            .sortedBy { it.pairedAt }
    }

    override suspend fun listAllDevices(): List<DeviceCard> = mutex.withLock {
        vaultRepository.readSyncState().pairedDevices.sortedBy { it.pairedAt }
    }

    override suspend fun getDevice(deviceId: String): DeviceCard? = mutex.withLock {
        val cleanId = deviceId.trim().lowercase()
        vaultRepository.readSyncState().pairedDevices.firstOrNull { it.deviceId.lowercase() == cleanId }
    }

    override suspend fun putDevice(card: DeviceCard) = mutex.withLock {
        val cleanId = card.deviceId.trim().lowercase()
        val normalized = card.copy(deviceId = cleanId)
        vaultRepository.updateSyncState { cur ->
            val next = cur.pairedDevices.toMutableList()
            val idx = next.indexOfFirst { it.deviceId.lowercase() == cleanId }
            if (idx >= 0) {
                next[idx] = normalized
            } else {
                next.add(normalized)
            }
            cur.copy(pairedDevices = next)
        }
        Unit
    }

    override suspend fun deleteDevice(deviceId: String) = mutex.withLock {
        val cleanId = deviceId.trim().lowercase()
        vaultRepository.updateSyncState { cur ->
            if (cur.pairedDevices.none { it.deviceId.lowercase() == cleanId }) {
                cur
            } else {
                cur.copy(pairedDevices = cur.pairedDevices.filterNot { it.deviceId.lowercase() == cleanId })
            }
        }
        Unit
    }

    override suspend fun addPendingTokenDelete(tokenId: String) = mutex.withLock {
        val clean = tokenId.trim()
        if (clean.isEmpty()) return@withLock
        vaultRepository.updateSyncState { cur ->
            if (clean in cur.pendingTokenDeletes) {
                cur
            } else {
                cur.copy(pendingTokenDeletes = cur.pendingTokenDeletes + clean)
            }
        }
        Unit
    }

    override suspend fun listPendingTokenDeletes(): List<String> = mutex.withLock {
        vaultRepository.readSyncState().pendingTokenDeletes
    }

    override suspend fun removePendingTokenDelete(tokenId: String) = mutex.withLock {
        val clean = tokenId.trim()
        if (clean.isEmpty()) return@withLock
        vaultRepository.updateSyncState { cur ->
            if (clean !in cur.pendingTokenDeletes) {
                cur
            } else {
                cur.copy(pendingTokenDeletes = cur.pendingTokenDeletes.filterNot { it == clean })
            }
        }
        Unit
    }

    override suspend fun hasSeenMsgId(msgId: String): Boolean = mutex.withLock {
        val clean = msgId.trim().lowercase()
        vaultRepository.readSyncState().seenMsgIds.any { it.msgId == clean }
    }

    override suspend fun markSeenMsgId(msgId: String, createdAt: Long, seenAt: Long) = mutex.withLock {
        val clean = msgId.trim().lowercase()
        if (clean.isEmpty()) return@withLock
        vaultRepository.updateSyncState { cur ->
            val map = LinkedHashMap<String, SeenMsgRecord>(cur.seenMsgIds.size + 1)
            for (rec in cur.seenMsgIds) {
                if (rec.msgId.isNotEmpty()) {
                    map[rec.msgId.lowercase()] = rec
                }
            }
            map[clean] = SeenMsgRecord(msgId = clean, createdAt = createdAt, seenAt = seenAt)

            var nextWatermark = cur.replayWatermarkTs
            if (map.size > DEFAULT_SEEN_MSG_MIN_ENTRIES) {
                val nowSec = System.currentTimeMillis() / 1000L
                val cutoff = nowSec - DEFAULT_SEEN_MSG_RETENTION_DAYS * 86_400L
                val sorted = map.values.sortedBy { it.seenAt }
                for (entry in sorted) {
                    if (map.size <= DEFAULT_SEEN_MSG_MIN_ENTRIES) break
                    if (entry.seenAt < cutoff) {
                        map.remove(entry.msgId)
                        if (entry.createdAt > nextWatermark) {
                            nextWatermark = entry.createdAt
                        }
                    }
                }
            }
            cur.copy(
                seenMsgIds = map.values.toList(),
                replayWatermarkTs = nextWatermark
            )
        }
        Unit
    }

    override suspend fun getReplayWatermark(): Long = mutex.withLock {
        vaultRepository.readSyncState().replayWatermarkTs
    }

    override suspend fun getCachedManifest(itemId: String): CachedManifestEntry? = mutex.withLock {
        val sealed = synchronized(cacheLock) { sealedManifestCache[itemId] } ?: return@withLock null
        val metaKey = ensureMetaKey()
        try {
            val opened = LocalMetaCacheCodec.openCachedManifest(metaKey, itemId, sealed)
            if (opened == null) {
                synchronized(cacheLock) { sealedManifestCache.remove(itemId) }
            }
            opened
        } finally {
            KeysAndAuth.zeroBytes(metaKey)
        }
    }

    override suspend fun putCachedManifest(entry: CachedManifestEntry) = mutex.withLock {
        val metaKey = ensureMetaKey()
        try {
            val sealed = LocalMetaCacheCodec.sealCachedManifest(metaKey, entry)
            synchronized(cacheLock) {
                sealedManifestCache[entry.itemId] = sealed
            }
        } finally {
            KeysAndAuth.zeroBytes(metaKey)
        }
    }

    override suspend fun deleteCachedManifest(itemId: String) = mutex.withLock {
        synchronized(cacheLock) {
            sealedManifestCache.remove(itemId)
        }
        Unit
    }

    override suspend fun pruneCachedManifests(activeItemIds: Set<String>) = mutex.withLock {
        synchronized(cacheLock) {
            val stale = sealedManifestCache.keys.filter { it !in activeItemIds }
            for (k in stale) {
                sealedManifestCache.remove(k)
            }
        }
    }

    override suspend fun getCheckpoint(direction: String, msgId: String, partId: Int): TransferCheckpoint? = mutex.withLock {
        val key = TransferCheckpoint.buildKey(direction, msgId, partId)
        val sealed = synchronized(cacheLock) { sealedCheckpoints[key] } ?: return@withLock null
        val metaKey = ensureMetaKey()
        try {
            val cp = LocalMetaCacheCodec.openCheckpoint(metaKey, key, sealed)
            val nowSec = System.currentTimeMillis() / 1000L
            if (cp == null || (cp.expiresAt > 0L && cp.expiresAt < nowSec)) {
                synchronized(cacheLock) { sealedCheckpoints.remove(key) }
                null
            } else {
                cp
            }
        } finally {
            KeysAndAuth.zeroBytes(metaKey)
        }
    }

    override suspend fun putCheckpoint(cp: TransferCheckpoint) = mutex.withLock {
        val key = TransferCheckpoint.buildKey(cp.direction, cp.msgId, cp.partId)
        val metaKey = ensureMetaKey()
        try {
            val sealed = LocalMetaCacheCodec.sealCheckpoint(metaKey, cp.copy(key = key))
            synchronized(cacheLock) {
                sealedCheckpoints[key] = sealed
            }
        } finally {
            KeysAndAuth.zeroBytes(metaKey)
        }
    }

    override suspend fun deleteCheckpoint(direction: String, msgId: String, partId: Int) = mutex.withLock {
        val key = TransferCheckpoint.buildKey(direction, msgId, partId)
        synchronized(cacheLock) {
            sealedCheckpoints.remove(key)
        }
        Unit
    }
}
