package com.gravityssh.app.vault

import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.AuthType
import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.JumpMode
import com.gravityssh.app.model.PresetLoadResult
import com.gravityssh.app.model.ProxyType
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.VaultData
import com.gravityssh.app.model.VaultLockState
import java.time.Instant
import java.util.UUID

internal object VaultCrudOps {

    const val MIN_TIMEOUT_SECONDS = 3
    const val MAX_TIMEOUT_SECONDS = 120
    const val MIN_KEEPALIVE_SECONDS = 0
    const val MAX_KEEPALIVE_SECONDS = 600
    const val MIN_AUTO_LOCK_MINUTES = 0
    const val MAX_AUTO_LOCK_MINUTES = 1440
    val AUTO_LOCK_PRESET_MINUTES: List<Int> = listOf(0, 1, 5, 15, 30)

    fun sanitizeSettings(settings: AppSettings): AppSettings {
        val clampedAutoLock = settings.autoLockMinutes.coerceIn(MIN_AUTO_LOCK_MINUTES, MAX_AUTO_LOCK_MINUTES)
        return if (clampedAutoLock == settings.autoLockMinutes) {
            settings
        } else {
            settings.copy(autoLockMinutes = clampedAutoLock)
        }
    }

    fun shouldAutoLockAfterBackground(
        lockState: VaultLockState,
        autoLockMinutes: Int,
        backgroundedAtElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        if (!lockState.initialized || lockState.locked) return false
        if (!lockState.hasPassword && !lockState.biometricEnabled) return false
        val clampedMinutes = autoLockMinutes.coerceIn(MIN_AUTO_LOCK_MINUTES, MAX_AUTO_LOCK_MINUTES)
        if (clampedMinutes <= 0) return false
        if (backgroundedAtElapsedMs <= 0L) return false
        val elapsedMs = nowElapsedMs - backgroundedAtElapsedMs
        return elapsedMs >= clampedMinutes * 60_000L
    }

    private inline fun <T> List<T>.upsertById(item: T, idOf: (T) -> String): List<T> {
        val targetId = idOf(item)
        val out = toMutableList()
        val idx = out.indexOfFirst { idOf(it) == targetId }
        if (idx >= 0) out[idx] = item else out.add(item)
        return out
    }

    fun wouldCreateJumpCycle(
        serverId: String,
        targetJumpServerId: String,
        allServers: List<ServerConfig>
    ): Boolean {
        val target = targetJumpServerId.trim()
        if (target.isEmpty()) return false
        val selfId = serverId.trim()
        if (selfId.isNotEmpty() && target == selfId) return true
        val byId = allServers.associateBy { it.id }
        val visited = HashSet<String>()
        if (selfId.isNotEmpty()) visited.add(selfId)
        var cur: String? = target
        while (!cur.isNullOrBlank()) {
            if (!visited.add(cur)) return true
            val node = byId[cur] ?: break
            cur = if (node.jumpHost.mode == JumpMode.SERVER) {
                node.jumpHost.serverId.trim().ifEmpty { null }
            } else {
                null
            }
        }
        return false
    }

    fun upsertServer(data: VaultData, server: ServerConfig): Pair<VaultData, ServerConfig> {
        val now = Instant.now().toString()
        val id = server.id.trim().ifBlank { UUID.randomUUID().toString() }
        val existing = data.servers.find { it.id == id }
        val trimmedHost = server.host.trim()
        require(trimmedHost.isNotEmpty()) { "主机地址不能为空" }

        val trimmedUser = server.username.trim().ifBlank { "root" }
        val trimmedKeyId = server.sshKeyId.trim()
        if (server.authType == AuthType.KEY || server.authType == AuthType.BOTH) {
            require(trimmedKeyId.isNotEmpty()) { "密钥认证必须选择 SSH 密钥" }
            if (data.sshKeys.isNotEmpty()) {
                require(data.sshKeys.any { it.id == trimmedKeyId }) { "所选 SSH 密钥不存在" }
            }
        }

        val proxyHost = server.proxy.host.trim()
        val proxyPort = if (server.proxy.type != ProxyType.NONE) {
            require(proxyHost.isNotEmpty()) { "代理地址不能为空" }
            server.proxy.port.coerceIn(1, 65535)
        } else {
            server.proxy.port.takeIf { it in 1..65535 } ?: 0
        }
        val normProxy = server.proxy.copy(
            host = proxyHost,
            port = proxyPort,
            username = server.proxy.username.trim()
        )

        val jumpServerId = server.jumpHost.serverId.trim()
        val jumpHost = server.jumpHost.host.trim()
        val jumpKeyId = server.jumpHost.sshKeyId.trim()
        val jumpPort = when (server.jumpHost.mode) {
            JumpMode.SERVER -> {
                require(jumpServerId.isNotEmpty()) { "请选择跳板主机" }
                require(data.servers.any { it.id == jumpServerId }) { "所选跳板主机不存在" }
                require(!wouldCreateJumpCycle(id, jumpServerId, data.servers)) { "跳板机链路存在循环引用" }
                server.jumpHost.port.takeIf { it in 1..65535 } ?: 22
            }
            JumpMode.CUSTOM -> {
                require(jumpHost.isNotEmpty()) { "跳板机地址不能为空" }
                if (server.jumpHost.authType == AuthType.KEY || server.jumpHost.authType == AuthType.BOTH) {
                    require(jumpKeyId.isNotEmpty()) { "跳板机密钥认证必须选择 SSH 密钥" }
                    if (data.sshKeys.isNotEmpty()) {
                        require(data.sshKeys.any { it.id == jumpKeyId }) { "所选跳板机 SSH 密钥不存在" }
                    }
                }
                server.jumpHost.port.coerceIn(1, 65535)
            }
            else -> server.jumpHost.port.takeIf { it in 1..65535 } ?: 22
        }
        val normJump = server.jumpHost.copy(
            serverId = jumpServerId,
            host = jumpHost,
            port = jumpPort,
            username = server.jumpHost.username.trim(),
            sshKeyId = jumpKeyId
        )

        val norm = server.copy(
            id = id,
            name = server.name.trim().ifBlank { "$trimmedUser@$trimmedHost" },
            group = server.group.trim().ifBlank { "默认分组" },
            host = trimmedHost,
            port = server.port.coerceIn(1, 65535),
            username = trimmedUser,
            sshKeyId = trimmedKeyId,
            proxy = normProxy,
            jumpHost = normJump,
            timeoutSeconds = server.timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
            keepAliveSeconds = server.keepAliveSeconds.coerceIn(MIN_KEEPALIVE_SECONDS, MAX_KEEPALIVE_SECONDS),
            createdAt = existing?.createdAt?.ifBlank { now } ?: now,
            updatedAt = now,
            lastConnectedAt = existing?.lastConnectedAt ?: server.lastConnectedAt
        )
        return data.copy(servers = data.servers.upsertById(norm) { it.id }) to norm
    }

