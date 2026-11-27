package com.gravityssh.app.terminal

data class CompletionEdit(val backspaces:Int,val insertText:String)
data class PathQuery(val dirToList:String,val namePrefix:String,val tokenPrefixDir:String,val dirsOnly:Boolean)
data class ParsedToken(val raw:String="",val unquoted:String="",val start:Int=0,val end:Int=0,val quote:Char='\u0000')
data class ParsedLine(
 val segPrefix:String,val activeSeg:String,val tokPrefix:String,val tokens:List<ParsedToken>,
 val effective:List<String>,val curTok:ParsedToken,val prevTok:String,val endsWithSpace:Boolean,val pathQuery:PathQuery?
)

object AutocompleteParser {
 private const val NQ='\u0000'
 private const val META=" \t'\"\\\$`()[]{}*?!#&|;<>="
 private fun w(s:String)=s.split(' ').toSet()
 private val EK=Regex("^[A-Za-z_]\\w*=.*")
 private val WT=Regex("\\S+\\s*$")
 private val WR=w("command time nohup")
 private val PF=w("./ ../ / ~/")
 private val RST=w("A B C D H F 1~ 4~")
 private val WF=mapOf("sudo" to (w("-u -g -C -p -t -h") to w("-E -H -i -s -n -k -A -b -v")),"env" to (w("-u -C --unset --chdir") to w("-i -0 --ignore-environment -")))
 fun parseLine(input: String, cwd: String, homeDir: String = "/"): ParsedLine {
  val s=input;var q=NQ;var e=false;var s0=0;var i=0;val n=s.length
  while (i<n) {
   val c=s[i++]
   when {
    e->e=false
    q=='\''->if (c=='\'') q=NQ
    c=='\\'->e=true
    q=='"'->if (c=='"') q=NQ
    c=='\''||c=='"'->q=c
    c==';'->s0=i
    c=='&'&&i<n&&s[i]=='&'->s0=++i
    c=='|'->s0=if (i<n&&s[i] in "|&") ++i else i
   }
  }
  while (s0<n&&s[s0] in " \t") s0++
  val p=s.take(s0);val seg=s.drop(s0)
  if (seg.isEmpty()) return ParsedLine(p,"",p,emptyList(),emptyList(),ParsedToken(),"",false,null)
  val ts=ArrayList<ParsedToken>();var j=0;val m=seg.length
  while (j<m) {
   while (j<m&&seg[j] in " \t") j++;if (j>=m) break
   val st=j;val b=StringBuilder();var tq=NQ;var q0=NQ
   while (j<m) {
    val c=seg[j++]
    when {
     tq=='\''->if (c=='\'') tq=NQ else b.append(c)
     c=='\\'&&j<m->b.append(seg[j++])
     tq=='"'->if (c=='"') tq=NQ else b.append(c)
     c in " \t"->{j--;break}
     c=='\''||c=='"'->{tq=c;if (q0==NQ&&b.isEmpty()) q0=c}
     else->b.append(c)
    }
   }
   ts.add(ParsedToken(seg.substring(st,j),b.toString(),st,j,if (tq!=NQ) tq else q0))
  }
  val sp=ts.last().end<m;val cur=if (sp) ParsedToken("","",m,m) else ts.last()
  val prv=if (sp) ts.last().unquoted else ts.getOrNull(ts.size-2)?.unquoted?:""
  val eff=run {
   val l=if (sp) ts.size else maxOf(0,ts.size-1);var k=0
   while (k<l) {
    val t=ts[k].unquoted;val tl=t.lowercase()
    if (EK.matches(t)||tl in WR) {k++;continue}
    val (vf,bf)=WF[tl]?:break;k++
    while (k<l) when (ts[k].unquoted) {
     "--"->{k++;break}
     in vf->if (k+1<l) k+=2 else return@run emptyList<String>()
     in bf->k++
     else->break
    }
   }
   ts.subList(k,ts.size).map {it.unquoted}
  }
  val pq=run {
   val t=cur.unquoted;if (t.startsWith("-")&&'/' !in t) return@run null
   val sd=SubcommandData
   val d=if (eff.isEmpty()||eff.size==1&&!sp) {if (PF.none(t::startsWith)) return@run null;false} else {
    val c=eff[0].lowercase();val sl='/' in t||t=="."||t=="..";val ff=prv in sd.FILE_FLAGS
    if (!sl&&!ff&&(c in sd.SUBCMD_ROOTS||t.isEmpty()&&c !in sd.FILE_PRIORITY_CMDS)) return@run null
    c in sd.DIR_ONLY_CMDS
   }
   val r=t.removePrefix("~/");val tl=r.length<t.length;val base=normalizePath(if (tl) homeDir else cwd)
   val px=if (tl) "~/" else "";val k=r.lastIndexOf('/')
   if (k<0) return@run PathQuery(base,r,px,d)
   val dp=r.take(k)
   PathQuery(normalizePath(if (!tl&&r.startsWith("/")) dp.ifEmpty {"/"} else "$base/$dp"),r.drop(k+1),px+r.take(k+1),d)
  }
  return ParsedLine(p,seg,p+seg.take(cur.start),ts,eff,cur,prv,sp,pq)
 }
 fun normalizePath(p:String)=ArrayDeque<String>().apply {for (s in p.ifBlank {"/"}.split('/')) if (s=="..") removeLastOrNull() else if (s.isNotEmpty()&&s!=".") addLast(s)}.joinToString("/","/")
 private fun esc(s:String,cs:String)=buildString {for (c in s) {if (c in cs) append('\\');append(c)}}
 fun escapePathToken(tokenDir:String,name:String,isDir:Boolean,quote:Char):String {
  val p=tokenDir+name+if (isDir) "/" else "";val cl=if (isDir) "" else "$quote"
  if (quote=='\'') return "'"+p.replace("'","'\\''")+cl
  if (quote=='"') return "\""+esc(p,"\\\"\$`!")+cl
  val b=p.removePrefix("~/");val tl=b.length<p.length
  return if (b.none {it in META}) p else (if (tl) "~/" else "")+esc(b,META)
 }
 fun computeCompletionInsert(currentLine:String,targetLine:String):CompletionEdit {
  val a=currentLine;val b=targetLine;var i=0;val n=minOf(a.length,b.length)
  while (i<n) {val c=a.codePointAt(i);if (c!=b.codePointAt(i)) break;i+=Character.charCount(c)}
  return CompletionEdit(a.codePointCount(i,a.length),b.drop(i))
 }
 fun applyTerminalInputToLine(currentLine:String,rawData:String):String {
  val r=rawData
  if (r.startsWith("\u001B")) return if (r.length in 3..4&&r[1] in "[O"&&r.drop(2) in RST) "" else currentLine
  return r.fold(currentLine) {a,ch->when (val c=ch.code) {
   10,13,3,4,21,9->""
   127,8->if (a.isEmpty()) "" else a.take(a.offsetByCodePoints(a.length,-1))
   23->a.replace(WT,"")
   else->if (c>=32) a+ch else a
  }}
 }
 fun activeTokenPrefix(input:String,source:String):String {
  if (input.isBlank()) return ""
  val p=parseLine(input,"/");val u=if (p.endsWithSpace) "" else p.curTok.unquoted
  return when (source) {"history","history_local"->p.activeSeg;"dir","file"->u.substringAfterLast('/');else->u}
 }
}
