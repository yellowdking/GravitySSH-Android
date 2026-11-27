package com.gravityssh.app.vault

import com.gravityssh.app.model.BuiltinOpsPresets
import com.gravityssh.app.model.BuiltinTemplate
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.PresetLoadResult
import com.gravityssh.app.model.SSHKeyRecord
import com.gravityssh.app.model.ServerConfig
import com.gravityssh.app.model.VaultData
import java.time.Instant
import java.util.UUID

internal object VaultCrudOps {

    private inline fun <T> List<T>.upsertById(item: T, idOf: (T) -> String): List<T> {
        val targetId = idOf(item)
        val out = toMutableList()
        val idx = out.indexOfFirst { idOf(it) == targetId }
        if (idx >= 0) out[idx] = item else out.add(item)
        return out
    }

    fun upsertServer(data: VaultData, server: ServerConfig): Pair<VaultData, ServerConfig> {
        val now = Instant.now().toString()
        val id = server.id.ifBlank { UUID.randomUUID().toString() }
        val existing = data.servers.find { it.id == id }
        val norm = server.copy(
            id = id,
            name = server.name.trim().ifBlank { "${server.username}@${server.host}" },
            group = server.group.trim().ifBlank { "默认分组" },
            host = server.host.trim(),
            port = server.port.coerceIn(1, 65535),
            username = server.username.trim().ifBlank { "root" },
            timeoutSeconds = server.timeoutSeconds.coerceIn(3, 120),
            keepAliveSeconds = server.keepAliveSeconds.coerceIn(0, 300),
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
        return data.copy(history = (listOf(rec) + data.history).take(maxLen))
    }
}