    fun upsertSshKey(data: VaultData, key: SSHKeyRecord): Pair<VaultData, SSHKeyRecord> {
        val now = Instant.now().toString()
        val id = key.id.ifBlank { UUID.randomUUID().toString() }
        val norm = key.copy(
            id = id,
            name = key.name.trim().ifBlank { "SSH Key" },
            createdAt = key.createdAt.ifBlank { now }
        )
        return data.copy(sshKeys = data.sshKeys.upsertById(norm) { it.id }) to norm
    }

    fun upsertShortcut(data: VaultData, shortcut: CommandShortcut): Pair<VaultData, CommandShortcut> {
        val now = Instant.now().toString()
        val id = shortcut.id.ifBlank { UUID.randomUUID().toString() }
        val existing = data.shortcuts.find { it.id == id }
        val norm = shortcut.copy(
            id = id,
            name = shortcut.name.trim(),
            command = shortcut.command.trim(),
            category = shortcut.category.trim().ifBlank { "常用" },
            createdAt = existing?.createdAt?.ifBlank { now } ?: now,
            updatedAt = now
        )
        return data.copy(shortcuts = data.shortcuts.upsertById(norm) { it.id }) to norm
    }

    fun upsertTemplate(data: VaultData, template: BuiltinTemplate): Pair<VaultData, BuiltinTemplate> {
        val id = template.id.ifBlank { UUID.randomUUID().toString() }
        val existing = data.templates.find { it.id == id }
        val norm = template.copy(
            id = id,
            presetId = existing?.presetId ?: template.presetId,
            name = template.name.trim(),
            category = template.category.trim().ifBlank { "自定义" },
            command = template.command.trim()
        )
        return data.copy(templates = data.templates.upsertById(norm) { it.id }) to norm
    }

    fun loadPresets(data: VaultData): Pair<VaultData, PresetLoadResult> {
        val existingKeys = mutableSetOf<String>()
        for (t in data.templates) {
            if (t.id.isNotBlank()) existingKeys.add(t.id)
            if (t.presetId.isNotBlank()) existingKeys.add(t.presetId)
        }
        val list = data.templates.toMutableList()
        var added = 0
        var skipped = 0
        for (preset in BuiltinOpsPresets.all()) {
            if (preset.id in existingKeys || preset.presetId in existingKeys) {
                skipped++
            } else {
                list.add(preset)
                existingKeys.add(preset.id)
                added++
            }
        }
        val next = if (added > 0) data.copy(templates = list) else data
        return next to PresetLoadResult(added = added, skipped = skipped, templates = next.templates)
    }

    fun appendHistory(
        data: VaultData,
        command: String,
        serverId: String,
        serverName: String,
        sessionId: String
    ): VaultData {
        val rec = CommandRecord(
            id = UUID.randomUUID().toString(),
            command = command.trim(),
            serverId = serverId,
            serverName = serverName,
            sessionId = sessionId,
            executedAt = Instant.now().toString()
        )
        val maxLen = data.settings.maxHistoryEntries.coerceIn(100, 10000)
        val existingCount = minOf(data.history.size, maxLen - 1)
        val nextHistory = ArrayList<CommandRecord>(existingCount + 1)
        nextHistory.add(rec)
        for (i in 0 until existingCount) {
            nextHistory.add(data.history[i])
        }
        return data.copy(history = nextHistory)
    }
}
