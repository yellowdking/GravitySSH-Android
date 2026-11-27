package com.gravityssh.app.terminal

data class SubcmdCandidate(val tokenText: String, val description: String, val appendSpace: Boolean = false)

object SubcommandData {
  private fun w(x: String) = x.split(' ').toSet()
  val FILE_PRIORITY_CMDS: Set<String> =
    w("cd ls ll la nano vim vi nvim cat less more head tail rm cp mv chmod chown mkdir rmdir pushd tree touch tar unzip gunzip zip gzip zcat 7z rsync grep sed awk find stat file du sh bash zsh python python3 node source .")
  val DIR_ONLY_CMDS: Set<String> = w("cd rmdir pushd tree")
  val SUBCMD_ROOTS: Set<String> =
    w("docker systemctl journalctl apt apt-get apk dnf yum git kubectl pm2 tmux nginx ufw firewall-cmd ss ip curl ps df free kill")
  val FILE_FLAGS: Set<String> = w("-f --file -c --config -i -o --env-file cp add apply")

  private fun s(spec: String): List<SubcmdCandidate> = spec.split(';').map {
    val i = it.indexOf('^'); val r = it.substring(0, i); val sp = r.endsWith(' ')
    SubcmdCandidate(if (sp) r.dropLast(1) else r, it.substring(i + 1), sp)
  }

  private fun pairs(spec: String): List<Pair<String, String>> = spec.split(';').map {
    val i = it.indexOf('^'); it.substring(0, i) to it.substring(i + 1)
  }

  val rootCommands: List<Pair<String, String>> = pairs(
    "docker^容器;systemctl^服务;journalctl^日志;apt^APT;apt-get^APT;apk^APK;dnf^DNF;yum^YUM;git^Git;kubectl^K8s;" +
      "pm2^PM2;tmux^Tmux;nginx^Nginx;ufw^防火墙;free^内存;firewall-cmd^防火墙;ss^端口;ip^网络;curl^HTTP;tar^归档;" +
      "rsync^同步;chmod^权限;find^查找;grep^搜索;ls^目录;df^磁盘;du^大小;ps^进程;cd^切目录;cat^查看;nano^编辑;" +
      "vim^Vim;less^分页;tail^日志;head^头部;rm^删除;cp^复制;mv^移动;mkdir^建目录;chown^属主;" +
      "htop^监控;kill^终止;unzip^解压;make^构建;npm^NPM;node^Node;python3^Python;go^Go"
  )

  private val aptL = s("update^刷源;upgrade -y^升级;update && apt upgrade -y^刷新升级;install -y ^安装;remove -y ^卸载;autoremove -y^清依赖;search ^搜索")
  private val dnfL = s("update -y^更新;install -y ^安装;remove -y ^卸载;search ^搜索")

  val l1: Map<String, List<SubcmdCandidate>> = mapOf(
    "docker" to s("ps -a^全部容器;compose ^编排;logs -f --tail 100 ^实时日志;ps^运行容器;exec -it ^进容器;images^镜像;restart ^重启;pull ^拉镜像;stats --no-stream^资源快照;rm -f ^删容器;inspect ^详情;network ls^网络;volume ls^数据卷;system prune -af^清理"),
    "systemctl" to s("status ^状态;restart ^重启;reload ^重载;start ^启动;stop ^停止;enable --now ^自启;disable ^禁自启;daemon-reload^重载单元;list-units --failed^失败项"),
    "journalctl" to s("-u ^服务日志;-xe --no-pager^错误日志;-f^实时日志;--since \"1 hour ago\"^近1h"),
    "apt" to aptL, "apt-get" to aptL,
    "apk" to s("update^刷索引;upgrade^升级;update && apk upgrade^更新升级;add --no-cache ^安装;del ^卸载;search ^搜索;info^包信息"),
    "dnf" to dnfL, "yum" to dnfL,
    "git" to s("status^状态;pull --rebase^拉取变基;push^推送;log --oneline -20^近20提交;diff^变更;checkout ^切分支;branch -a^分支;add .^暂存全部;commit -m \"\"^提交;stash^暂存"),
    "kubectl" to s("get pods -A^全部Pod;get svc -A^全部Svc;get nodes^节点;logs -f --tail=100 ^日志;describe pod ^详情;apply -f ^应用;exec -it ^进容器"),
    "pm2" to s("list^列表;logs --lines 100^日志;restart ^重启;reload ^重载;stop ^停止;monit^监控;save^保存"),
    "tmux" to s("ls^会话列表;new -s ^新建;attach -t ^接入;kill-session -t ^销毁"),
    "nginx" to s("-t^查语法;-s reload^重载"),
    "ufw" to s("status numbered^规则;allow ^放行;delete ^删除;deny ^拒绝;reload^重载;enable^启用"),
    "firewall-cmd" to s("--list-all^规则;--add-port=^开端口;--permanent ^永久;--reload^重载"),
    "ss" to s("-tulpn^监听;-antp^TCP;-s^统计"),
    "ip" to s("a^网卡IP;route^路由;link^链路;neigh^邻接"),
    "curl" to s("-fsSL ^静默跟随;-I ^响应头;-k ^忽略证书;-X POST ^POST;-s ifconfig.me^公网IP"),
    "tar" to s("-xzvf ^解压gz;-czvf ^打包gz;-tvf ^查归档"),
    "rsync" to s("-avzP ^归档同步;--delete ^同步删"),
    "chmod" to s("+x ^加执行;-R ^递归;755 ^目录权限;644 ^文件权限"),
    "find" to s(". -name ^按名找;. -type f ^找文件;. -mtime -1^近24h"),
    "grep" to s("-rn ^递归行号;-i ^忽略大小写;-E ^正则"),
    "ls" to s("-la^全详细;-lh^可读;-lt^按时间"),
    "tail" to s("-f -n 100 ^实时日志;-n 50 ^末尾50行"),
    "df" to s("-h^可读;-Th^含类型"),
    "du" to s("-sh *^当前大小;-h --max-depth=1^一级汇总"),
    "ps" to s("aux^全部进程;aux | grep ^搜进程;-ef^完整"),
    "free" to s("-h^人类可读;-m^MB显示;-g^GB显示;-h -s 2^每2秒刷新")
  )

  val l2: Map<String, List<SubcmdCandidate>> = mapOf(
    "docker compose" to s("up -d^后台启动;down^停止移除;pull^拉镜像;logs -f --tail 100^实时日志;restart^重启;ps^状态;build^构建;exec ^进服务;up -d --force-recreate^强制重建;pull && docker compose up -d^拉取滚动更新"),
    "docker system" to s("prune -af^清理资源;df^磁盘占用"),
    "docker network" to s("ls^网络列表;inspect ^网络详情"),
    "docker volume" to s("ls^卷列表;prune^清理卷"),
    "kubectl get" to s("pods -A^全部Pod;svc -A^全部Svc;deploy -A^全部Deploy;nodes^节点")
  )
}
