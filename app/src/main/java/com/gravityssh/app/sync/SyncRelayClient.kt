package com.gravityssh.app.sync

import com.gravityssh.app.model.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RelayGoneException(message: String) : IOException(message)

open class SyncRelayClient(
    private val appGateKey: String = SyncConfig.APP_GATE_KEY,
    private val pinnedSpkiSha256: String = SyncConfig.PINNED_SPKI_SHA256
) {
    private val json = AppJson.json
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val pinnedClient: OkHttpClient by lazy { SyncHttpClients.buildPinnedClient(pinnedSpkiSha256) }
    private val standardClient: OkHttpClient get() = SyncHttpClients.baseClient
    private val clientCacheLock = Any()
    private val customClients = LinkedHashMap<String, OkHttpClient>(16, 0.75f, true)

    open suspend fun ping(relayUrl: String): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        executePost(relayUrl, "/v1/ping", "{}".toByteArray(Charsets.UTF_8), isPing = true)
        ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
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

    private suspend fun executePost(
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

    private suspend fun executePostOrNullOn404(
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
        val endpoint = SyncConfig.parseRelayEndpoint(relayUrl)
        val url = "${endpoint.baseUrl}$path".toHttpUrl()
        val client = selectClient(endpoint)

        val ts = System.currentTimeMillis() / 1000L
        val sig = SyncCryptoPrimitives.signAppGate(appGateKey, "POST", path, ts, rawBody)

        val request = Request.Builder()
            .url(url)
            .post(rawBody.toRequestBody(mediaType))
            .header("X-GSSH-Client", SyncConfig.CLIENT_HEADER)
            .header("X-GSSH-TS", ts.toString())
            .header("X-GSSH-Sig", sig)
            .build()

        val (code, text) = executeCallCancellable(client, request)
        when (code) {
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
        if (code !in 200..299) throw IOException("中继服务返回错误 ($code)")
        return text
    }

    private fun selectClient(endpoint: ResolvedRelayEndpoint): OkHttpClient {
        endpoint.explicitSpkiPin?.let { pin ->
            val key = "pin:${endpoint.host.lowercase()}:$pin"
            return getOrCreateCustomClient(key) {
                SyncHttpClients.buildPinnedClient(pin, endpoint.host)
            }
        }
        if (endpoint.isDefaultRelay) return pinnedClient
        if (endpoint.isRawIp) {
            val key = "ip:${endpoint.hostPort}"
            return getOrCreateCustomClient(key) {
                SyncHttpClients.buildSelfSignedIpClient(endpoint.hostPort, endpoint.host)
            }
        }
        return standardClient
    }

    private fun getOrCreateCustomClient(key: String, factory: () -> OkHttpClient): OkHttpClient {
        synchronized(clientCacheLock) {
            customClients[key]?.let { return it }
            val created = factory()
            if (customClients.size >= MAX_CACHED_CLIENTS) {
                val eldestKey = customClients.entries.iterator().next().key
                customClients.remove(eldestKey)
            }
            customClients[key] = created
            return created
        }
    }

    private suspend fun executeCallCancellable(client: OkHttpClient, request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        try {
                            val text = readBodyCapped(resp.body)
                            if (cont.isActive) cont.resume(resp.code to text)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }
                }
            })
        }

    private fun readBodyCapped(body: ResponseBody?): String {
        if (body == null) return ""
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw IOException("中继响应超过 4 MiB 上限")
        }
        return body.byteStream().use { input ->
            SyncCryptoPrimitives.readStreamCapped(input, MAX_RESPONSE_BYTES, "中继响应超过 4 MiB 上限")
                .toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_CACHED_CLIENTS = 16
    }
}
