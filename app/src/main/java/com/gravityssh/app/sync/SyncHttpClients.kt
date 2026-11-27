package com.gravityssh.app.sync

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

object SyncHttpClients {
    private val modernTlsSpec: ConnectionSpec =
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionSpecs(listOf(modernTlsSpec))
            .build()
    }

    internal fun buildPinnedClient(pinnedSpkiSha256: String): OkHttpClient {
        val expectedBytes = pinnedSpkiSha256.lowercase().toByteArray(Charsets.UTF_8)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
                chain[0].checkValidity()
                val spkiHash = SyncCryptoPrimitives.sha256Hex(chain[0].publicKey.encoded)
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

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> hostname == expectedHost }
            .build()
    }

    internal fun buildSelfSignedIpClient(): OkHttpClient {
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

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> SyncConfig.isRawIpHost(hostname) }
            .build()
    }
}
