package com.gravityssh.app.terminal

import com.gravityssh.app.model.*
import com.gravityssh.app.terminal.AutocompleteContext as Ctx
import com.gravityssh.app.terminal.AutocompleteParser as Parser
import com.gravityssh.app.terminal.SubcommandCatalog as Cat

object AutocompleteEngine {

 fun resolvePathQuery(input: String, currentDir: String, homeDir: String = "/"): PathQuery? =
  Parser.parseLine(input, currentDir, homeDir).pathQuery

 fun computeCompletionInsert(currentLine: String, fullCommand: String): CompletionEdit =
  Parser.computeCompletionInsert(currentLine, fullCommand)

 fun applyTerminalInputToLine(currentLine: String, rawData: String): String =
  Parser.applyTerminalInputToLine(currentLine, rawData)

 @Suppress("UNUSED_PARAMETER")
 fun suggest(
  input: String,
  shortcuts: List<CommandShortcut> = emptyList(),
  history: List<CommandRecord> = emptyList(),
  settings: AppSettings = AppSettings(),
  limit: Int = 3,
  remoteEntries: List<SFTPFileEntry> = emptyList(),
  currentDir: String = "/",
  homeDir: String = "/",
  currentServerId: String = "",
  cwdEntries: List<SFTPFileEntry> = emptyList()
 ): List<CompletionItem> {
  if (!settings.autocompleteEnabled||limit<=0||input.isBlank()) return emptyList()
  val p=Parser.parseLine(input,currentDir,homeDir)
  if (p.activeSeg.isEmpty()) return emptyList()

  val cwd=normDir(currentDir);val pq=p.pathQuery
  val sig=Ctx.scanFolderSignals(cwdEntries.ifEmpty {if (pq!=null&&pq.dirToList==cwd) remoteEntries else emptyList()},cwd)
  val best=LinkedHashMap<String,CompletionItem>()
  fun put(c:String,d:String,cat:String,src:String,ins:String,sc:Int) {
   val k=ins.trim().lowercase();val ex=best[k]
   if (ex==null||sc>ex.score) best[k]=CompletionItem(c,d,cat,src,ins,sc)
  }

  var s1=0
  if (pq!=null) {
   if (!p.endsWithSpace&&p.curTok.unquoted=="~"&&p.effective.size>=2) {put("~/","目录","目录","dir",p.tokPrefix+"~/",880);s1++}
   val pool=if (remoteEntries.isNotEmpty()) remoteEntries else if (pq.dirToList==cwd) cwdEntries else emptyList()
   if (pool.isNotEmpty()) {
    val np=pq.namePrefix.lowercase();val hid=np.startsWith(".")||p.prevTok.equals("--env-file",true)
    val first=p.effective.isEmpty()||p.effective.size==1&&!p.endsWithSpace
    val cmd=if (first) "" else p.effective[0].lowercase()
    for (e in pool) {
     if (e.name=="."||e.name==".."||!hid&&e.name.startsWith(".")||pq.dirsOnly&&!e.isDir) continue
     val nl=e.name.lowercase()
     val mp=when {np.isEmpty()->80;nl==np->if (e.isDir) 100 else continue;nl.startsWith(np)->100-(e.name.length-np.length).coerceAtMost(40);else->continue}
     val db=if (!e.isDir) 0 else if (pq.dirsOnly) 120 else if (cmd=="chmod") 0 else 30
     val sc=(700+db+mp+Ctx.fileExtDelta(cmd,p.prevTok,e,first)).coerceIn(550,960)
     val esc=Parser.escapePathToken(pq.tokenPrefixDir,e.name,e.isDir,p.curTok.quote)
     val lbl=if (e.isDir) "目录" else "文件"
     put(if (e.isDir) "${e.name}/" else e.name,lbl,lbl,if (e.isDir) "dir" else "file",p.tokPrefix+esc,sc);s1++
    }
   }
  }

  val inFlag=!p.endsWithSpace&&p.curTok.unquoted.startsWith("-")&&'/' !in p.curTok.unquoted&&p.effective.size>=2
  if (inFlag) {
   Cat.matchFlags(p.effective,p.curTok.unquoted).forEachIndexed {i,c->
    put(c.tokenText,c.description,"参数","flag",p.tokPrefix+c.tokenText+if (c.appendSpace) " " else "",(640-i*6).coerceIn(560,660))
   }
  }

  val multi=p.effective.size>=2||p.effective.size==1&&p.endsWithSpace
  val pBlk=pq!=null&&(s1>0||'/' in p.curTok.unquoted||p.prevTok in Cat.FILE_FLAGS)
  if (!inFlag&&multi&&!pBlk) {
   val cp=Cat.cmdPath(p.effective,p.endsWithSpace);val cat=p.effective[0].lowercase()
   val base=Cat.matchSubcommands(p.effective,p.endsWithSpace)
   val pfx=if (p.endsWithSpace) "" else p.curTok.unquoted.lowercase()
   val cands=if (sig.hasDockerfile&&cp=="docker"&&"build -t".startsWith(pfx)&&base.none {it.tokenText.startsWith("build")}) base+SubcmdCandidate("build -t","构建镜像",true) else base
   cands.forEachIndexed {i,c->
    val sc=(560-i*5+Ctx.folderSubcmdBoost(cp,c.tokenText,sig)).coerceIn(480,680)
    put(c.tokenText,c.description,cat,"subcmd",p.tokPrefix+c.tokenText+if (c.appendSpace) " " else "",sc)
   }
  }

  if (settings.autocompleteFromHistory) {
   for (h in Ctx.rankHistory(p,input,currentServerId,history,limit+4)) put(h.command,h.description,h.category,h.source,h.insertText,h.score)
  }

  if (!p.endsWithSpace&&p.effective.size==1&&pq==null&&p.effective[0].isNotEmpty()) {
   val q1=p.effective[0].lowercase()
   Ctx.folderRootSuggestions(q1,sig).forEachIndexed {i,c->
    put(c.tokenText,c.description,c.tokenText.substringBefore(' '),"builtin",p.tokPrefix+c.tokenText+if (c.appendSpace) " " else "",(470-i*10).coerceIn(440,470))
   }
   for ((cmd,desc) in Cat.rootCommands) if (cmd.startsWith(q1)&&cmd!=q1) put(cmd,desc,cmd,"builtin","${p.tokPrefix}$cmd ",(340-(cmd.length-q1.length).coerceAtMost(40)).coerceIn(240,340))
   if (q1.length>=2) for (b in BuiltinCommands.all) {
    val bl=b.command.trimEnd().lowercase()
    if (bl.startsWith(q1)&&bl!=q1) put(b.command.trimEnd(),b.description,b.category,"builtin",p.tokPrefix+b.command,(280-(bl.length-q1.length).coerceAtMost(40)).coerceIn(240,340))
   }
  }

  return best.values.sortedByDescending {it.score}.take(limit)
 }

 private fun normDir(p:String)=ArrayDeque<String>().apply {for (s in p.ifBlank {"/"}.split('/')) if (s=="..") removeLastOrNull() else if (s.isNotEmpty()&&s!=".") addLast(s)}.joinToString("/","/")
}
