package com.gravityssh.app.vault

import android.util.Log
import com.gravityssh.app.model.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.PublicKey
import java.time.Instant

@Serializable
data class KnownHostEntry(
    val hostPort: String,
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val firstSeenAt: String
)

class KnownHostsManager(
    private val storageFile: File,
    autoLoad: Boolean = true
) : HostKeyVerifier {

    private val lock = Any()
    private val json = AppJson.json
    private val entries = LinkedHashMap<String, KnownHostEntry>()
    private var loaded = false

    private val _knownHosts = MutableStateFlow<List<KnownHostEntry>>(emptyList())
    val knownHosts: StateFlow<List<KnownHostEntry>> = _knownHosts.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        if (autoLoad) {
            load()
        }
    }

    fun load() {
        synchronized(lock) {
            if (!loaded) {
                loadLocked()
            }
        }
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val hostKey = keyFor(hostname, port)
        val fp = SecurityUtils.getFingerprint(key)
        val existing = entries[hostKey]
        if (existing == null) {
            val keyType = KeyType.fromKey(key)
            val sshAlgo = if (keyType != KeyType.UNKNOWN) keyType.toString() else key.algorithm
            entries[hostKey] = KnownHostEntry(
                hostPort = hostKey,
                host = hostname,
                port = port,
                algorithm = sshAlgo,
                fingerprint = fp,
                firstSeenAt = Instant.now().toString()
            )
            saveLocked()
            emitLocked()
            return true
        }
        return existing.fingerprint == fp
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        val entry = entries[keyFor(hostname, port)] ?: return emptyList()
        // Only advertise valid RFC 4253 SSH key type names (e.g. ssh-ed25519, ssh-rsa, ecdsa-sha2-*)
        if (entry.algorithm.contains('-')) listOf(entry.algorithm) else emptyList()
    }

    fun clearHost(host: String, port: Int): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val removed = entries.remove(keyFor(host, port)) != null
        if (removed) {
            saveLocked()
            emitLocked()
        }
        removed
    }

    fun clearHostByKey(hostPort: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val removed = entries.remove(hostPort) != null
        if (removed) {
            saveLocked()
            emitLocked()
        }
        removed
    }

    fun clearAll(): Int = synchronized(lock) {
        ensureLoadedLocked()
        val count = entries.size
        entries.clear()
        saveLocked()
        emitLocked()
        count
    }

    private fun keyFor(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    private fun ensureLoadedLocked() {
        if (!loaded) {
            loadLocked()
        }
    }

    private fun loadLocked() {
        loaded = true
        if (!storageFile.exists()) {
            emitLocked()
            return
        }
        try {
            val list = json.decodeFromString<List<KnownHostEntry>>(storageFile.readText(Charsets.UTF_8))
            entries.clear()
            list.forEach { entries[it.hostPort] = it }
            _lastError.value = null
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Corrupt known_hosts file, backing up", e) }
            val backup = File(storageFile.parentFile ?: File("."), "${storageFile.name}.corrupt")
            runCatching {
                Files.move(storageFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            entries.clear()
            _lastError.value = "主机指纹缓存已损坏，已备份至 ${backup.name}：${e.message ?: e.javaClass.simpleName}"
        }
        emitLocked()
    }

    private fun saveLocked() {
        val bytes = json.encodeToString(entries.values.toList()).toByteArray(Charsets.UTF_8)
        AtomicFileOps.writeBytesAtomic(storageFile, bytes)
    }

    private fun emitLocked() {
        _knownHosts.value = entries.values.toList()
    }

    companion object {
        private const val TAG = "KnownHostsManager"
    }
}
