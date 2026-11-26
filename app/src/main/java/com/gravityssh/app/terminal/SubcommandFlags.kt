package com.gravityssh.app.terminal

object SubcommandFlags {
  private fun f(t: String, d: String, sp: Boolean = false) = SubcmdCandidate(t, d, sp)

  private val dockerLogs = listOf(
    f("-f", "实时跟踪"),
    f("--tail 100", "最近100行"),
    f("--since 10m", "近10分钟"),
    f("-t", "显示时间戳")
  )

  private val table: Map<String, List<SubcmdCandidate>> = mapOf(
    "ls" to listOf(
      f("-la", "全部详细"),
      f("-lh", "可读大小"),
      f("-lt", "按时间排"),
      f("-ltr", "时间倒序"),
      f("-a", "含隐藏文件")
    ),
    "df" to listOf(f("-h", "可读大小"), f("-Th", "含文件系统")),
    "du" to listOf(f("-sh", "总计大小"), f("-h --max-depth=1", "一级汇总")),
    "ps" to listOf(f("-ef", "完整格式"), f("-aux", "全部进程")),
    "docker ps" to listOf(
      f("-a", "全部容器"),
      f("--format", "输出格式", true),
      f("-q", "仅显示ID"),
      f("--filter", "条件过滤", true)
    ),
    "docker logs" to dockerLogs,
    "docker compose logs" to dockerLogs,
    "docker compose up" to listOf(
      f("-d", "后台运行"),
      f("--build", "重新构建"),
      f("--force-recreate", "强制重建"),
      f("--remove-orphans", "清理孤儿")
    ),
    "docker compose down" to listOf(
      f("-v", "删除数据卷"),
      f("--remove-orphans", "清理孤儿")
    ),
    "docker compose" to listOf(
      f("-f", "指定Compose文件", true),
      f("--env-file", "指定环境文件", true)
    ),
    "curl" to listOf(
      f("-fsSL", "静默跟随", true),
      f("-I", "仅响应头", true),
      f("-k", "忽略证书", true),
      f("-X POST", "POST方法", true),
      f("-H", "请求头", true),
      f("-d", "请求体", true),
      f("-o", "输出文件", true),
      f("-v", "详细调试", true)
    ),
    "tar" to listOf(
      f("-xzvf", "解压tar.gz", true),
      f("-czvf", "打包tar.gz", true),
      f("-tvf", "查看归档", true),
      f("-C", "目标目录", true)
    ),
    "grep" to listOf(
      f("-rn", "递归行号", true),
      f("-i", "忽略大小写", true),
      f("-E", "扩展正则", true),
      f("-v", "反向匹配", true),
      f("-C 3", "上下文3行", true),
      f("--include=", "限定文件名")
    ),
    "find" to listOf(
      f("-name", "按名匹配", true),
      f("-type f", "仅文件"),
      f("-type d", "仅目录"),
      f("-maxdepth", "最大深度", true),
      f("-mtime", "修改天数", true)
    ),
    "journalctl" to listOf(
      f("-u", "指定服务", true),
      f("-xe", "错误详情"),
      f("-f", "实时跟踪"),
      f("--since", "起始时间", true),
      f("--no-pager", "禁用分页"),
      f("-n 100", "最近100行")
    ),
    "systemctl" to listOf(
      f("--now", "立即执行"),
      f("--failed", "仅失败项"),
      f("--type=service", "仅服务"),
      f("--no-pager", "禁用分页")
    ),
    "rsync" to listOf(
      f("-avzP", "归档进度", true),
      f("--delete", "同步删除", true),
      f("--exclude=", "排除路径"),
      f("--dry-run", "模拟演练")
    ),
    "chmod" to listOf(f("-R", "递归修改", true), f("+x", "添加执行", true)),
    "kubectl" to listOf(
      f("-A", "全部命名空间"),
      f("-n", "指定命名空间", true),
      f("-o wide", "扩展输出"),
      f("-f", "指定清单", true)
    ),
    "ss" to listOf(f("-tulpn", "监听端口"), f("-antp", "TCP连接"), f("-s", "统计摘要")),
    "nginx" to listOf(f("-t", "检查语法"), f("-s reload", "重载配置")),
    "firewall-cmd" to listOf(
      f("--list-all", "全部规则"),
      f("--permanent", "永久生效", true),
      f("--add-port=", "开放端口"),
      f("--reload", "重载配置")
    )
  )

  fun matchFlags(eff: List<String>, curTok: String): List<SubcmdCandidate> {
    if (eff.isEmpty() || !curTok.startsWith("-") || '/' in curTok) return emptyList()
    val prior = if (eff.size >= 2 && eff.last() == curTok) eff.dropLast(1) else eff
    val words = prior.filter { !it.startsWith("-") }.map { it.lowercase() }
    if (words.isEmpty()) return emptyList()
    var pool: List<SubcmdCandidate>? = null
    for (len in minOf(words.size, 3) downTo 1) {
      pool = table[words.take(len).joinToString(" ")]
      if (pool != null) break
    }
    if (pool.isNullOrEmpty()) return emptyList()
    val used = HashSet<String>()
    for (t in prior) {
      if (t.startsWith("-")) {
        val low = t.lowercase()
        used.add(low)
        val eq = low.indexOf('=')
        if (eq > 0) used.add(low.substring(0, eq + 1))
      }
    }
    val ql = curTok.lowercase()
    val out = mutableListOf<SubcmdCandidate>()
    for (cand in pool) {
      val low = cand.tokenText.lowercase()
      val head = low.substringBefore(' ')
      if (head in used || low in used) continue
      if (low == ql && !cand.appendSpace) continue
      if (low.startsWith(ql)) out.add(cand)
    }
    return out
  }
}
