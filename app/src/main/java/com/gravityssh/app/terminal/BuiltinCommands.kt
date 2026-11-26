package com.gravityssh.app.terminal

data class BuiltinCmdEntry(
    val command: String,
    val description: String,
    val category: String
)

object BuiltinCommands {
    val all: List<BuiltinCmdEntry> = listOf(
        BuiltinCmdEntry("apt update && apt upgrade -y", "刷新索引并自动升级系统", "APT"),
        BuiltinCmdEntry("apt install -y ", "安装指定软件包", "APT"),
        BuiltinCmdEntry("apt remove -y ", "卸载指定软件包", "APT"),
        BuiltinCmdEntry("apt autoremove -y", "清理不再需要的依赖包", "APT"),
        BuiltinCmdEntry("apk update && apk upgrade", "更新并升级 Alpine 包", "APK"),
        BuiltinCmdEntry("apk add --no-cache ", "无缓存安装 Alpine 软件包", "APK"),
        BuiltinCmdEntry("dnf update -y", "更新所有软件包", "DNF"),
        BuiltinCmdEntry("docker ps", "查看运行中的容器", "Docker"),
        BuiltinCmdEntry("docker ps -a", "查看所有容器（含已停止）", "Docker"),
        BuiltinCmdEntry("docker images", "列出本地所有镜像", "Docker"),
        BuiltinCmdEntry("docker logs -f --tail 100 ", "实时跟踪容器日志", "Docker"),
        BuiltinCmdEntry("docker exec -it ", "进入容器交互终端", "Docker"),
        BuiltinCmdEntry("docker stats --no-stream", "查看容器资源快照", "Docker"),
        BuiltinCmdEntry("docker system prune -af", "清理未使用镜像/容器/缓存", "Docker"),
        BuiltinCmdEntry("docker compose up -d", "后台启动 Compose 编排", "Docker"),
        BuiltinCmdEntry("docker compose down", "停止并移除 Compose 容器", "Docker"),
        BuiltinCmdEntry("docker compose logs -f --tail 100", "实时跟踪 Compose 日志", "Docker"),
        BuiltinCmdEntry("docker compose pull && docker compose up -d", "拉取新镜像并滚动更新", "Docker"),
        BuiltinCmdEntry("systemctl status ", "查看系统服务运行状态", "Systemd"),
        BuiltinCmdEntry("systemctl restart ", "重启系统服务", "Systemd"),
        BuiltinCmdEntry("systemctl reload ", "平滑重载服务配置", "Systemd"),
        BuiltinCmdEntry("systemctl enable --now ", "设置开机自启并启动", "Systemd"),
        BuiltinCmdEntry("systemctl daemon-reload", "重载 systemd 配置", "Systemd"),
        BuiltinCmdEntry("journalctl -u ", "查看指定服务日志", "Systemd"),
        BuiltinCmdEntry("journalctl -xe --no-pager", "查看最新系统错误日志", "Systemd"),
        BuiltinCmdEntry("git status", "查看仓库工作区状态", "Git"),
        BuiltinCmdEntry("git pull --rebase", "拉取远程更新并变基", "Git"),
        BuiltinCmdEntry("git log --oneline -20", "查看最近 20 条提交", "Git"),
        BuiltinCmdEntry("git diff", "查看未暂存变更", "Git"),
        BuiltinCmdEntry("nginx -t", "测试 Nginx 配置语法", "Nginx"),
        BuiltinCmdEntry("nginx -s reload", "平滑重载 Nginx", "Nginx"),
        BuiltinCmdEntry("ss -tulpn", "查看所有 TCP/UDP 监听端口", "网络"),
        BuiltinCmdEntry("ip a", "查看所有网卡与 IP 地址", "网络"),
        BuiltinCmdEntry("curl -s ifconfig.me", "查看服务器公网出口 IP", "网络"),
        BuiltinCmdEntry("ufw status numbered", "查看 UFW 防火墙规则", "防火墙"),
        BuiltinCmdEntry("ls -la", "列出当前目录全部文件", "文件"),
        BuiltinCmdEntry("df -h", "查看分区磁盘使用率", "系统"),
        BuiltinCmdEntry("du -sh *", "统计当前目录大小", "系统"),
        BuiltinCmdEntry("free -h", "查看内存与 SWAP 占用", "系统"),
        BuiltinCmdEntry("htop", "交互式进程监控", "系统"),
        BuiltinCmdEntry("ps aux | grep ", "检索运行中的进程", "系统"),
        BuiltinCmdEntry("tail -f -n 100 ", "实时追踪文件末尾日志", "文件"),
        BuiltinCmdEntry("tar -czvf ", "打包并用 gzip 压缩", "压缩"),
        BuiltinCmdEntry("tar -xzvf ", "解压 .tar.gz 归档", "压缩"),
        BuiltinCmdEntry("tmux ls", "列出活跃 tmux 会话", "终端"),
        BuiltinCmdEntry("tmux attach -t ", "重新接入 tmux 会话", "终端")
    )
}
