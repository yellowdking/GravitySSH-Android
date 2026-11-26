package com.gravityssh.app.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
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
    private val storageFile: File
) : HostKeyVerifier {

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val entries = LinkedHashMap<String, KnownHostEntry>()

    init {
        load()
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = synchronized(lock) {
        val hostKey = keyFor(hostname, port)
        val fp = SecurityUtils.getFingerprint(key)
        val existing = entries[hostKey]
        if (existing == null) {
            entries[hostKey] = KnownHostEntry(
                hostPort = hostKey,
                host = hostname,
                port = port,
                algorithm = key.algorithm,
                fingerprint = fp,
                firstSeenAt = Instant.now().toString()
            )
            saveLocked()
            return true
        }
        return existing.fingerprint == fp
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = synchronized(lock) {
        val entry = entries[keyFor(hostname, port)] ?: return emptyList()
        listOf(entry.algorithm)
    }

    fun listKnownHosts(): List<KnownHostEntry> = synchronized(lock) {
        entries.values.toList()
    }

    fun clearHost(host: String, port: Int): Boolean = synchronized(lock) {
        val removed = entries.remove(keyFor(host, port)) != null
        if (removed) saveLocked()
        removed
    }

    fun clearHostByKey(hostPort: String): Boolean = synchronized(lock) {
        val removed = entries.remove(hostPort) != null
        if (removed) saveLocked()
        removed
    }

    fun clearAll(): Int = synchronized(lock) {
        val count = entries.size
        entries.clear()
        saveLocked()
        count
    }

    private fun keyFor(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    private fun load() = synchronized(lock) {
        if (!storageFile.exists()) return
        runCatching {
            val list = json.decodeFromString<List<KnownHostEntry>>(storageFile.readText(Charsets.UTF_8))
            entries.clear()
            list.forEach { entries[it.hostPort] = it }
        }
    }

    private fun saveLocked() {
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(entries.values.toList()), Charsets.UTF_8)
    }
}
