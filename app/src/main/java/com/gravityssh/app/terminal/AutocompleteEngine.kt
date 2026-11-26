package com.gravityssh.app.terminal

import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.SubcommandCatalog as Cat

data class CompletionEdit(val backspaces: Int, val insertText: String)
data class PathQuery(val dirToList: String, val namePrefix: String, val tokenPrefixDir: String, val dirsOnly: Boolean)

object AutocompleteEngine {
  private val WS = Regex("\\s+")

  fun resolvePathQuery(input: String, currentDir: String): PathQuery? {
    val s = input.trimStart().ifEmpty { return null }
    val sp = s.last().isWhitespace()
    val raw = s.trimEnd().split(WS).filter { it.isNotEmpty() }.ifEmpty { return null }
    if (raw.size == 1 && !sp) {
      val t = raw[0].trimStart('"', '\'')
      return if (t.startsWith("./") || t.startsWith("../") || t.startsWith("/")) Cat.buildPathQuery(t, currentDir, false) else null
    }
    if (raw.size == 1 && raw[0].equals("sudo", true)) return null
    val eff = if (raw[0].equals("sudo", true) && raw.size >= 2) raw.drop(1) else raw
    val cmd = eff[0].lowercase()
    val tok = (if (sp) "" else raw.last()).trimStart('"', '\'')
    val prev = if (sp) raw.last() else raw.getOrNull(raw.size - 2).orEmpty()
    if (tok.startsWith("~/") || (tok.startsWith("-") && !tok.contains('/'))) return null
    val isF = cmd in Cat.FILE_PRIORITY_CMDS || tok.contains('/') || tok == "." || tok == ".." || prev in Cat.FILE_FLAGS
    return if (!isF && Cat.matchSubcommands(eff, sp).isNotEmpty()) null else Cat.buildPathQuery(tok, currentDir, cmd in Cat.DIR_ONLY_CMDS)
  }

  @Suppress("UNUSED_PARAMETER")
  fun suggest(
    input: String, shortcuts: List<CommandShortcut> = emptyList(), history: List<CommandRecord> = emptyList(),
    settings: AppSettings = AppSettings(), limit: Int = 3,
    remoteEntries: List<SFTPFileEntry> = emptyList(), currentDir: String = "/"
  ): List<CompletionItem> {
    if (!settings.autocompleteEnabled || limit <= 0) return emptyList()
    val s = input.trimStart().ifEmpty { return emptyList() }
    val sp = s.last().isWhitespace()
    val toks = s.trimEnd().split(WS).filter { it.isNotEmpty() }.ifEmpty { return emptyList() }
    val pfx = if (sp) s else s.indexOfLast { it.isWhitespace() }.let { if (it >= 0) s.substring(0, it + 1) else "" }
    val seen = HashSet<String>()
    val out = mutableListOf<CompletionItem>()
    fun put(c: String, d: String, cat: String, src: String, ins: String, sc: Int) { if (seen.add(ins)) out.add(CompletionItem(c, d, cat, src, ins, sc)) }
    resolvePathQuery(s, currentDir)?.takeIf { remoteEntries.isNotEmpty() }?.let { pq ->
      val np = pq.namePrefix.lowercase()
      val dot = np.startsWith(".")
      for (e in remoteEntries) {
        val d = e.isDir
        if (e.name == "." || e.name == ".." || (!dot && e.name.startsWith(".")) || (pq.dirsOnly && !d)) continue
        val nl = e.name.lowercase()
        val b = when {
          np.isEmpty() -> 100
          nl == np -> if (d) 140 else continue
          nl.startsWith(np) -> 120 - (e.name.length - np.length).coerceAtMost(40)
          nl.contains(np) -> 40
          else -> continue
        }
        val raw = pq.tokenPrefixDir + e.name + if (d) "/" else ""
        val ins = pfx + if (raw.any { it.isWhitespace() }) "\"$raw\"" else raw
        val lbl = if (d) "目录" else "文件"
        put(if (d) "${e.name}/" else e.name, lbl, lbl, if (d) "dir" else "file", ins, 600 + (if (d) (if (pq.dirsOnly) 150 else 40) else 0) + b)
      }
    }
    val multi = toks.size >= 2 || (toks.size == 1 && sp)
    if (multi) {
      val cat = (if (toks[0].equals("sudo", true) && toks.size >= 2) toks[1] else toks[0]).lowercase()
      Cat.matchSubcommands(toks, sp).forEachIndexed { i, c ->
        put(c.tokenText, c.description, cat, "subcmd", pfx + c.tokenText + if (c.appendSpace) " " else "", 480 - (i * 4).coerceAtMost(80))
      }
    }
    val ql = s.lowercase()
    if (!multi && s.length >= 2) {
      for ((c, d) in Cat.rootHints) if (c.startsWith(ql) && c != ql) put(c, d, c, "builtin", "$c ", 360 - (c.length - ql.length).coerceAtMost(40))
      for (e in BuiltinCommands.all) score(e.command, e.description, ql, 220).takeIf { it > 0 }?.let { put(e.command, e.description, e.category, "builtin", e.command, it) }
    }
    if (settings.autocompleteFromHistory && s.length >= 2) {
      history.take(300).forEachIndexed { i, r ->
        val c = r.command.trim()
        if (c.isNotEmpty() && '\n' !in c) score(c, "", ql, 150 + (40 - i / 8).coerceAtLeast(0)).takeIf { it > 0 }?.let { put(c, "历史命令", "历史", "history", c, it) }
      }
    }
    return out.sortedByDescending { it.score }.take(limit)
  }
  private fun score(c: String, d: String, q: String, b: Int) = c.lowercase().let { cl ->
    when {
      cl == q -> 0
      cl.startsWith(q) -> b + 120 - (c.length - q.length).coerceAtMost(60)
      cl.contains(q) -> b + 40
      d.isNotEmpty() && d.lowercase().contains(q) -> b + 10
      else -> 0
    }
  }
  fun computeCompletionInsert(currentLine: String, fullCommand: String): CompletionEdit {
    val t = currentLine.trimStart()
    if (t.isEmpty() || fullCommand.startsWith(t)) return CompletionEdit(0, fullCommand.substring(t.length))
    var n = 0
    while (n < minOf(t.length, fullCommand.length) && t[n] == fullCommand[n]) n++
    return CompletionEdit(t.length - n, fullCommand.substring(n))
  }
  fun applyTerminalInputToLine(currentLine: String, rawData: String): String {
    if (rawData.startsWith("\u001B")) return if (rawData == "\u001B[A" || rawData == "\u001B[B") "" else currentLine
    return rawData.fold(currentLine) { a, ch ->
      when (val c = ch.code) {
        0x0A, 0x0D, 0x03, 0x04, 0x15 -> ""
        0x7F, 0x08 -> a.dropLast(1)
        0x17 -> a.replace(Regex("\\S+\\s*$"), "")
        else -> if (c >= 0x20) a + ch else a
      }
    }
  }
}
