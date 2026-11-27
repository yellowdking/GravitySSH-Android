package com.gravityssh.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@Serializable
data class DropRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String,
    @SerialName("ttlSeconds") val ttlSeconds: Int = SyncConfig.DEFAULT_TTL_SECONDS
)

@Serializable
data class DropResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("expiresAt") val expiresAt: String = ""
)

@Serializable
data class ChannelRequest(
    @SerialName("channelId") val channelId: String
)

@Serializable
data class PickupResponse(
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String
)

@Serializable
data class PairInitRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String,
    @SerialName("ttlSeconds") val ttlSeconds: Int = SyncConfig.DEFAULT_TTL_SECONDS
)

@Serializable
data class PairInitResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("expiresAt") val expiresAt: String = ""
)

@Serializable
data class PairRequestBody(
    @SerialName("channelId") val channelId: String,
    @SerialName("requestId") val requestId: String,
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String
)

@Serializable
data class PairRequestResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("expiresAt") val expiresAt: String = ""
)

@Serializable
data class PairSenderTokenRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String
)

@Serializable
data class PairPollResponse(
    @SerialName("state") val state: String = "",
    @SerialName("requestId") val requestId: String = "",
    @SerialName("salt") val salt: String = "",
    @SerialName("nonce") val nonce: String = "",
    @SerialName("ciphertext") val ciphertext: String = ""
)

@Serializable
data class PairApproveRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String,
    @SerialName("requestId") val requestId: String,
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String
)

@Serializable
data class PairRejectRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("senderToken") val senderToken: String,
    @SerialName("requestId") val requestId: String
)

@Serializable
data class PairStatusRequest(
    @SerialName("channelId") val channelId: String,
    @SerialName("requestId") val requestId: String
)

@Serializable
data class PairStatusResponse(
    @SerialName("state") val state: String = "",
    @SerialName("salt") val salt: String = "",
    @SerialName("nonce") val nonce: String = "",
    @SerialName("ciphertext") val ciphertext: String = ""
)

@Serializable
data class PairOkResponse(
    @SerialName("ok") val ok: Boolean = false
)

internal object SyncTlsFactory {
    fun tls13Spec(): ConnectionSpec =
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3)
            .build()

    fun buildStandardClient(): OkHttpClient = baseBuilder().build()

    fun buildPinnedClient(pinnedSpkiSha256: String): OkHttpClient {
        val expectedBytes = pinnedSpkiSha256.lowercase().toByteArray(Charsets.UTF_8)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
                chain[0].checkValidity()
                val spkiHash = SyncCrypto.sha256Hex(chain[0].publicKey.encoded)
                    .lowercase()
                    .toByteArray(Charsets.UTF_8)
                if (!MessageDigest.isEqual(spkiHash, expectedBytes)) {
                    throw CertificateException("Relay SPKI pin mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        val expectedHost = SyncConfig.DEFAULT_RELAY_URL.toHttpUrl().host

        return baseBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> hostname == expectedHost }
            .build()
    }

    fun buildSelfSignedIpClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
                chain[0].checkValidity()
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        return baseBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> SyncConfig.isRawIpHost(hostname) }
            .build()
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .connectionSpecs(listOf(tls13Spec()))
}
