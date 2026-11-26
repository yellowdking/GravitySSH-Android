package com.gravityssh.app.terminal

import com.gravityssh.app.model.*
import java.time.Instant
import kotlin.math.*

data class FolderSignals(
 val hasCompose:Boolean=false,val hasDockerfile:Boolean=false,val hasGit:Boolean=false,val hasNode:Boolean=false,
 val hasMake:Boolean=false,val hasGo:Boolean=false,val hasNginx:Boolean=false,val hasSh:Boolean=false
)

object AutocompleteContext {
 private val WS=Regex("\\s+")
 private val CMP=setOf("docker-compose.yml","docker-compose.yaml","compose.yml","compose.yaml")

 private fun pk(s:String)=s.split(';').map {
  val i=it.indexOf('^');val r=it.take(i);val sp=r.endsWith(' ')
  SubcmdCandidate(if (sp) r.dropLast(1) else r,it.drop(i+1),sp)
 }

 private val RT=arrayOf(
  pk("docker compose up -d^启动;docker compose down^停止;docker compose logs -f --tail 100^日志;docker compose pull^拉取"),
  pk("docker build -t ^构建"),
  pk("git status^状态;git pull --rebase^拉取;git diff^变更;git log --oneline -20^提交"),
  pk("npm run ^脚本;npm install^依赖;pm2 list^进程"),
  pk("make ^构建"),
  pk("go build ./...^编译;go test ./...^测试"),
  pk("nginx -t^语法;nginx -s reload^重载")
 )

 fun scanFolderSignals(cwdEntries: List<SFTPFileEntry>, cwd: String): FolderSignals {
  var b=if (cwd.startsWith("/etc/nginx")) 64 else 0
  for (e in cwdEntries) {
   val n=e.name.lowercase()
   if (n==".git") b=b or 4
   if (e.isDir) continue
   when (n) {
    in CMP->b=b or 1
    "package.json"->b=b or 8
    "makefile","gnumakefile"->b=b or 16
    "go.mod"->b=b or 32
    "nginx.conf"->b=b or 64
   }
   if (n=="dockerfile"||n.startsWith("dockerfile.")) b=b or 2
   if (n.endsWith(".sh")) b=b or 128
  }
  return FolderSignals(b and 1!=0,b and 2!=0,b and 4!=0,b and 8!=0,b and 16!=0,b and 32!=0,b and 64!=0,b and 128!=0)
 }
 fun fileExtDelta(cmd: String, prevTok: String, entry: SFTPFileEntry, isFirstToken: Boolean): Int =
  SubcommandCatalog.fileExtScore(cmd, prevTok, entry, isFirstToken)
 fun folderSubcmdBoost(cmdPath: String, token: String, sig: FolderSignals): Int {
  val t=token.lowercase()
  return when (cmdPath.lowercase()) {
   "docker"->if (sig.hasDockerfile&&t.startsWith("build")) 110 else if (sig.hasCompose&&t=="compose") 100 else 0
   "docker compose"->if (!sig.hasCompose) 0 else when {t=="up -d"->85;t=="down"->80;t.startsWith("logs")->75;t=="pull"->70;else->0}
   "git"->if (!sig.hasGit) 0 else when {t=="status"->90;t.startsWith("pull")->85;t=="diff"->80;t.startsWith("log")->75;else->0}
   "nginx"->if (!sig.hasNginx) 0 else if (t=="-t") 90 else if (t=="-s reload") 85 else 0
   "pm2"->if (!sig.hasNode) 0 else if (t=="list") 70 else if (t.startsWith("logs")) 65 else 0
   else->0
  }
 }
 fun folderRootSuggestions(prefix: String, sig: FolderSignals): List<SubcmdCandidate> {
  val pl=prefix.lowercase();if (pl.length<2) return emptyList()
  val fs=booleanArrayOf(sig.hasCompose,sig.hasDockerfile,sig.hasGit,sig.hasNode,sig.hasMake,sig.hasGo,sig.hasNginx)
  return fs.indices.filter {fs[it]}.flatMap {RT[it]}.filter {val t=it.tokenText.lowercase();t.startsWith(pl)&&(t!=pl||it.appendSpace)}
 }
 private class Agg(var cmd:String,val k:String,var f:Int,var lf:Int,var a:Double,var la:Double,var loc:Boolean)
 fun rankHistory(p: ParsedLine, input: String, currentServerId: String, history: List<CommandRecord>, limit: Int): List<CompletionItem> {
  if (history.isEmpty()||limit<=0||p.activeSeg.isEmpty()) return emptyList()
  val sq=p.activeSeg;val sd=SubcommandData;val ef=p.effective
  if (!p.endsWithSpace&&ef.size<=1&&sq.length<2&&p.pathQuery==null) return emptyList()
  if (p.endsWithSpace&&(ef.isEmpty()||ef.size==1&&ef[0].lowercase().let {it !in sd.FILE_PRIORITY_CMDS&&it !in sd.SUBCMD_ROOTS})) return emptyList()
  val now=System.currentTimeMillis();val m=LinkedHashMap<String,Agg>()
  for ((i,r) in history.take(400).withIndex()) {
   val raw=r.command.trim();if (SubcommandCatalog.isUnsafeHistory(raw)) continue
   val k=norm(raw);val loc=currentServerId!=""&&r.serverId==currentServerId
   val ag=ageH(r.executedAt,now,i);val ex=m[k]
   if (ex==null) m[k]=Agg(raw,k,1,if (loc) 1 else 0,ag,if (loc) ag else 1e9,loc)
   else {
    ex.f=minOf(ex.f+1,30);ex.a=minOf(ex.a,ag)
    if (loc) {if (!ex.loc) ex.cmd=raw;ex.loc=true;ex.lf=minOf(ex.lf+1,30);ex.la=minOf(ex.la,ag)}
   }
  }
  val fq=input.trimStart();val ld=input.take(input.length-fq.length)
  val fl=fq.lowercase();val sl=sq.lowercase();val nf=norm(fq);val ns=norm(sq);val hc=p.segPrefix!=""
  return m.values.mapNotNull {a->
   val cl=a.cmd.lowercase()
   val mf=cl.startsWith(fl)&&a.k!=nf;val ms=!mf&&hc&&cl.startsWith(sl)&&a.k!=ns
   if (!mf&&!ms) return@mapNotNull null
   val ins=if (mf) ld+a.cmd else p.segPrefix+a.cmd;if (norm(ins)==nf) return@mapNotNull null
   val ex=(a.cmd.length-if (mf) fq.length else sq.length).coerceIn(0,40)
   val s=if (a.loc) sc(400,a.la,a.lf,ex,45,12,35,15,495) else sc(250,a.a,a.f,ex,50,14,40,30,370)
   val cat=if (a.loc) "本机历史" else "历史"
   CompletionItem(a.cmd,cat,cat,if (a.loc) "history_local" else "history",ins,s)
  }.sortedByDescending {it.score}.take(limit)
 }
 private fun sc(b:Int,ag:Double,fr:Int,ex:Int,r:Int,f:Int,fc:Int,e:Int,hi:Int)=
  (b+(r*exp(-ag/72)).roundToInt()+minOf((f*ln(1.0+fr)).roundToInt(),fc)+e-ex*e/40).coerceIn(b,hi)
 private fun norm(s:String)=s.trim().replace(WS," ").lowercase()
 private fun ageH(ts:String,now:Long,i:Int)=if (ts.isBlank()) i*2.0 else try {maxOf((now-Instant.parse(ts).toEpochMilli())/36e5,0.0)} catch (_:Exception) {i*2.0}
}
