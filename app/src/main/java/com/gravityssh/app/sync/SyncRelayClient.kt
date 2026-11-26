package com.gravityssh.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SyncRelayClient(
    private val appGateKey: String = SyncConfig.APP_GATE_KEY,
    private val pinnedSpkiSha256: String = SyncConfig.PINNED_SPKI_SHA256
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val defaultHostPort: String = run {
        val u = SyncConfig.DEFAULT_RELAY_URL.toHttpUrl()
        "${u.host}:${u.port}"
    }

    private val pinnedClient: OkHttpClient by lazy { SyncTlsFactory.buildPinnedClient(pinnedSpkiSha256) }
    private val selfSignedIpClient: OkHttpClient by lazy { SyncTlsFactory.buildSelfSignedIpClient() }
    private val standardClient: OkHttpClient by lazy { SyncTlsFactory.buildStandardClient() }

    suspend fun ping(relayUrl: String): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        executePost(relayUrl, "/v1/ping", "{}".toByteArray(Charsets.UTF_8), isPing = true)
        ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
    }

    suspend fun drop(relayUrl: String, req: DropRequest): DropResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(req).toByteArray(Charsets.UTF_8)
        val respText = executePost(relayUrl, "/v1/drop", body)
        json.decodeFromString(DropResponse.serializer(), respText)
    }

    suspend fun pickup(relayUrl: String, channelId: String): PickupResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChannelRequest(channelId)).toByteArray(Charsets.UTF_8)
        val respText = executePost(relayUrl, "/v1/pickup", body)
        json.decodeFromString(PickupResponse.serializer(), respText)
    }

    suspend fun cancel(relayUrl: String, channelId: String) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChannelRequest(channelId)).toByteArray(Charsets.UTF_8)
        executePost(relayUrl, "/v1/cancel", body)
        Unit
    }

    private fun executePost(
        relayUrl: String,
        path: String,
        rawBody: ByteArray,
        isPing: Boolean = false
    ): String {
        val base = SyncConfig.normalizeRelayUrl(relayUrl).ifEmpty { SyncConfig.DEFAULT_RELAY_URL }
        val url = "$base$path".toHttpUrl()
        val client = when {
            "${url.host}:${url.port}" == defaultHostPort -> pinnedClient
            SyncConfig.isRawIpHost(url.host) -> selfSignedIpClient
            else -> standardClient
        }

        val ts = System.currentTimeMillis() / 1000L
        val sig = SyncCrypto.signAppGate(appGateKey, "POST", path, ts, rawBody)

        val request = Request.Builder()
            .url(url)
            .post(rawBody.toRequestBody(mediaType))
            .header("X-GSSH-Client", SyncConfig.CLIENT_HEADER)
            .header("X-GSSH-TS", ts.toString())
            .header("X-GSSH-Sig", sig)
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when (resp.code) {
                404 -> throw IOException(
                    if (isPing) "中继服务鉴权失败或版本不支持 /v1/ping" else "同步码不存在、已过期或已被使用"
                )
                429 -> throw IOException("同步中继繁忙或请求过于频繁，请稍后重试")
                409 -> throw IOException("同步通道冲突，请重新生成同步码")
            }
            if (!resp.isSuccessful) throw IOException("中继服务返回错误 (${resp.code})")
            return text
        }
    }
}
