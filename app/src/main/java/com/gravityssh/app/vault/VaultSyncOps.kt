package com.gravityssh.app.vault

import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.JumpHostConfig
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.ProxyConfig
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.VaultData
import java.time.Instant
import java.util.UUID

object VaultSyncOps {

    private const val SUPPORTED_SCHEMA_VERSION = 1
    private val VALID_AUTH_TYPES = setOf(AuthType.PASSWORD, AuthType.KEY, AuthType.BOTH)
    private val VALID_PROXY_TYPES = setOf(ProxyType.NONE, ProxyType.SOCKS5, ProxyType.HTTP)
    private val VALID_JUMP_MODES = setOf(JumpMode.NONE, JumpMode.SERVER, JumpMode.CUSTOM)
    private val validPresetIds: Set<String> by lazy {
        BuiltinOpsPresets.all().mapNotNull { it.presetId.trim().takeIf { id -> id.isNotEmpty() } }.toSet()
    }

    fun exportBundle(data: VaultData, sourceDevice: String): SyncBundle {
        val now = Instant.now().toString()
        val normServers = data.servers.map { s ->
            s.copy(
                createdAt = s.createdAt.ifBlank { now },
                updatedAt = s.updatedAt.ifBlank { now },
                lastConnectedAt = s.lastConnectedAt?.takeIf { it.isNotBlank() }
            )
        }
        val normKeys = data.sshKeys.map { k -> k.copy(createdAt = k.createdAt.ifBlank { now }) }
        val normShortcuts = data.shortcuts.map { sc ->
            sc.copy(createdAt = sc.createdAt.ifBlank { now }, updatedAt = sc.updatedAt.ifBlank { now })
        }
        return SyncBundle(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            exportedAt = now,
            sourcePlatform = "android",
            sourceDevice = sourceDevice.ifBlank { "Android" },
            servers = normServers,
            sshKeys = normKeys,
            shortcuts = normShortcuts,
            templates = data.templates
        )
    }

    fun applyBundle(
        current: VaultData,
        bundle: SyncBundle,
        mode: SyncImportMode
    ): Pair<VaultData, SyncImportSummary> {
        if (bundle.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "不支持的同步协议版本（schemaVersion=${bundle.schemaVersion}），仅支持版本 $SUPPORTED_SCHEMA_VERSION，请升级应用后重试"
            )
        }

        val now = Instant.now().toString()
        val cleanKeys = bundle.sshKeys.mapNotNull { sanitizeKey(it, now) }
        val cleanServers = bundle.servers.mapNotNull { sanitizeServer(it, now) }
        val cleanShortcuts = bundle.shortcuts.mapNotNull { sanitizeShortcut(it, now) }
        val cleanTemplates = sanitizeTemplates(bundle.templates)

        if (mode == SyncImportMode.OVERWRITE) {
            val overwriteKeys = deduplicateKeys(cleanKeys.map { it.second })
            val overwriteServers = deduplicateServers(cleanServers.map { it.second }).toMutableList()
            breakJumpCycles(overwriteServers, targetIds = null)
            val next = current.copy(
                servers = overwriteServers,
                sshKeys = overwriteKeys,
                shortcuts = cleanShortcuts,
                templates = cleanTemplates,
                syncState = current.syncState
            )
            return next to SyncImportSummary(
                mode = mode,
                serversAdded = overwriteServers.size,
                serversUpdated = 0,
                keysAdded = overwriteKeys.size,
                keysUpdated = 0,
                shortcutsAdded = cleanShortcuts.size,
                shortcutsUpdated = 0,
                templatesAdded = cleanTemplates.size,
                templatesUpdated = 0
            )
        }

