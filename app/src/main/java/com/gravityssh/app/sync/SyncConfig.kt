package com.gravityssh.app.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SyncConfig {
    const val DEFAULT_RELAY_URL = "https://107.172.98.169:54843"
    const val CLIENT_HEADER = "GravitySSH/1"
    const val DEFAULT_TTL_SECONDS = 300
    const val PBKDF2_ITERATIONS = 200_000
    const val CHANNEL_PREFIX = "gssh-sync-channel-v1:"
    const val QR_SCHEME_PREFIX = "gssh-sync://v1?code="

    // Live credentials from relay-ops deployment on 107.172.98.169:54843
    const val PINNED_SPKI_SHA256 = "8d02a67e23e9f5af5922a8e700a6309dfa4ce335baca7125452fe22f4e5e29ba"
    const val APP_GATE_KEY = "fb6f39e339dffe1141537e5e8154491cce0044c7bc2ecb26b17828571b9d5aae"

    private val IPV4_RE = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val IPV6_CHARS_RE = Regex("^[0-9a-fA-F:.]+$")

    fun isRawIpHost(host: String): Boolean {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isEmpty()) return false
        if (IPV4_RE.matches(clean)) return true
        return clean.count { it == ':' } >= 2 && IPV6_CHARS_RE.matches(clean)
    }

    fun normalizeRelayUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("同步中继仅支持 HTTPS，禁止使用明文 HTTP")
        }
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val parsed = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("中继地址格式无效，请输入如 1.2.3.4:54843")
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isBlank()) {
            throw IllegalArgumentException("同步中继仅支持有效的 HTTPS 地址")
        }
        val hostPart = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
        val authority = withScheme.substringAfter("://").substringBefore('/')
        val explicitPort = if (authority.startsWith("[")) {
            authority.substringAfter("]", "").startsWith(":")
        } else {
            authority.contains(':')
        }
        return if (parsed.port == 443 && !explicitPort) {
            "https://$hostPart"
        } else {
            "https://$hostPart:${parsed.port}"
        }
    }
}
