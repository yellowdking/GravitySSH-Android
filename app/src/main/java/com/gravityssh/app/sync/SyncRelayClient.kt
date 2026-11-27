package com.gravityssh.app.sync

import com.gravityssh.app.model.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

class RelayGoneException(message: String) : IOException(message)

open class SyncRelayClient(
    private val appGateKey: String = SyncConfig.APP_GATE_KEY,
    private val pinnedSpkiSha256: String = SyncConfig.PINNED_SPKI_SHA256
) {
    private val json = AppJson.json
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val defaultHostPort: String = run {
        val u = SyncConfig.DEFAULT_RELAY_URL.toHttpUrl()
        "${u.host}:${u.port}"
    }

    private val pinnedClient: OkHttpClient by lazy { SyncTlsFactory.buildPinnedClient(pinnedSpkiSha256) }
    private val selfSignedIpClient: OkHttpClient by lazy { SyncTlsFactory.buildSelfSignedIpClient() }
    private val standardClient: OkHttpClient by lazy { SyncTlsFactory.buildStandardClient() }

    open suspend fun ping(relayUrl: String): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        executePost(relayUrl, "/v1/ping", "{}".toByteArray(Charsets.UTF_8), isPing = true)
        ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
    }

    open suspend fun drop(relayUrl: String, req: DropRequest): DropResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(req).toByteArray(Charsets.UTF_8)
        val respText = executePost(relayUrl, "/v1/drop", body)
        json.decodeFromString(DropResponse.serializer(), respText)
    }

    open suspend fun pickup(relayUrl: String, channelId: String): PickupResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChannelRequest(channelId)).toByteArray(Charsets.UTF_8)
        val respText = executePost(relayUrl, "/v1/pickup", body)
        json.decodeFromString(PickupResponse.serializer(), respText)
    }

    open suspend fun cancel(relayUrl: String, channelId: String) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChannelRequest(channelId)).toByteArray(Charsets.UTF_8)
        executePost(relayUrl, "/v1/cancel", body)
        Unit
    }

    open suspend fun pairInit(
        relayUrl: String,
        channelId: String,
        senderToken: String,
        ttlSeconds: Int = SyncConfig.DEFAULT_TTL_SECONDS
    ): PairInitResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PairInitRequest(channelId, senderToken, ttlSeconds)).toByteArray(Charsets.UTF_8)
        val respText = executePost(relayUrl, "/v1/pair/init", body)
        json.decodeFromString(PairInitResponse.serializer(), respText)
    }

    open suspend fun pairRequest(
        relayUrl: String,
        channelId: String,
        requestId: String,
        salt: String,
        nonce: String,
        ciphertext: String
    ): PairRequestResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            PairRequestBody(channelId, requestId, salt, nonce, ciphertext)
        ).toByteArray(Charsets.UTF_8)
        val respText = executePost(
            relayUrl = relayUrl,
            path = "/v1/pair/request",
            rawBody = body,
            conflictMessage = "已有其他设备正在等待验证，请让发送端重新生成同步码"
        )
        json.decodeFromString(PairRequestResponse.serializer(), respText)
    }

    open suspend fun pairPoll(
        relayUrl: String,
        channelId: String,
        senderToken: String
    ): PairPollResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PairSenderTokenRequest(channelId, senderToken)).toByteArray(Charsets.UTF_8)
        val respText = executePostOrNullOn404(relayUrl, "/v1/pair/poll", body)
            ?: return@withContext PairPollResponse(state = "gone")
        json.decodeFromString(PairPollResponse.serializer(), respText)
    }

    open suspend fun pairApprove(
        relayUrl: String,
        channelId: String,
        senderToken: String,
        requestId: String,
        salt: String,
        nonce: String,
        ciphertext: String
    ) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            PairApproveRequest(channelId, senderToken, requestId, salt, nonce, ciphertext)
        ).toByteArray(Charsets.UTF_8)
        executePost(relayUrl, "/v1/pair/approve", body)
        Unit
    }

    open suspend fun pairReject(
        relayUrl: String,
        channelId: String,
        senderToken: String,
        requestId: String
    ) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PairRejectRequest(channelId, senderToken, requestId)).toByteArray(Charsets.UTF_8)
        executePost(relayUrl, "/v1/pair/reject", body)
        Unit
    }

    open suspend fun pairStatus(
        relayUrl: String,
        channelId: String,
        requestId: String
    ): PairStatusResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PairStatusRequest(channelId, requestId)).toByteArray(Charsets.UTF_8)
        val respText = executePost(relayUrl, "/v1/pair/status", body)
        json.decodeFromString(PairStatusResponse.serializer(), respText)
    }

    open suspend fun pairCancel(
        relayUrl: String,
        channelId: String,
        senderToken: String
    ) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PairSenderTokenRequest(channelId, senderToken)).toByteArray(Charsets.UTF_8)
        executePost(relayUrl, "/v1/pair/cancel", body)
        Unit
    }

    private fun executePost(
        relayUrl: String,
        path: String,
        rawBody: ByteArray,
        isPing: Boolean = false,
        conflictMessage: String = "同步通道冲突，请重新生成同步码"
    ): String = executePostOrNullOn404(
        relayUrl = relayUrl,
        path = path,
        rawBody = rawBody,
        isPing = isPing,
        allow404 = false,
        conflictMessage = conflictMessage
    ) ?: throw RelayGoneException(
        if (isPing) "中继服务鉴权失败或版本不支持 /v1/ping" else "同步码不存在、已过期或已被使用"
    )

    private fun executePostOrNullOn404(
        relayUrl: String,
        path: String,
        rawBody: ByteArray,
        isPing: Boolean = false,
        allow404: Boolean = true,
        conflictMessage: String = "同步通道冲突，请重新生成同步码"
    ): String? {
        if (rawBody.size > MAX_REQUEST_BYTES) {
            throw IOException("同步数据体积超过中继限制（2 MiB）")
        }
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
            val text = readBodyCapped(resp.body)
            when (resp.code) {
                404 -> {
                    if (allow404) return null
                    throw RelayGoneException(
                        if (isPing) "中继服务鉴权失败或版本不支持 /v1/ping" else "同步码不存在、已过期或已被使用"
                    )
                }
                429 -> throw IOException("同步中继繁忙或请求过于频繁，请稍后重试")
                409 -> throw IOException(conflictMessage)
                413 -> throw IOException("同步数据体积超过中继限制（2 MiB）")
            }
            if (!resp.isSuccessful) throw IOException("中继服务返回错误 (${resp.code})")
            return text
        }
    }

    private fun readBodyCapped(body: ResponseBody?): String {
        if (body == null) return ""
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw IOException("中继响应超过 4 MiB 上限")
        }
        val out = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_RESPONSE_BYTES) {
                    throw IOException("中继响应超过 4 MiB 上限")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
