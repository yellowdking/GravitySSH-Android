package com.gravityssh.app.terminal

data class SubcmdCandidate(val tokenText: String, val description: String, val appendSpace: Boolean = false)

object SubcommandCatalog {
  val FILE_PRIORITY_CMDS: Set<String> =
    "cd ls ll la nano vim vi cat less head tail rm cp mv chmod chown mkdir rmdir touch tar unzip zip grep find stat file du sh bash python python3 source .".split(' ').toSet()
  val DIR_ONLY_CMDS: Set<String> = setOf("cd", "rmdir", "pushd")
  internal val FILE_FLAGS = "-f --file -c -i -o --config --env-file".split(' ').toSet()
  internal val rootHints = listOf(
    "docker" to "容器", "systemctl" to "服务", "git" to "仓库", "apt" to "包管理",
    "journalctl" to "日志", "nginx" to "Nginx", "tar" to "归档", "ufw" to "防火墙", "tmux" to "Tmux"
  )

  private fun c(t: String, d: String, sp: Boolean = false) = SubcmdCandidate(t, d, sp)
  private val apt = listOf(
    c("update && apt upgrade -y", "刷新并升级"), c("install -y", "安装软件", true),
    c("remove -y", "卸载软件", true), c("autoremove -y", "清理依赖"), c("search", "搜索软件", true)
  )

  private val l1 = mapOf(
    "docker" to listOf(
      c("ps -a", "查看全部容器"), c("compose", "Compose 编排", true),
      c("logs -f --tail 100", "实时容器日志", true), c("exec -it", "进入容器终端", true),
      c("images", "查看本地镜像"), c("restart", "重启容器", true),
      c("pull", "拉取镜像", true), c("stats", "容器资源监控"),
      c("rm -f", "强制删除容器", true), c("container ls -a", "列出所有容器"),
      c("cp", "容器与宿主机拷贝", true), c("system prune -af", "清理无用资源")
    ),
    "systemctl" to listOf(
      c("status", "查看状态", true), c("restart", "重启服务", true), c("reload", "重载配置", true),
      c("start", "启动服务", true), c("stop", "停止服务", true), c("enable --now", "自启并启动", true),
      c("disable", "取消自启", true), c("daemon-reload", "重载配置"), c("list-units --failed", "失败服务")
    ),
    "git" to listOf(
      c("status", "工作区状态"), c("pull", "拉取更新"), c("log --oneline -20", "最近提交"),
      c("diff", "未暂存变更"), c("checkout", "切换分支", true), c("branch -a", "全部分支"),
      c("add .", "暂存全部"), c("commit -m \"\"", "提交更改"), c("push", "推送远程")
    ),
    "apt" to apt, "apt-get" to apt,
    "journalctl" to listOf(c("-u", "服务日志", true), c("-xe --no-pager", "错误日志"), c("-f", "实时日志"), c("--since \"1 hour ago\"", "近1小时")),
    "nginx" to listOf(c("-t", "检查语法"), c("-s reload", "重载配置")),
    "tar" to listOf(c("-xzvf", "解压tar.gz", true), c("-czvf", "打包tar.gz", true), c("-tvf", "查看归档", true)),
    "ufw" to listOf(c("status numbered", "查看规则"), c("allow", "放行端口", true), c("delete", "删除规则", true), c("reload", "重载")),
    "tmux" to listOf(c("ls", "列出会话"), c("attach -t", "接入会话", true), c("new -s", "新建会话", true))
  )

  private val l2 = mapOf(
    ("docker" to "compose") to listOf(
      c("up -d", "后台启动编排"), c("down", "停止并移除容器"), c("pull", "拉取最新镜像"),
      c("logs -f --tail 100", "实时查看日志"), c("restart", "重启服务"),
      c("ps", "查看编排状态"), c("build", "重新构建镜像"), c("up -d --force-recreate", "重建并启动")
    ),
    ("docker" to "ps") to listOf(c("-a", "全部容器"), c("--format", "输出格式", true)),
    ("docker" to "logs") to listOf(c("-f --tail 100", "实时日志", true), c("--since 10m", "近10分钟", true)),
    ("docker" to "system") to listOf(c("prune -af", "清理资源"), c("df", "磁盘占用"))
  )

  fun matchSubcommands(tokens: List<String>, endsWithSpace: Boolean): List<SubcmdCandidate> {
    if (tokens.isEmpty()) return emptyList()
    val eff = if (tokens[0].equals("sudo", true) && tokens.size >= 2) tokens.drop(1) else tokens
    val (pool, pfx) = when {
      eff.size == 1 && endsWithSpace -> l1[eff[0].lowercase()] to ""
      eff.size == 2 && !endsWithSpace -> l1[eff[0].lowercase()] to eff[1].lowercase()
      eff.size == 2 && endsWithSpace -> l2[eff[0].lowercase() to eff[1].lowercase()] to ""
      eff.size == 3 && !endsWithSpace -> l2[eff[0].lowercase() to eff[1].lowercase()] to eff[2].lowercase()
      else -> null to ""
    }
    if (pool.isNullOrEmpty()) return emptyList()
    if (pfx.isEmpty()) return pool
    val a = mutableListOf<SubcmdCandidate>()
    val b = mutableListOf<SubcmdCandidate>()
    for (x in pool) {
      val low = x.tokenText.lowercase()
      if (low == pfx && !x.appendSpace) continue
      if (low.startsWith(pfx)) a.add(x) else if (low.contains(pfx)) b.add(x)
    }
    return a + b
  }

  internal fun buildPathQuery(tok: String, curDir: String, dirsOnly: Boolean): PathQuery {
    val base = norm(curDir.ifBlank { "/" })
    val sl = tok.lastIndexOf('/')
    if (sl < 0) return PathQuery(base, tok, "", dirsOnly)
    val dp = tok.substring(0, sl)
    return PathQuery(norm(if (tok.startsWith("/")) dp.ifEmpty { "/" } else "$base/$dp"), tok.substring(sl + 1), tok.substring(0, sl + 1), dirsOnly)
  }

  private fun norm(p: String): String {
    val st = ArrayDeque<String>()
    for (s in p.split('/')) when (s) { "", "." -> Unit; ".." -> if (st.isNotEmpty()) st.removeLast(); else -> st.addLast(s) }
    return if (st.isEmpty()) "/" else "/" + st.joinToString("/")
  }
}
