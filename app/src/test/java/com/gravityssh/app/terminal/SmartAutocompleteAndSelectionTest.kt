package com.gravityssh.app.terminal

import com.gravityssh.app.model.*
import org.junit.Assert.*
import org.junit.Test

class SmartAutocompleteAndSelectionTest {
 private val ae=AutocompleteEngine
 private val sh=TerminalSelectionHelper
 private fun f(n:String,d:Boolean=false,p:String="/opt/$n")=SFTPFileEntry(n,p,isDir=d)
 private fun r(c:String,s:String="",t:String="")=CommandRecord(id=c,command=c,serverId=s,executedAt=t)
 private fun eq(a:Any?,b:Any?)=assertEquals(a,b)
 private fun sg(q:String,sc:List<CommandShortcut> =emptyList(),h:List<CommandRecord> =emptyList(),l:Int=3,re:List<SFTPFileEntry> =emptyList(),cwd:String="/",home:String="/",sid:String="",ce:List<SFTPFileEntry> =emptyList())=ae.suggest(q,sc,h,AppSettings(),l,re,cwd,home,sid,ce)

 @Test
 fun zeroShortcutsAndZeroMidWordNoise() {
  val sc=listOf(CommandShortcut(id="s1",name="DockerQuick",command="docker ps --format table"),CommandShortcut(id="s2",name="ComposeUp",command="docker compose up -d"))
  val hist=listOf(r("docker stats"))
  for (q in listOf("doc","docker ","docker c","docker compose ")) {
   val res=sg(q,sc,hist,5)
   assertTrue(res.isNotEmpty()&&res.none {it.source=="shortcut"})
  }
  val ls=sg("ls",sc,hist,5)
  assertTrue(ls.none {"docker" in it.command||"docker" in it.insertText}&&ls.all {it.insertText.lowercase().startsWith("ls")})
  assertTrue(sg("容器",sc,hist,5).isEmpty())
 }

 @Test
 fun remoteFilesDirsOnlyHomeExtAndYamlBoost() {
  val es=listOf(f("sshsync",true),f("docker-compose.yml"),f(".env"))
  val cd=sg("cd ",h=listOf(r("cd /var/log")),re=es,cwd="/opt")
  eq("sshsync/",cd[0].command); eq("dir",cd[0].source); eq("cd sshsync/",cd[0].insertText)
  assertTrue(cd.none {it.command=="docker-compose.yml"||it.command==".env"})

  eq(PathQuery("/opt","ss","/opt/",true),ae.resolvePathQuery("cd /opt/ss","/root"))
  eq(PathQuery("/home/alice","p","~/",true),ae.resolvePathQuery("cd ~/p","/opt","/home/alice"))
  eq("cd ~/projects/",sg("cd ~/p",re=listOf(f("projects",true,"/home/alice/projects")),cwd="/opt",home="/home/alice")[0].insertText)

  val nano=sg("nano ",re=listOf(f("logo.png"),f("app.yml")),cwd="/opt")
  eq("app.yml",nano[0].command); eq("file",nano[0].source)

  val yaml=sg("docker compose -f ",re=listOf(f("README.md"),f("compose.yaml")),cwd="/opt")
  eq("compose.yaml",yaml[0].command); eq("docker compose -f compose.yaml",yaml[0].insertText)
 }

