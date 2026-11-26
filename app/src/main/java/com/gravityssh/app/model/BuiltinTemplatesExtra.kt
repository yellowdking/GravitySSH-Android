package com.gravityssh.app.model

internal fun extraPresets(): List<BuiltinTemplate> = listOf(
    BuiltinTemplate(
        id = "tpl-docker",
        presetId = "tpl-docker",
        name = "一键安装 Docker 与 Compose",
        category = "Docker",
        description = "安装 Docker Engine 与 Compose 插件，设置开机自启并验证版本",
        safetyNote = "已安装 Docker 时直接显示版本与状态，不重复覆盖",
        command = """
if command -v docker >/dev/null 2>&1; then
  echo "Docker 已安装：${'$'}(docker --version)"
else
  curl -fsSL https://get.docker.com | sudo sh || curl -fsSL https://get.docker.com | sudo sh -s -- --mirror Aliyun
fi
sudo systemctl enable --now docker 2>/dev/null || true
docker --version && docker compose version
        """.trimIndent()
    ),
    BuiltinTemplate(
        id = "tpl-ssh-port",
        presetId = "tpl-ssh-port",
        name = "安全修改 SSH 端口（防锁死 10 分钟自动回滚）",
        category = "安全加固",
        description = "新增新端口并保留旧端口、放行防火墙、语法校验后重载；10 分钟未确认自动回滚",
        safetyNote = "防失联机制：请先用新端口测试连接成功后再执行回显的取消回滚命令",
        command = """
printf "请输入新的 SSH 端口 (1024-65535): "; read NEW_PORT
case "${'$'}NEW_PORT" in ''|*[!0-9]*) echo "端口必须是数字"; exit 1 ;; esac
[ "${'$'}NEW_PORT" -lt 1024 ] || [ "${'$'}NEW_PORT" -gt 65535 ] && { echo "建议使用 1024-65535 之间的端口"; exit 1; }
BAK="/etc/ssh/sshd_config.bak.${'$'}(date +%s)"
sudo cp -a /etc/ssh/sshd_config "${'$'}BAK"
OLD_PORT=${'$'}(sudo sshd -T 2>/dev/null | awk '/^port /{print ${'$'}2; exit}')
OLD_PORT=${'$'}{OLD_PORT:-22}
sudo mkdir -p /etc/ssh/sshd_config.d
printf "Port %s\nPort %s\n" "${'$'}OLD_PORT" "${'$'}NEW_PORT" | sudo tee /etc/ssh/sshd_config.d/99-gravityssh-port.conf >/dev/null
if ! sudo sshd -t; then
  sudo rm -f /etc/ssh/sshd_config.d/99-gravityssh-port.conf
  echo "sshd 配置校验失败，已自动撤销"; exit 1
fi
command -v ufw >/dev/null 2>&1 && sudo ufw allow "${'$'}{NEW_PORT}/tcp" >/dev/null 2>&1 || true
command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --permanent --add-port="${'$'}{NEW_PORT}/tcp" >/dev/null 2>&1 && sudo firewall-cmd --reload >/dev/null 2>&1 || true
sudo systemctl reload sshd 2>/dev/null || sudo systemctl reload ssh 2>/dev/null || sudo service ssh reload
echo "✓ 已同时监听旧端口 ${'$'}{OLD_PORT} 与新端口 ${'$'}{NEW_PORT}（备份: ${'$'}{BAK}）"
(sleep 600 && [ -f /tmp/.gssh_port_pending ] && sudo rm -f /etc/ssh/sshd_config.d/99-gravityssh-port.conf /tmp/.gssh_port_pending && (sudo systemctl reload sshd 2>/dev/null || sudo systemctl reload ssh 2>/dev/null || true)) >/dev/null 2>&1 &
touch /tmp/.gssh_port_pending
echo ">>> 请立即新建会话测试新端口 ${'$'}{NEW_PORT}！确认可连上后执行: rm -f /tmp/.gssh_port_pending"
echo ">>> 10 分钟内未确认将自动回滚至旧端口 ${'$'}{OLD_PORT}。"
        """.trimIndent()
    ),
    BuiltinTemplate(
        id = "tpl-inspect",
        presetId = "tpl-inspect",
        name = "系统全面体检与状态巡检",
        category = "系统维护",
        description = "只读巡检系统内核、负载、内存、磁盘、监听端口与异常服务状态",
        safetyNote = "纯只读巡检命令，不会修改任何系统配置",
        command = """
echo "=== 主机与内核 ==="; uname -a; uptime
echo "=== 内存与 SWAP ==="; free -h
echo "=== 磁盘占用 ==="; df -hT | grep -v tmpfs
echo "=== 监听端口 ==="; ss -tulpn 2>/dev/null | head -n 25 || netstat -tulpn 2>/dev/null | head -n 25
echo "=== 异常服务 ==="; systemctl --failed --no-pager 2>/dev/null || true
        """.trimIndent()
    )
)
