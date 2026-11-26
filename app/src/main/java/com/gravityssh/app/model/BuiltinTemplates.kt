package com.gravityssh.app.model

object BuiltinOpsPresets {
    fun all(): List<BuiltinTemplate> = corePresets() + extraPresets()

    private fun corePresets(): List<BuiltinTemplate> = listOf(
        BuiltinTemplate(
            id = "tpl-bbr",
            presetId = "tpl-bbr",
            name = "开启 BBR 网络加速",
            category = "网络优化",
            description = "配置 fq 队列与 BBR 拥塞控制，独立写入 sysctl.d 并校验生效",
            safetyNote = "使用独立配置文件 /etc/sysctl.d/99-gravityssh-bbr.conf，可随时删除回退",
            command = """
sudo sh -c 'cat >/etc/sysctl.d/99-gravityssh-bbr.conf <<EOF
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
EOF
sysctl --system >/dev/null 2>&1 || sysctl -p /etc/sysctl.d/99-gravityssh-bbr.conf'
sysctl net.ipv4.tcp_congestion_control net.core.default_qdisc
lsmod | grep -i bbr || true
            """.trimIndent()
        ),
        BuiltinTemplate(
            id = "tpl-swap",
            presetId = "tpl-swap",
            name = "安全创建 2G SWAP 虚拟内存",
            category = "系统优化",
            description = "检查现有 SWAP，创建 /swapfile (600 权限) 并备份后写入 /etc/fstab 持久化",
            safetyNote = "已存在 /swapfile 时自动跳过，修改 /etc/fstab 前自动生成 .bak 备份",
            command = """
SWAP_SIZE="2G"
if [ -f /swapfile ]; then echo "/swapfile 已存在，跳过创建"; swapon --show; exit 0; fi
sudo fallocate -l "${'$'}SWAP_SIZE" /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=progress
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
sudo cp -a /etc/fstab /etc/fstab.bak.${'$'}(date +%s)
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
free -h && swapon --show
            """.trimIndent()
        ),
        BuiltinTemplate(
            id = "tpl-mirror",
            presetId = "tpl-mirror",
            name = "一键切换国内镜像源（自动识别系统）",
            category = "系统维护",
            description = "自动识别 Debian / Ubuntu / Alpine，备份原软件源后切换为阿里云镜像并刷新缓存",
            safetyNote = "替换前自动备份原始源配置文件，可随时恢复",
            command = """
if [ -f /etc/os-release ]; then . /etc/os-release; fi
case "${'$'}ID" in
  debian)
    if [ -f /etc/apt/sources.list.d/debian.sources ]; then
      sudo cp -a /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list.d/debian.sources.bak
      sudo sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources
    elif [ -f /etc/apt/sources.list ]; then
      sudo cp -a /etc/apt/sources.list /etc/apt/sources.list.bak
      sudo sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list
    fi
    sudo apt-get update ;;
  ubuntu)
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
      sudo cp -a /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/ubuntu.sources.bak
      sudo sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources
    elif [ -f /etc/apt/sources.list ]; then
      sudo cp -a /etc/apt/sources.list /etc/apt/sources.list.bak
      sudo sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' /etc/apt/sources.list
    fi
    sudo apt-get update ;;
  alpine)
    sudo cp -a /etc/apk/repositories /etc/apk/repositories.bak
    sudo sed -i 's|dl-cdn.alpinelinux.org|mirrors.aliyun.com|g' /etc/apk/repositories
    sudo apk update ;;
  *) echo "未自动适配的系统: ${'$'}{ID:-unknown}，请手动检查软件源" ;;
esac
            """.trimIndent()
        )
    )
}
