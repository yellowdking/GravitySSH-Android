package com.gravityssh.app.sync

import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object SyncHttpClients {
    private const val SAN_TYPE_IP_ADDRESS = 7
    private const val MAX_CUSTOM_RELAY_TOFU_PINS = 64

    private val tofuLock = Any()
    private val customRelayTofuPins = LinkedHashMap<String, String>()

    internal val tls13Spec: ConnectionSpec =
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3)
            .build()

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionSpecs(listOf(tls13Spec))
            .build()
    }

    fun clearCustomRelayTofuPins() {
        synchronized(tofuLock) {
            customRelayTofuPins.clear()
        }
    }

    internal fun spkiSha256Hex(cert: X509Certificate): String =
        SyncCryptoPrimitives.sha256Hex(cert.publicKey.encoded).lowercase()

    internal fun verifyPinnedSpkiCert(chain: Array<X509Certificate>, expectedSpkiHex: String) =
        verifyPinnedSpkiCerts(chain, listOf(expectedSpkiHex))

    internal fun verifyPinnedSpkiCerts(chain: Array<X509Certificate>, expectedSpkiHexes: List<String>) {
        if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
        if (expectedSpkiHexes.isEmpty()) throw CertificateException("中继 SPKI 指纹未配置")
        chain[0].checkValidity()
        val spkiHash = spkiSha256Hex(chain[0]).toByteArray(Charsets.UTF_8)
        var matched = false
        for (expected in expectedSpkiHexes) {
            val expectedBytes = expected.trim().lowercase().removePrefix("sha256:").toByteArray(Charsets.UTF_8)
            if (MessageDigest.isEqual(spkiHash, expectedBytes)) {
                matched = true
            }
        }
        if (!matched) {
            throw CertificateException("中继服务器证书指纹校验失败（SPKI Pin 不匹配）")
        }
    }

    internal fun verifySelfSignedIpLeafCert(cert: X509Certificate, expectedIp: String) {
        cert.checkValidity()
        val cleanExpectedIp = expectedIp.trim().removeSurrounding("[", "]")
        if (cleanExpectedIp.isEmpty()) return
        val sanEntries = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
        val ipSans = sanEntries.mapNotNull { entry ->
            if (entry.size >= 2 && (entry[0] as? Int) == SAN_TYPE_IP_ADDRESS) {
                (entry[1] as? String)?.trim()?.removeSurrounding("[", "]")
            } else {
                null
            }
        }
        // Only enforce IP SAN verification when the peer cert actually carries IP SAN entries,
        // so standard self-hosted bbplin/sshsync certs with DNS:sshsync work out-of-the-box
        // under TLS 1.3 + SPKI TOFU pinning (or explicit #spki=).
        if (ipSans.isNotEmpty()) {
            val expectedBytes = parseRawIpBytes(cleanExpectedIp)
            val matched = ipSans.any { san ->
                if (expectedBytes != null) {
                    val sanBytes = parseRawIpBytes(san)
                    sanBytes != null && expectedBytes.contentEquals(sanBytes)
                } else {
                    san.equals(cleanExpectedIp, ignoreCase = true)
                }
            }
            if (!matched) {
                throw CertificateException("自定义中继 TLS 证书 SAN 与目标 IP ($cleanExpectedIp) 不匹配")
            }
        }
    }

    internal fun verifyAndPinCustomRelayTofu(hostPortKey: String, cert: X509Certificate, expectedIp: String) {
        verifySelfSignedIpLeafCert(cert, expectedIp)
        val gotHex = spkiSha256Hex(cert)
        val gotBytes = gotHex.toByteArray(Charsets.UTF_8)
        val key = hostPortKey.trim().lowercase()
        synchronized(tofuLock) {
            val existing = customRelayTofuPins[key]
            if (existing != null) {
                if (!MessageDigest.isEqual(gotBytes, existing.toByteArray(Charsets.UTF_8))) {
                    throw CertificateException("自定义中继证书指纹发生变更（TOFU 校验失败），请核实中继服务器或使用 #spki= 显式固定指纹")
                }
                return
            }
            if (customRelayTofuPins.size >= MAX_CUSTOM_RELAY_TOFU_PINS) {
                val eldestKey = customRelayTofuPins.entries.iterator().next().key
                customRelayTofuPins.remove(eldestKey)
            }
            customRelayTofuPins[key] = gotHex
        }
    }

    internal fun buildPinnedClient(
        pinnedSpkiSha256: String,
        expectedHost: String = SyncConfig.DEFAULT_RELAY_URL.toHttpUrl().host
    ): OkHttpClient = buildPinnedClient(SyncConfig.parseSpkiPinList(pinnedSpkiSha256), expectedHost)

    internal fun buildPinnedClient(
        pinnedSpkiSha256List: List<String>,
        expectedHost: String = SyncConfig.DEFAULT_RELAY_URL.toHttpUrl().host
    ): OkHttpClient {
        val pins = SyncConfig.parseSpkiPinList(pinnedSpkiSha256List.joinToString(","))
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                verifyPinnedSpkiCerts(chain, pins)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        val cleanExpected = expectedHost.trim().removeSurrounding("[", "]")

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> hostsMatch(hostname, cleanExpected) }
            .build()
    }

    internal fun buildSelfSignedIpClient(targetHostPort: String, targetIp: String): OkHttpClient {
        val cleanIp = targetIp.trim().removeSurrounding("[", "]")
        val key = targetHostPort.trim().lowercase()
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
                verifyAndPinCustomRelayTofu(key, chain[0], cleanIp)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> SyncConfig.isRawIpHost(hostname) && hostsMatch(hostname, cleanIp) }
            .build()
    }

    private fun hostsMatch(actual: String, expected: String): Boolean {
        val a = actual.trim().removeSurrounding("[", "]")
        val e = expected.trim().removeSurrounding("[", "]")
        if (a.equals(e, ignoreCase = true)) return true
        val ab = parseRawIpBytes(a)
        val eb = parseRawIpBytes(e)
        return ab != null && eb != null && ab.contentEquals(eb)
    }

    private fun parseRawIpBytes(host: String): ByteArray? {
        val clean = host.trim().removeSurrounding("[", "]")
        if (!SyncConfig.isRawIpHost(clean)) return null
        return runCatching { InetAddress.getByName(clean).address }.getOrNull()
    }
}
