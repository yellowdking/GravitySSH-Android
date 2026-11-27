# GravitySSH Android

Modern Antigravity-styled Android companion client for **GravitySSH** (Android 10+ / `minSdk 29`), featuring full SSH/SFTP management, Jump Host (Bastion) & SOCKS5/HTTP proxy tunneling, real-time Linux host monitoring, encrypted local vault, and **Zero-Knowledge Mutual-Pairing Sync (`gssh-sync-v2`)** with Windows GravitySSH.

## Key Features
- **Encrypted Local Vault**: Android Keystore AES-256-GCM hardware-backed encryption + optional Master Password & Biometric lock.
- **Zero-Knowledge Multi-Device Sync (`gssh-sync-v2`)**: Dual-key mutual-pairing sync code (`XXXX-XXXX` send code + receiver confirmation code) & QR code with PBKDF2-HMAC-SHA256 (200k iterations), GZIP, AES-256-GCM, pure TLS 1.3 (`minSdk 29`), SPKI SHA-256 pinning (built-in VPS2 relay `43.155.231.14:54843` + custom `#spki=<64-hex>` / raw-IP SPKI TOFU), and burn-after-read relay.
- **Full SSH & Terminal Engine**: Multi-tab xterm-256color VT100 terminal, Ed25519 / RSA-4096 key generator & deployer, IPv6 + Jump Host & SOCKS5/HTTP proxy support, Paste Guard, Keyword Highlighting, and Autocomplete.
- **SFTP File Manager**: Directory browsing, upload/download, built-in atomic online text editor (<= 1 MB), compress/extract, permission alignment, and chroot-safe fast delete.
- **Host Monitor & Ops Templates**: Real-time RTT/CPU/RAM/Disk/Net/Load/GPU strip and 6 built-in hardened Linux ops presets.

## Requirements
- Android 10 (API 29) or higher (`minSdk = 29`, `targetSdk = 35`) for native TLS 1.3 support.

## Build
```bash
python3 scripts/fetch_fonts.py
gradle testDebugUnitTest lintDebug assembleDebug assembleRelease
```