        // 1. Merge SSH keys first and record incomingId -> localId mappings
        val keyIdMap = HashMap<String, String>()
        val outKeys = current.sshKeys.toMutableList()
        var keysAdded = 0
        var keysUpdated = 0
        for ((origId, inc) in cleanKeys) {
            val idx = outKeys.indexOfFirst { cur ->
                (origId.isNotBlank() && cur.id == origId) ||
                    (inc.fingerprint.isNotBlank() && cur.fingerprint.equals(inc.fingerprint, ignoreCase = true))
            }
            if (idx >= 0) {
                val cur = outKeys[idx]
                val finalId = cur.id.ifBlank { inc.id }
                if (origId.isNotBlank() && origId != finalId) keyIdMap[origId] = finalId
                if (inc.id != finalId) keyIdMap[inc.id] = finalId
                outKeys[idx] = inc.copy(
                    id = finalId,
                    publicKey = inc.publicKey.ifBlank { cur.publicKey },
                    fingerprint = inc.fingerprint.ifBlank { cur.fingerprint },
                    privateKeyPem = inc.privateKeyPem.ifBlank { cur.privateKeyPem },
                    passphrase = inc.passphrase.ifEmpty { cur.passphrase },
                    createdAt = cur.createdAt.ifBlank { inc.createdAt }
                )
                keysUpdated++
            } else {
                outKeys.add(inc)
                keysAdded++
            }
        }

        // 2. Merge servers (pass 1: remap sshKeyId & jumpHost.sshKeyId, build serverIdMap)
        val serverIdMap = HashMap<String, String>()
        val touchedServerIds = HashSet<String>()
        val outServers = current.servers.toMutableList()
        var serversAdded = 0
        var serversUpdated = 0
        for ((origId, rawServer) in cleanServers) {
            val mappedKeyId = rawServer.sshKeyId.takeIf { it.isNotBlank() }?.let { keyIdMap[it] ?: it }.orEmpty()
            val mappedJumpKeyId = rawServer.jumpHost.sshKeyId.takeIf { it.isNotBlank() }?.let { keyIdMap[it] ?: it }.orEmpty()
            val inc = rawServer.copy(
                sshKeyId = mappedKeyId,
                jumpHost = rawServer.jumpHost.copy(sshKeyId = mappedJumpKeyId)
            )

            val idx = outServers.indexOfFirst { cur ->
                (origId.isNotBlank() && cur.id == origId) ||
                    (cur.host.equals(inc.host, ignoreCase = true) && cur.port == inc.port && cur.username == inc.username)
            }
            if (idx >= 0) {
                val cur = outServers[idx]
                val finalId = cur.id.ifBlank { inc.id }
                if (origId.isNotBlank() && origId != finalId) serverIdMap[origId] = finalId
                if (inc.id != finalId) serverIdMap[inc.id] = finalId
                val sameProxy = inc.proxy.type != ProxyType.NONE &&
                    cur.proxy.type.equals(inc.proxy.type, ignoreCase = true) &&
                    cur.proxy.host.trim().equals(inc.proxy.host.trim(), ignoreCase = true) &&
                    cur.proxy.port == inc.proxy.port &&
                    cur.proxy.username.trim() == inc.proxy.username.trim()
                val sameCustomJump = inc.jumpHost.mode == JumpMode.CUSTOM &&
                    cur.jumpHost.mode.equals(JumpMode.CUSTOM, ignoreCase = true) &&
                    cur.jumpHost.host.trim().equals(inc.jumpHost.host.trim(), ignoreCase = true) &&
                    cur.jumpHost.port == inc.jumpHost.port &&
                    cur.jumpHost.username.trim() == inc.jumpHost.username.trim()
                val sameMainEndpoint = cur.host.trim().equals(inc.host.trim(), ignoreCase = true) &&
                    cur.port == inc.port &&
                    cur.username.trim() == inc.username.trim()
                val mergedSshKeyId = if (inc.authType == AuthType.PASSWORD) {
                    inc.sshKeyId
                } else {
                    inc.sshKeyId.ifBlank { cur.sshKeyId }
                }
                val mergedJumpKeyId = if (sameCustomJump && inc.jumpHost.authType != AuthType.PASSWORD) {
                    inc.jumpHost.sshKeyId.ifBlank { cur.jumpHost.sshKeyId }
                } else {
                    inc.jumpHost.sshKeyId
                }
                outServers[idx] = inc.copy(
                    id = finalId,
                    password = if (sameMainEndpoint) inc.password.ifEmpty { cur.password } else inc.password,
                    sshKeyId = mergedSshKeyId,
                    proxy = inc.proxy.copy(
                        password = if (sameProxy) inc.proxy.password.ifEmpty { cur.proxy.password } else inc.proxy.password
                    ),
                    jumpHost = inc.jumpHost.copy(
                        password = if (sameCustomJump) inc.jumpHost.password.ifEmpty { cur.jumpHost.password } else inc.jumpHost.password,
                        sshKeyId = mergedJumpKeyId
                    ),
                    createdAt = cur.createdAt.ifBlank { inc.createdAt },
                    lastConnectedAt = cur.lastConnectedAt ?: inc.lastConnectedAt
                )
                touchedServerIds.add(finalId)
                serversUpdated++
            } else {
                outServers.add(inc)
                touchedServerIds.add(inc.id)
                serversAdded++
            }
        }

