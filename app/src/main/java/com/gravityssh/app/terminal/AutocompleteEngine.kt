package com.gravityssh.app.terminal

import com.gravityssh.app.model.AppSettings
import com.gravityssh.app.model.CommandRecord
import com.gravityssh.app.model.CommandShortcut
import com.gravityssh.app.model.CompletionItem

data class CompletionEdit(
    val backspaces: Int,
    val insertText: String
)

object AutocompleteEngine {

    fun suggest(
        input: String,
        shortcuts: List<CommandShortcut>,
        history: List<CommandRecord>,
        settings: AppSettings,
        limit: Int = 8
    ): List<CompletionItem> {
        if (!settings.autocompleteEnabled) return emptyList()
        val q = input.trimStart()
        if (q.length < 2) return emptyList()
        val qLower = q.lowercase()
        val seen = HashSet<String>()
        val scored = mutableListOf<CompletionItem>()

        if (settings.autocompleteFromShortcut) {
            for (sc in shortcuts) {
                val cmd = sc.command.trim()
                if (cmd.isEmpty() || cmd.contains('\n')) continue
                val score = scoreMatch(cmd, sc.name, qLower, base = 300 + sc.useCount.coerceAtMost(50))
                if (score > 0 && seen.add(cmd)) {
                    scored.add(CompletionItem(cmd, sc.name, sc.category, "shortcut", cmd, score))
                }
            }
        }

        if (settings.autocompleteFromHistory) {
            for ((idx, rec) in history.take(300).withIndex()) {
                val cmd = rec.command.trim()
                if (cmd.isEmpty() || cmd.contains('\n')) continue
                val recencyBonus = (50 - idx / 6).coerceAtLeast(0)
                val score = scoreMatch(cmd, "", qLower, base = 200 + recencyBonus)
                if (score > 0 && seen.add(cmd)) {
                    scored.add(CompletionItem(cmd, "历史命令", "历史", "history", cmd, score))
                }
            }
        }

        for (entry in BuiltinCommands.all) {
            val score = scoreMatch(entry.command, entry.description, qLower, base = 100)
            if (score > 0 && seen.add(entry.command)) {
                scored.add(CompletionItem(entry.command, entry.description, entry.category, "builtin", entry.command, score))
            }
        }

        return scored.sortedByDescending { it.score }.take(limit)
    }

    private fun scoreMatch(cmd: String, desc: String, qLower: String, base: Int): Int {
        val cLower = cmd.lowercase()
        if (cLower == qLower) return 0
        if (cLower.startsWith(qLower)) return base + 120 - (cmd.length - qLower.length).coerceAtMost(60)
        if (cLower.contains(qLower)) return base + 40
        if (desc.isNotEmpty() && desc.lowercase().contains(qLower)) return base + 10
        return 0
    }

    fun computeCompletionInsert(currentLine: String, fullCommand: String): CompletionEdit {
        val trimmed = currentLine.trimStart()
        if (trimmed.isEmpty()) return CompletionEdit(0, fullCommand)
        if (fullCommand.startsWith(trimmed)) {
            return CompletionEdit(0, fullCommand.substring(trimmed.length))
        }
        var common = 0
        val maxLen = minOf(trimmed.length, fullCommand.length)
        while (common < maxLen && trimmed[common] == fullCommand[common]) common++
        if (common > 0) {
            return CompletionEdit(trimmed.length - common, fullCommand.substring(common))
        }
        return CompletionEdit(trimmed.length, fullCommand)
    }

    fun applyTerminalInputToLine(currentLine: String, rawData: String): String {
        if (rawData.startsWith("\u001B")) {
            return if (rawData == "\u001B[A" || rawData == "\u001B[B") "" else currentLine
        }
        var line = currentLine
        for (ch in rawData) {
            val code = ch.code
            line = when {
                ch == '\r' || ch == '\n' || code == 0x03 || code == 0x04 || code == 0x15 -> ""
                code == 0x7F || code == 0x08 -> if (line.isNotEmpty()) line.dropLast(1) else ""
                code == 0x17 -> line.replace(Regex("\\S+\\s*$"), "")
                code >= 0x20 -> line + ch
                else -> line
            }
        }
        return line
    }
}
