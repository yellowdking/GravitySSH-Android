package com.gravityssh.app.terminal

import com.gravityssh.app.model.*
import org.junit.Assert.*
import org.junit.Test

class SmartAutocompleteAndSelectionTest {
 private val ae = AutocompleteEngine
 private fun f(n: String, d: Boolean = false, p: String = "/opt/$n") = SFTPFileEntry(n, p, isDir = d)
 private fun r(c: String, s: String = "", t: String = "") = CommandRecord(id = c, command = c, serverId = s, executedAt = t)
 private fun eq(a: Any?, b: Any?) = assertEquals(a, b)

 @Test
 fun zeroShortcutsAndZeroMidWordNoise() {
  val sc = listOf(
   CommandShortcut(id = "s1", name = "DockerQuick", command = "docker ps --format table"),
   CommandShortcut(id = "s2", name = "ComposeUp", command = "docker compose up -d")
  )
  val hist = listOf(r("docker stats"))
  for (q in listOf("doc", "docker ", "docker c", "docker compose ")) {
   val res = ae.suggest(q, sc, hist, AppSettings(), 5)
   assertTrue(res.isNotEmpty() && res.none { it.source == "shortcut" })
  }
  val ls = ae.suggest("ls", sc, hist, AppSettings(), 5)
  assertTrue(ls.none { "docker" in it.command || "docker" in it.insertText })
  assertTrue(ls.all { it.insertText.lowercase().startsWith("ls") })
  assertTrue(ae.suggest("容器", sc, hist, AppSettings(), 5).isEmpty())
 }

 @Test
 fun remoteFilesDirsOnlyHomeExtAndYamlBoost() {
  val es = listOf(f("sshsync", true), f("docker-compose.yml"), f(".env"))
  val cd = ae.suggest("cd ", history = listOf(r("cd /var/log")), limit = 3, remoteEntries = es, currentDir = "/opt")
  eq("sshsync/", cd.first().command); eq("dir", cd.first().source); eq("cd sshsync/", cd.first().insertText)
  assertTrue(cd.none { it.command == "docker-compose.yml" || it.command == ".env" })

  eq(PathQuery("/opt", "ss", "/opt/", true), ae.resolvePathQuery("cd /opt/ss", "/root"))
  eq(PathQuery("/home/alice", "p", "~/", true), ae.resolvePathQuery("cd ~/p", "/opt", "/home/alice"))
  val home = ae.suggest("cd ~/p", remoteEntries = listOf(f("projects", true, "/home/alice/projects")), currentDir = "/opt", homeDir = "/home/alice")
  eq("cd ~/projects/", home.first().insertText)

  val nano = ae.suggest("nano ", remoteEntries = listOf(f("logo.png"), f("app.yml")), currentDir = "/opt")
  eq("app.yml", nano.first().command); eq("file", nano.first().source)

  val yaml = ae.suggest("docker compose -f ", remoteEntries = listOf(f("README.md"), f("compose.yaml")), currentDir = "/opt")
  eq("compose.yaml", yaml.first().command); eq("docker compose -f compose.yaml", yaml.first().insertText)
 }

 @Test
 fun dockerSubcommandsChainingWrappersAndFlags() {
  val root = ae.suggest("docker ", limit = 3)
  assertTrue("ps -a" in root.map { it.command } && "compose" in root.map { it.command } && root.all { it.source == "subcmd" })

  val dc = ae.suggest("docker c", limit = 3)
  eq("compose", dc.first().command); eq("docker compose ", dc.first().insertText)
  eq(CompletionEdit(0, "ompose "), ae.computeCompletionInsert("docker c", "docker compose "))

  eq(listOf("up -d", "down", "pull"), ae.suggest("docker compose ", limit = 3).map { it.command })
  eq("cd /opt && docker compose ", ae.suggest("cd /opt && docker c", limit = 3).first().insertText)
  eq("sudo docker compose ", ae.suggest("sudo docker c", limit = 3).first().insertText)

  val lsF = ae.suggest("ls -", limit = 3)
  eq("-la", lsF.first().command); eq("flag", lsF.first().source)

  val upF = ae.suggest("docker compose up -d -", limit = 5)
  val cmds = upF.map { it.command }
  assertTrue(upF.all { it.source == "flag" } && "-d" !in cmds && "--build" in cmds && "--force-recreate" in cmds)
 }

 @Test
 fun folderContextHistoryAffinityAndDenylist() {
  val cmp = listOf(f("docker-compose.yml"))
  eq("compose", ae.suggest("docker ", currentDir = "/opt", cwdEntries = cmp).first().command)
  eq("docker compose up -d", ae.suggest("do", currentDir = "/opt", cwdEntries = cmp).first().command)
  eq("git status", ae.suggest("gi", currentDir = "/opt", cwdEntries = listOf(f(".git", true))).first().command)

  val hist = listOf(
   r("journalctl -u api-other -f", "srv-b", "2026-11-26T15:00:00Z"),
   r("journalctl -u api-local -f", "srv-a", "2026-11-26T14:00:00Z"),
   r("rm -rf /", "srv-a", "2026-11-26T15:00:00Z"),
   r("rm -rf /tmp/build", "srv-a", "2026-11-26T14:00:00Z")
  )
  val hRes = ae.suggest("journalctl -u api", history = hist, currentServerId = "srv-a", limit = 5)
  eq("journalctl -u api-local -f", hRes.first().command); eq("history_local", hRes.first().source); eq("history", hRes[1].source)

  val rmRes = ae.suggest("rm -rf ", history = hist, currentServerId = "srv-a", limit = 5)
  assertTrue(rmRes.none { it.command == "rm -rf /" } && rmRes.any { it.command == "rm -rf /tmp/build" })
 }

 @Test
 fun webStyleWordSelectionAndMultiLineExtraction() {
  val ctrl = TerminalSessionController("sel1", initialCols = 40, initialRows = 6, keywordHighlightEnabled = { false })
  ctrl.onRemoteOutput("addr 107.172.98.169:54843 ok   \r\nsecond line   \r\nthird")
  val snap = ctrl.snapshot(0)

  val w = TerminalSelectionHelper.selectWordAt(snap, row = 0, col = 12)
  eq(CellPos(0, 5), w.start); eq(CellPos(0, 24), w.end)
  eq("107.172.98.169:54843", TerminalSelectionHelper.extractText(snap, w))

  eq("107.172.98.169:54843 ok\nsecond", TerminalSelectionHelper.extractText(snap, TextSelection(CellPos(0, 5), CellPos(1, 5))))

  val cw = 10f; val ch = 20f; val px = 12f; val py = 4f; val r = 30f
  eq(SelectionHandle.START, TerminalSelectionHelper.hitTestHandle(px + 5 * cw - r * 0.25f, py + ch + r * 0.45f, w, cw, ch, px, py, r))
  eq(SelectionHandle.END, TerminalSelectionHelper.hitTestHandle(px + 25 * cw + r * 0.25f, py + ch + r * 0.45f, w, cw, ch, px, py, r))
  eq(SelectionHandle.NONE, TerminalSelectionHelper.hitTestHandle(390f, 150f, w, cw, ch, px, py, r))
 }
}