        // 2b. Pass 2: remap jumpHost.serverId on all incoming/merged servers regardless of bundle ordering
        if (serverIdMap.isNotEmpty()) {
            for (i in outServers.indices) {
                val s = outServers[i]
                if (s.id !in touchedServerIds) continue
                val jhServerId = s.jumpHost.serverId
                val mappedJumpServerId = serverIdMap[jhServerId]
                if (mappedJumpServerId != null) {
                    outServers[i] = s.copy(jumpHost = s.jumpHost.copy(serverId = mappedJumpServerId))
                }
            }
        }
        // 2c. Break any self-reference or multi-hop jump cycles introduced by incoming/merged servers
        breakJumpCycles(outServers, targetIds = touchedServerIds)

        // 3. Merge shortcuts
        val outShortcuts = current.shortcuts.toMutableList()
        var shortcutsAdded = 0
        var shortcutsUpdated = 0
        for (inc in cleanShortcuts) {
            val idx = outShortcuts.indexOfFirst { cur ->
                cur.id == inc.id || (cur.name == inc.name && cur.command == inc.command)
            }
            if (idx >= 0) {
                val cur = outShortcuts[idx]
                outShortcuts[idx] = inc.copy(
                    id = cur.id.ifBlank { inc.id },
                    useCount = maxOf(cur.useCount, inc.useCount),
                    createdAt = cur.createdAt.ifBlank { inc.createdAt }
                )
                shortcutsUpdated++
            } else {
                outShortcuts.add(inc)
                shortcutsAdded++
            }
        }

        // 4. Merge templates (custom templates can never match or overwrite built-in presets)
        val outTemplates = current.templates.toMutableList()
        var templatesAdded = 0
        var templatesUpdated = 0
        for (inc in cleanTemplates) {
            var idx = -1
            if (inc.presetId.isNotBlank()) {
                idx = outTemplates.indexOfFirst { cur ->
                    cur.presetId == inc.presetId || cur.id == inc.presetId
                }
            } else {
                idx = outTemplates.indexOfFirst { cur ->
                    cur.presetId.isBlank() && cur.id == inc.id
                }
                if (idx < 0) {
                    val incName = inc.name.trim()
                    idx = outTemplates.indexOfFirst { cur ->
                        cur.presetId.isBlank() && cur.name.trim() == incName
                    }
                }
            }
            if (idx >= 0) {
                val cur = outTemplates[idx]
                outTemplates[idx] = inc.copy(
                    id = cur.id.ifBlank { inc.id },
                    presetId = cur.presetId.ifBlank { inc.presetId }
                )
                templatesUpdated++
            } else {
                val safeInc = if (outTemplates.any { it.id == inc.id }) {
                    inc.copy(id = UUID.randomUUID().toString())
                } else {
                    inc
                }
                outTemplates.add(safeInc)
                templatesAdded++
            }
        }

