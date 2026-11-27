package com.gravityssh.app.terminal

import com.gravityssh.app.model.SFTPFileEntry

object SubcommandCatalog {
 val FILE_FLAGS: Set<String> get() = SubcommandData.FILE_FLAGS
 val rootCommands: List<Pair<String, String>> get() = SubcommandData.rootCommands

 private fun w(s:String)=s.split(' ').toSet()
 private val WS=Regex("\\s+")
 private val CH=Regex("&&|\\|\\||[;|]")
 private val SEC=Regex("(?i)\\b(PASSWORD|PASSWD|TOKEN|SECRET|API_KEY|PRIVATE_KEY)\\s*=")
 private val SUD=Regex("^(?:sudo(?:\\s+-[a-zA-Z]+(?:\\s+\\S+)?)*\\s+)+")
 private val DEV=Regex(">\\s*/dev/(sd|nvme|vd)")
 private val DNG=w("wipefs fdisk parted shred shutdown reboot poweroff halt")
 private val RMD=w("/ /* ~ ~/* \$home \${home} . .. --no-preserve-root")
 private val TXT=w("yml yaml conf cfg ini json toml env log txt md sh py go js ts service xml sql bash zsh")
 private val BIN=w("png jpg jpeg gif webp bin so exe pdf o a class jar mp4 mp3 iso wasm")
 private val ARC=w("tar tgz zip gz 7z bz2 xz")
 private val ACM=w("tar unzip gunzip 7z gzip zcat")
 private val TCM=w("nano vim vi nvim cat less more head tail grep sed awk")
 private val SCM=w("sh bash zsh source .")

 fun cmdPath(eff: List<String>, endsWithSpace: Boolean): String {
  if (eff.isEmpty()) return ""
  return if (eff.size==2&&endsWithSpace||eff.size==3&&!endsWithSpace) "${eff[0].lowercase()} ${eff[1].lowercase()}" else eff[0].lowercase()
 }

 fun matchSubcommands(eff: List<String>, endsWithSpace: Boolean): List<SubcmdCandidate> {
  if (eff.isEmpty()) return emptyList()
  val (pool,pfx)=when {
   eff.size==1&&endsWithSpace->SubcommandData.l1[eff[0].lowercase()] to ""
   eff.size==2&&!endsWithSpace->SubcommandData.l1[eff[0].lowercase()] to eff[1].lowercase()
   eff.size==2&&endsWithSpace->SubcommandData.l2["${eff[0].lowercase()} ${eff[1].lowercase()}"] to ""
   eff.size==3&&!endsWithSpace->SubcommandData.l2["${eff[0].lowercase()} ${eff[1].lowercase()}"] to eff[2].lowercase()
   else->null to ""
  }
  if (pool.isNullOrEmpty()) return emptyList()
  if (pfx.isEmpty()) return pool
  return pool.filter {val l=it.tokenText.lowercase();l.startsWith(pfx)&&!(l==pfx&&!it.appendSpace)}
 }

 fun matchFlags(eff: List<String>, curTok: String): List<SubcmdCandidate> = SubcommandFlags.matchFlags(eff, curTok)

 internal fun fileExtScore(cmd:String,prevTok:String,e:SFTPFileEntry,first:Boolean):Int {
  if (e.isDir) return 0
  val n=e.name.lowercase();val c=cmd.lowercase();val pv=prevTok.lowercase();val x=n.substringAfterLast('.',"")
  val ar=n.endsWith(".tar.gz")||n.endsWith(".tar.xz")||n.endsWith(".tar.bz2")||x in ARC
  val bn=ar||x in BIN
  if (first) return when {n.endsWith(".sh")->120;'x' in e.mode.lowercase()->80;bn->-120;else->0}
  if (pv=="--env-file") return if (n==".env"||n.startsWith(".env.")||n.endsWith(".env")) 120 else 0
  if ((pv=="-f"||pv=="--file")&&(c=="docker"||c=="kubectl")) return if (x=="yml"||x=="yaml") 120 else -40
  return when {
   c in ACM->if (ar) 120 else -40
   c in TCM->if (x in TXT||n.startsWith(".env")) 80 else if (bn) -120 else 0
   c in SCM->if (x=="sh"||x=="bash"||x=="zsh") 120 else 0
   c=="python"||c=="python3"->if (x=="py") 120 else 0
   c=="node"->if (x=="js"||x=="mjs"||x=="ts") 120 else 0
   c=="chmod"->if (x=="sh") 60 else 40
   else->0
  }
 }

 internal fun isUnsafeHistory(cmd:String):Boolean {
  if (cmd.isEmpty()||cmd.length>160||'\n' in cmd||'\r' in cmd||SEC.containsMatchIn(cmd)||":(){" in cmd||DEV.containsMatchIn(cmd)) return true
  for (pt in CH.split(cmd)) {
   val s=pt.trim().replace(SUD,"").lowercase();if (s.isEmpty()) continue
   val t=s.split(WS);val c=t[0]
   if (c in DNG||c.startsWith("mkfs")||c=="dd"&&t.any {it.startsWith("if=")}||c=="init"&&(t.getOrNull(1)=="0"||t.getOrNull(1)=="6")) return true
   if ((c=="chmod"||c=="chown")&&t.any {it=="-r"||it=="--recursive"}&&t.any {it=="/"||it=="/*"}) return true
   if (c=="rm") {
    val hr=t.any {it=="--recursive"||it.startsWith("-")&&!it.startsWith("--")&&'r' in it}
    val hf=t.any {it=="--force"||it.startsWith("-")&&!it.startsWith("--")&&'f' in it}
    if (hr&&hf&&t.drop(1).any {it in RMD}) return true
   }
  }
  return false
 }
}
