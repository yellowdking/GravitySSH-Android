package com.gravityssh.app.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ResolvedRelayEndpoint(
    val baseUrl: String,
    val hostPort: String,
    val host: String,
    val explicitSpkiPin: String?,
    val isDefaultRelay: Boolean,
    val isRawIp: Boolean
)

object SyncConfig {
    const val DEFAULT_RELAY_URL = "https://43.155.231.14:54843"
    const val CLIENT_HEADER = "GravitySSH/1"
    const val DEFAULT_TTL_SECONDS = 300
    const val PAIR_POLL_INTERVAL_MS = 1500L
    const val PBKDF2_ITERATIONS = 200_000
    const val CHANNEL_PREFIX = "gssh-sync-channel-v1:"
    const val QR_SCHEME_PREFIX = "gssh-sync://v1?code="

    // Live credentials from relay-ops deployment on 43.155.231.14:54843
    const val PINNED_SPKI_SHA256 = "ca1a5f441de4962b6269449325ded3a284a0a8a9d8fd3d995ffcb218c8eb7575"
    const val APP_GATE_KEY = "fb6f39e339dffe1141537e5e8154491cce0044c7bc2ecb26b17828571b9d5aae"

    internal val SPKI_HEX_RE = Regex("^[0-9a-f]{64}$")

    private val IPV4_RE = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val IPV6_CHARS_RE = Regex("^[0-9a-fA-F:.]+$")

    private val defaultHostPortKey: String = run {
        val u = DEFAULT_RELAY_URL.toHttpUrlOrNull()!!
        formatHostPortKey(u.host, u.port)
    }

    fun isRawIpHost(host: String): Boolean {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isEmpty()) return false
        if (IPV4_RE.matches(clean)) return true
        return clean.count { it == ':' } >= 2 && IPV6_CHARS_RE.matches(clean)
    }

    internal fun parseExplicitSpkiFragment(fragment: String?): String? {
        val trimmed = fragment?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        for (part in trimmed.split('&')) {
            val eqIdx = part.indexOf('=')
            if (eqIdx <= 0) continue
            val key = part.substring(0, eqIdx).trim()
            if (key.equals("spki", ignoreCase = true)) {
                val pin = part.substring(eqIdx + 1).trim().lowercase().removePrefix("sha256:")
                if (!SPKI_HEX_RE.matches(pin)) {
                    throw IllegalArgumentException("中继 SPKI 指纹格式无效（应为 64 位 SHA-256 十六进制字符串）")
                }
                return pin
            }
        }
        return null
    }

    fun normalizeRelayUrl(raw: String): String {
        val trimmed = raw.trim()
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
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IllegalArgumentException("中继服务器地址不支持包含用户名或密码")
        }
        val explicitPin = parseExplicitSpkiFragment(parsed.fragment)
        val cleanHost = parsed.host.removeSurrounding("[", "]")
        val hostPart = if (':' in cleanHost) "[$cleanHost]" else cleanHost
        val authority = withScheme.substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val explicitPort = if (authority.startsWith("[")) {
            authority.substringAfter("]", "").startsWith(":")
        } else {
            authority.contains(':')
        }
        val origin = if (parsed.port == 443 && !explicitPort) {
            "https://$hostPart"
        } else {
            "https://$hostPart:${parsed.port}"
        }
        val cleanPath = parsed.encodedPath.trimEnd('/')
        val base = "$origin$cleanPath"
        return if (explicitPin != null) "$base#spki=$explicitPin" else base
    }

    fun parseRelayEndpoint(raw: String): ResolvedRelayEndpoint {
        val normalized = normalizeRelayUrl(raw).ifEmpty { DEFAULT_RELAY_URL }
        val parsed = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("中继地址格式无效，请输入如 1.2.3.4:54843")
        val explicitPin = parseExplicitSpkiFragment(parsed.fragment)
        val baseUrl = normalized.substringBefore('#')
        val cleanHost = parsed.host.removeSurrounding("[", "]")
        val hostPort = formatHostPortKey(cleanHost, parsed.port)
        return ResolvedRelayEndpoint(
            baseUrl = baseUrl,
            hostPort = hostPort,
            host = cleanHost,
            explicitSpkiPin = explicitPin,
            isDefaultRelay = hostPort == defaultHostPortKey,
            isRawIp = isRawIpHost(cleanHost)
        )
    }

    private fun formatHostPortKey(host: String, port: Int): String {
        val clean = host.trim().removeSurrounding("[", "]")
        val hostPart = if (':' in clean) "[$clean]" else clean
        return "$hostPart:$port".lowercase()
    }
}