 @Test
 fun dockerSubcommandsChainingWrappersAndFlags() {
  val root=ae.suggest("docker ",limit=3)
  assertTrue("ps -a" in root.map {it.command}&&"compose" in root.map {it.command}&&root.all {it.source=="subcmd"})

  val dc=ae.suggest("docker c",limit=3)
  eq("compose",dc[0].command); eq("docker compose ",dc[0].insertText)
  eq(CompletionEdit(0,"ompose "),ae.computeCompletionInsert("docker c","docker compose "))

  eq(listOf("up -d","down","pull"),ae.suggest("docker compose ",limit=3).map {it.command})
  eq("cd /opt && docker compose ",ae.suggest("cd /opt && docker c",limit=3)[0].insertText)
  eq("sudo docker compose ",ae.suggest("sudo docker c",limit=3)[0].insertText)

  val lsF=ae.suggest("ls -",limit=3)
  eq("-la",lsF[0].command); eq("flag",lsF[0].source)

  val upF=ae.suggest("docker compose up -d -",limit=5)
  val cmds=upF.map {it.command}
  assertTrue(upF.all {it.source=="flag"}&&"-d" !in cmds&&"--build" in cmds&&"--force-recreate" in cmds)
 }

 @Test
 fun oneCharTriggerFreeAndFlagChaining() {
  val f1=ae.suggest("f",limit=3)
  assertTrue(f1.isNotEmpty()&&f1.all {it.command.lowercase().startsWith("f")})
  eq("free",f1[0].command); eq("free ",f1[0].insertText)
  eq(CompletionEdit(0,"ree "),ae.computeCompletionInsert("f",f1[0].insertText))

  val fc=ae.suggest("free ",limit=3)
  eq("-h",fc[0].command); eq("free -h",fc[0].insertText)

  val ff=ae.suggest("free -",limit=3)
  eq("-h",ff[0].command); eq("flag",ff[0].source); eq("free -h",ff[0].insertText)
 }

 @Test
 fun folderContextHistoryAffinityAndDenylist() {
  val cmp=listOf(f("docker-compose.yml"))
  eq("compose",sg("docker ",cwd="/opt",ce=cmp)[0].command)
  eq("docker compose up -d",sg("do",cwd="/opt",ce=cmp)[0].command)
  eq("git status",sg("gi",cwd="/opt",ce=listOf(f(".git",true)))[0].command)

  val hist=listOf(
   r("journalctl -u api-other -f","srv-b","2026-11-26T15:00:00Z"),
   r("journalctl -u api-local -f","srv-a","2026-11-26T14:00:00Z"),
   r("rm -rf /","srv-a","2026-11-26T15:00:00Z"),
   r("rm -rf /tmp/build","srv-a","2026-11-26T14:00:00Z")
  )
  val hRes=sg("journalctl -u api",h=hist,l=5,sid="srv-a")
  eq("journalctl -u api-local -f",hRes[0].command); eq("history_local",hRes[0].source); eq("history",hRes[1].source)

  val rmRes=sg("rm -rf ",h=hist,l=5,sid="srv-a")
  assertTrue(rmRes.none {it.command=="rm -rf /"}&&rmRes.any {it.command=="rm -rf /tmp/build"})
 }

 @Test
 fun webStyleWordSelectionAndMultiLineExtraction() {
  val ctrl=TerminalSessionController("sel1",initialCols=40,initialRows=6,keywordHighlightEnabled={false})
  ctrl.onRemoteOutput("addr 107.172.98.169:54843 ok   \r\nsecond line   \r\nthird")
  val snap=ctrl.snapshot(0)

  val w=sh.selectWordAt(snap,row=0,col=12)
  eq(CellPos(0,5),w.start); eq(CellPos(0,24),w.end)
  eq("107.172.98.169:54843",sh.extractText(snap,w))
  eq("107.172.98.169:54843 ok\nsecond",sh.extractText(snap,TextSelection(CellPos(0,5),CellPos(1,5))))

  val cw=10f; val ch=20f; val px=12f; val py=4f; val r=30f
  eq(SelectionHandle.START,sh.hitTestHandle(px+5*cw-r*0.25f,py+ch+r*0.45f,w,cw,ch,px,py,r))
  eq(SelectionHandle.END,sh.hitTestHandle(px+25*cw+r*0.25f,py+ch+r*0.45f,w,cw,ch,px,py,r))
  eq(SelectionHandle.NONE,sh.hitTestHandle(390f,150f,w,cw,ch,px,py,r))
 }
}