        val next = current.copy(
            servers = outServers,
            sshKeys = outKeys,
            shortcuts = outShortcuts,
            templates = outTemplates,
            syncState = current.syncState
        )
        return next to SyncImportSummary(
            mode = mode,
            serversAdded = serversAdded,
            serversUpdated = serversUpdated,
            keysAdded = keysAdded,
            keysUpdated = keysUpdated,
            shortcutsAdded = shortcutsAdded,
            shortcutsUpdated = shortcutsUpdated,
            templatesAdded = templatesAdded,
            templatesUpdated = templatesUpdated
        )
    }

    private fun sanitizeKey(raw: SSHKeyRecord, now: String): Pair<String, SSHKeyRecord>? {
        val origId = raw.id.trim()
        val fingerprint = raw.fingerprint.trim()
        val publicKey = raw.publicKey.trim().takeUnless { it.contains('\u0000') || it.contains('\r') || it.contains('\n') }.orEmpty()
        val privateKeyPem = raw.privateKeyPem.trim()
        if (origId.isBlank() && fingerprint.isBlank() && publicKey.isBlank() && privateKeyPem.isBlank()) {
            return null
        }
        val id = origId.ifBlank { UUID.randomUUID().toString() }
        val clean = raw.copy(
            id = id,
            name = raw.name.trim().ifBlank { "SSH Key" },
            keyType = raw.keyType.trim().ifBlank { "ed25519" },
            fingerprint = fingerprint,
            publicKey = publicKey,
            privateKeyPem = privateKeyPem,
            comment = raw.comment.trim(),
            createdAt = raw.createdAt.trim().ifBlank { now }
        )
        return origId to clean
    }

    private fun sanitizeServer(raw: ServerConfig, now: String): Pair<String, ServerConfig>? {
        val host = raw.host.trim()
        if (host.isBlank()) return null

        val origId = raw.id.trim()
        val id = origId.ifBlank { UUID.randomUUID().toString() }
        val username = raw.username.trim().ifBlank { "root" }
        val authType = raw.authType.trim().lowercase().takeIf { it in VALID_AUTH_TYPES } ?: AuthType.PASSWORD

        val proxyType = raw.proxy.type.trim().lowercase().takeIf { it in VALID_PROXY_TYPES } ?: ProxyType.NONE
        val proxyPort = when (proxyType) {
            ProxyType.SOCKS5 -> if (raw.proxy.port <= 0) 1080 else raw.proxy.port.coerceIn(1, 65535)
            ProxyType.HTTP -> if (raw.proxy.port <= 0) 8080 else raw.proxy.port.coerceIn(1, 65535)
            else -> 0
        }
        val cleanProxy = ProxyConfig(
            type = proxyType,
            host = raw.proxy.host.trim(),
            port = proxyPort,
            username = raw.proxy.username.trim(),
            password = raw.proxy.password
        )

        val rawJumpMode = raw.jumpHost.mode.trim().lowercase().takeIf { it in VALID_JUMP_MODES } ?: JumpMode.NONE
        val rawJumpServerId = raw.jumpHost.serverId.trim()
        val jumpMode = if (rawJumpMode == JumpMode.SERVER && (rawJumpServerId.isEmpty() || rawJumpServerId == id)) {
            JumpMode.NONE
        } else {
            rawJumpMode
        }
        val jumpServerId = if (jumpMode == JumpMode.SERVER) rawJumpServerId else ""
        val jumpAuthType = raw.jumpHost.authType.trim().lowercase().takeIf { it in VALID_AUTH_TYPES } ?: AuthType.PASSWORD
        val jumpPort = if (raw.jumpHost.port <= 0) 22 else raw.jumpHost.port.coerceIn(1, 65535)
        val cleanJump = JumpHostConfig(
            mode = jumpMode,
            serverId = jumpServerId,
            host = raw.jumpHost.host.trim(),
            port = jumpPort,
            username = raw.jumpHost.username.trim(),
            authType = jumpAuthType,
            password = raw.jumpHost.password,
            sshKeyId = raw.jumpHost.sshKeyId.trim(),
            useMainProxy = raw.jumpHost.useMainProxy
        )

        val cleanPort = if (raw.port <= 0) 22 else raw.port.coerceIn(1, 65535)
        val cleanTimeout = if (raw.timeoutSeconds <= 0) 15 else raw.timeoutSeconds.coerceIn(3, 120)
        val cleanKeepAlive = raw.keepAliveSeconds.coerceIn(0, 600)

        val clean = raw.copy(
            id = id,
            name = raw.name.trim().ifBlank { "$username@$host" },
            group = raw.group.trim().ifBlank { "默认分组" },
            color = raw.color.trim().ifBlank { "#6366F1" },
            host = host,
            port = cleanPort,
            username = username,
            authType = authType,
            sshKeyId = raw.sshKeyId.trim(),
            proxy = cleanProxy,
            jumpHost = cleanJump,
            timeoutSeconds = cleanTimeout,
            keepAliveSeconds = cleanKeepAlive,
            defaultRemotePath = raw.defaultRemotePath.trim(),
            startupCommand = raw.startupCommand.trim(),
            notes = raw.notes.trim(),
            createdAt = raw.createdAt.trim().ifBlank { now },
            updatedAt = raw.updatedAt.trim().ifBlank { now },
            lastConnectedAt = raw.lastConnectedAt?.trim()?.takeIf { it.isNotEmpty() }
        )
        return origId to clean
    }

    private fun deduplicateKeys(keys: List<SSHKeyRecord>): List<SSHKeyRecord> {
        val seen = HashSet<String>(keys.size)
        return keys.map { k ->
            if (seen.add(k.id)) k else k.copy(id = UUID.randomUUID().toString())
        }
    }

    private fun deduplicateServers(servers: List<ServerConfig>): List<ServerConfig> {
        val seen = HashSet<String>(servers.size)
        return servers.map { s ->
            if (seen.add(s.id)) s else s.copy(id = UUID.randomUUID().toString())
        }
    }

    internal fun hasJumpCycle(servers: List<ServerConfig>, selfId: String, jumpServerId: String): Boolean {
        val start = jumpServerId.trim()
        if (start.isEmpty()) return false
        val byId = servers.associateBy { it.id }
        val visited = HashSet<String>()
        val cleanSelf = selfId.trim()
        if (cleanSelf.isNotEmpty()) visited.add(cleanSelf)
        var cur = start
        while (cur.isNotEmpty()) {
            if (!visited.add(cur)) return true
            val next = byId[cur] ?: return false
            if (next.jumpHost.mode != JumpMode.SERVER) return false
            cur = next.jumpHost.serverId.trim()
        }
        return false
    }

    private fun breakJumpCycles(servers: MutableList<ServerConfig>, targetIds: Set<String>?) {
        for (i in servers.indices) {
            val srv = servers[i]
            if (targetIds != null && srv.id !in targetIds) continue
            if (srv.jumpHost.mode != JumpMode.SERVER) continue
            val jumpId = srv.jumpHost.serverId.trim()
            if (jumpId.isEmpty() || jumpId == srv.id || hasJumpCycle(servers, srv.id, jumpId)) {
                servers[i] = srv.copy(
                    jumpHost = srv.jumpHost.copy(mode = JumpMode.NONE, serverId = "")
                )
            }
        }
    }

    private fun sanitizeShortcut(raw: CommandShortcut, now: String): CommandShortcut? {
        val name = raw.name.trim()
        val command = raw.command.trim()
        if (command.isBlank()) return null
        return raw.copy(
            id = raw.id.trim().ifBlank { UUID.randomUUID().toString() },
            name = name.ifBlank { command.take(24) },
            command = command,
            description = raw.description.trim(),
            category = raw.category.trim().ifBlank { "常用" },
            useCount = raw.useCount.coerceAtLeast(0),
            createdAt = raw.createdAt.trim().ifBlank { now },
            updatedAt = raw.updatedAt.trim().ifBlank { now }
        )
    }

    private fun sanitizeTemplates(rawList: List<BuiltinTemplate>): List<BuiltinTemplate> {
        val validPresets = validPresetIds
        val seenPresets = HashSet<String>()
        val seenIds = HashSet<String>()
        val out = ArrayList<BuiltinTemplate>(rawList.size)
        for (raw in rawList) {
            val clean = sanitizeTemplate(raw) ?: continue
            val effectivePresetId = if (clean.presetId.isNotEmpty() &&
                clean.presetId in validPresets &&
                seenPresets.add(clean.presetId)
            ) {
                clean.presetId
            } else {
                ""
            }
            val effectiveId = if (effectivePresetId.isNotEmpty()) {
                effectivePresetId
            } else if (clean.id in validPresets || clean.id in seenIds) {
                UUID.randomUUID().toString()
            } else {
                clean.id
            }
            seenIds.add(effectiveId)
            out.add(clean.copy(id = effectiveId, presetId = effectivePresetId))
        }
        return out
    }

    private fun sanitizeTemplate(raw: BuiltinTemplate): BuiltinTemplate? {
        val name = raw.name.trim()
        val command = raw.command.trim()
        if (command.isBlank()) return null
        return raw.copy(
            id = raw.id.trim().ifBlank { UUID.randomUUID().toString() },
            presetId = raw.presetId.trim(),
            name = name.ifBlank { command.take(24) },
            category = raw.category.trim().ifBlank { "自定义" },
            description = raw.description.trim(),
            safetyNote = raw.safetyNote.trim(),
            command = command
        )
    }
}
