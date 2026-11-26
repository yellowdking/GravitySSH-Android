# GravitySSH Android

Modern Antigravity-styled Android companion client for **GravitySSH**, featuring full SSH/SFTP management, Jump Host (Bastion) & SOCKS5/HTTP proxy tunneling, real-time Linux host monitoring, encrypted local vault, and **Zero-Knowledge Sync-Code (`gssh-sync-v1`)** multi-device sync with Windows GravitySSH.

## Key Features
- **Encrypted Local Vault**: Android Keystore AES-256-GCM hardware-backed encryption + optional Master Password & Biometric lock.
- **Zero-Knowledge Multi-Device Sync (`gssh-sync-v1`)**: Ephemeral 8-char sync code (`XXXX-XXXX`) & QR code with PBKDF2-HMAC-SHA256 (200k iterations), GZIP, AES-256-GCM, TLS 1.3 + SPKI SHA-256 pinning, and burn-after-read relay.
- **Full SSH & Terminal Engine**: Multi-tab xterm-256color VT100 terminal, Ed25519 / RSA-4096 key generator & deployer, Jump Host & SOCKS5/HTTP proxy support, Paste Guard, Keyword Highlighting, and Autocomplete.
- **SFTP File Manager**: Directory browsing, upload/download, built-in online text editor (<= 1 MB), compress/extract, permission alignment, and chroot-safe fast delete.
- **Host Monitor & Ops Templates**: Real-time RTT/CPU/RAM/Disk/Net/Load/GPU strip and 6 built-in hardened Linux ops presets.

## Build
```bash
python3 scripts/fetch_fonts.py
gradle testDebugUnitTest assembleDebug assembleRelease
```
