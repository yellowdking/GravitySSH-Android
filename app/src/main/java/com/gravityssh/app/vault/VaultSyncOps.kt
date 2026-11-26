package com.gravityssh.app.vault

import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.VaultData
import java.time.Instant

object VaultSyncOps {

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
            schemaVersion = 1,
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
        if (mode == SyncImportMode.OVERWRITE) {
            val next = current.copy(
                servers = bundle.servers,
                sshKeys = bundle.sshKeys,
                shortcuts = bundle.shortcuts,
                templates = bundle.templates
            )
            return next to SyncImportSummary(
                mode = mode,
                serversAdded = bundle.servers.size, serversUpdated = 0,
                keysAdded = bundle.sshKeys.size, keysUpdated = 0,
                shortcutsAdded = bundle.shortcuts.size, shortcutsUpdated = 0,
                templatesAdded = bundle.templates.size, templatesUpdated = 0
            )
        }

        val (servers, srvAdd, srvUpd) = mergeList(
            current.servers, bundle.servers,
            match = { cur, item ->
                (item.id.isNotBlank() && cur.id == item.id) ||
                    (cur.host.equals(item.host, ignoreCase = true) && cur.port == item.port && cur.username == item.username)
            },
            keepId = { cur, item -> item.copy(id = cur.id.ifBlank { item.id }) }
        )
        val (keys, keyAdd, keyUpd) = mergeList(
            current.sshKeys, bundle.sshKeys,
            match = { cur, item ->
                (item.id.isNotBlank() && cur.id == item.id) ||
                    (item.fingerprint.isNotBlank() && cur.fingerprint == item.fingerprint)
            },
            keepId = { cur, item -> item.copy(id = cur.id.ifBlank { item.id }) }
        )
        val (shortcuts, scAdd, scUpd) = mergeList(
            current.shortcuts, bundle.shortcuts,
            match = { cur, item ->
                (item.id.isNotBlank() && cur.id == item.id) ||
                    (cur.name == item.name && cur.command == item.command)
            },
            keepId = { cur, item -> item.copy(id = cur.id.ifBlank { item.id }) }
        )
        val (templates, tplAdd, tplUpd) = mergeList(
            current.templates, bundle.templates,
            match = { cur, item ->
                (item.id.isNotBlank() && cur.id == item.id) ||
                    (item.presetId.isNotBlank() && cur.presetId == item.presetId) ||
                    cur.name == item.name
            },
            keepId = { cur, item -> item.copy(id = cur.id.ifBlank { item.id }) }
        )

        val next = current.copy(servers = servers, sshKeys = keys, shortcuts = shortcuts, templates = templates)
        return next to SyncImportSummary(mode, srvAdd, srvUpd, keyAdd, keyUpd, scAdd, scUpd, tplAdd, tplUpd)
    }

    private fun <T> mergeList(
        existing: List<T>,
        incoming: List<T>,
        match: (cur: T, item: T) -> Boolean,
        keepId: (cur: T, item: T) -> T
    ): Triple<List<T>, Int, Int> {
        val out = existing.toMutableList()
        var added = 0
        var updated = 0
        for (item in incoming) {
            val idx = out.indexOfFirst { cur -> match(cur, item) }
            if (idx >= 0) {
                out[idx] = keepId(out[idx], item)
                updated++
            } else {
                out.add(item)
                added++
            }
        }
        return Triple(out, added, updated)
    }
}
