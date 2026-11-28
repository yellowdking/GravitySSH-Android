package com.gravityssh.app.vault

import android.util.Log
import com.gravityssh.app.model.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
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

    fun clearLastError() {
        synchronized(lock) {
            _lastError.value = null
        }
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        if (!loaded) {
            // Do not silently accept TOFU or overwrite disk when known_hosts could not be read
            return false
        }
        val hostKey = keyFor(hostname, port)
        val fp = SecurityUtils.getFingerprint(key)
        val existing = entries[hostKey]
        if (existing == null) {
            val keyType = KeyType.fromKey(key)
            val sshAlgo = if (keyType != KeyType.UNKNOWN) keyType.toString() else key.algorithm
            val newEntry = KnownHostEntry(
                hostPort = hostKey,
                host = hostname,
                port = port,
                algorithm = sshAlgo,
                fingerprint = fp,
                firstSeenAt = Instant.now().toString()
            )
            val nextEntries = LinkedHashMap(entries).apply { put(hostKey, newEntry) }
            persistSnapshotLocked(nextEntries.values, "保存主机指纹失败")
            entries[hostKey] = newEntry
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
        requireLoadedLocked()
        val key = keyFor(host, port)
        if (!entries.containsKey(key)) return false
        val nextEntries = LinkedHashMap(entries).apply { remove(key) }
        persistSnapshotLocked(nextEntries.values, "清除主机指纹失败")
        entries.remove(key)
        emitLocked()
        true
    }

    fun clearHostByKey(hostPort: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        requireLoadedLocked()
        if (!entries.containsKey(hostPort)) return false
        val nextEntries = LinkedHashMap(entries).apply { remove(hostPort) }
        persistSnapshotLocked(nextEntries.values, "清除主机指纹失败")
        entries.remove(hostPort)
        emitLocked()
        true
    }

    fun clearAll(): Int = synchronized(lock) {
        ensureLoadedLocked()
        requireLoadedLocked()
        val count = entries.size
        if (count == 0) return 0
        persistSnapshotLocked(emptyList(), "清空主机指纹缓存失败")
        entries.clear()
        emitLocked()
        count
    }

    private fun keyFor(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    private fun ensureLoadedLocked() {
        if (!loaded) {
            loadLocked()
        }
    }

    private fun requireLoadedLocked() {
        if (!loaded) {
            throw IOException(_lastError.value ?: "主机指纹缓存未能加载")
        }
    }

    private fun loadLocked() {
        if (!storageFile.exists()) {
            loaded = true
            _lastError.value = null
            emitLocked()
            return
        }
        try {
            val raw = storageFile.readText(Charsets.UTF_8)
            val list = json.decodeFromString<List<KnownHostEntry>>(raw)
            entries.clear()
            list.forEach { entries[it.hostPort] = it }
            loaded = true
            _lastError.value = null
        } catch (e: SerializationException) {
            handleCorruptFileLocked(e)
        } catch (e: IllegalArgumentException) {
            handleCorruptFileLocked(e)
        } catch (e: IOException) {
            // Transient I/O failure: never wipe existing trusted hosts or delete/overwrite known_hosts.json
            runCatching { Log.e(TAG, "Failed to read known_hosts file (preserving on-disk & in-memory state)", e) }
            loaded = false
            _lastError.value = "读取主机指纹缓存失败（已保留现有记录）：${e.message ?: e.javaClass.simpleName}"
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Unexpected error loading known_hosts file", e) }
            loaded = false
            _lastError.value = "加载主机指纹缓存异常（已保留现有记录）：${e.message ?: e.javaClass.simpleName}"
        }
        emitLocked()
    }

    private fun handleCorruptFileLocked(e: Exception) {
        runCatching { Log.e(TAG, "Corrupt known_hosts file, backing up", e) }
        val dir = storageFile.parentFile ?: File(".")
        val prefix = "${storageFile.name}.corrupt."
        var ts = System.currentTimeMillis()
        var backup = File(dir, "$prefix$ts")
        while (backup.exists()) {
            ts++
            backup = File(dir, "$prefix$ts")
        }
        val moved = runCatching {
            Files.move(storageFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            VaultPersistence.pruneCorruptBackups(dir, prefix, MAX_CORRUPT_BACKUPS)
            true
        }.getOrDefault(false)
        // Preserve any already-loaded in-memory entries; if initial load failed and backup succeeded, allow recovery
        loaded = moved || entries.isNotEmpty()
        _lastError.value = if (moved) {
            "主机指纹缓存文件已损坏，已备份至 ${backup.name}：${e.message ?: e.javaClass.simpleName}"
        } else {
            "主机指纹缓存文件已损坏且无法备份：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun persistSnapshotLocked(snapshot: Collection<KnownHostEntry>, errorPrefix: String) {
        try {
            val bytes = json.encodeToString(snapshot.toList()).toByteArray(Charsets.UTF_8)
            AtomicFileOps.writeBytesAtomic(storageFile, bytes)
            _lastError.value = null
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "$errorPrefix: ${e.message}", e) }
            _lastError.value = "$errorPrefix：${e.message ?: e.javaClass.simpleName}"
            throw e
        }
    }

    private fun emitLocked() {
        _knownHosts.value = entries.values.toList()
    }

    companion object {
        private const val TAG = "KnownHostsManager"
        private const val MAX_CORRUPT_BACKUPS = 3
    }
}
