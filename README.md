# GravitySSH Android

Android companion client for **GravitySSH** (`minSdk 29`), featuring SSH/SFTP management, Jump Host & SOCKS5/HTTP proxy tunneling, host monitoring, local vault encryption, and multi-device sync with Windows GravitySSH.

## Key Features
- **Local Vault**: Android Keystore encryption + optional Master Password & Biometric lock.
- **Multi-Device Sync**: Paired device push, offline inbox, QR code & 8-char room transfer over default or self-hosted relay.
- **SSH & Terminal**: Multi-tab xterm-256color VT100 terminal, Ed25519 / RSA-4096 key generator & deployer, IPv6 + Jump Host & SOCKS5/HTTP proxy, Paste Guard, Keyword Highlighting, and Autocomplete.
- **SFTP File Manager**: Directory browsing, upload/download, online text editor (<= 1 MB), compress/extract, permissions, and fast delete.
- **Host Monitor & Templates**: Real-time RTT/CPU/RAM/Disk/Net/Load/GPU strip and built-in Linux ops presets.

## Self-Hosted Sync Relay (Optional)
```bash
docker run --rm -v synctool_data:/data bbplin/synctool:latest init --public-host <公网IP>
docker run -d --name synctool-relay --restart unless-stopped -p 54843:54843 -v synctool_data:/data bbplin/synctool:latest
```

## Build
```bash
python3 scripts/fetch_fonts.py
gradle :synctool-sdk:test testDebugUnitTest lintDebug assembleDebug assembleRelease
```
