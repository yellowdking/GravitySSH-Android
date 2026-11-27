package com.gravityssh.app.terminal

import com.gravityssh.app.model.UpdateCheckResult
import com.gravityssh.app.sync.SyncHttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("assets") val assets: List<GitHubAssetDto> = emptyList()
)

@Serializable
private data class GitHubAssetDto(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = ""
)

class UpdateChecker(
    private val currentVersion: String,
    private val apiUrl: String = DEFAULT_RELEASE_API
) {
    private data class FetchResult(
        val code: Int,
        val location: String?,
        val body: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        SyncHttpClients.baseClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        var targetUrl = apiUrl.trim()
        var raw = ""
        for (hop in 0..MAX_REDIRECTS) {
            val req = Request.Builder()
                .url(targetUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GravitySSH-Android/$currentVersion")
                .build()
            val res = executeCancellable(req)
            if (res.code in REDIRECT_STATUS_CODES) {
                if (hop == MAX_REDIRECTS) {
                    throw IOException("检查更新重定向次数过多")
                }
                val loc = res.location?.trim().orEmpty()
                val resolved = req.url.resolve(loc)?.toString()
                    ?: throw IOException("检查更新重定向地址无效")
                if (!isTrustedApiRedirectUrl(resolved)) {
                    throw IOException("检查更新遭到不受信任的重定向")
                }
                targetUrl = resolved
                continue
            }
            if (res.code !in 200..299) {
                throw IOException("检查更新失败 (HTTP ${res.code})")
            }
            raw = res.body
            break
        }

        val dto = json.decodeFromString(GitHubReleaseDto.serializer(), raw)
        val latest = dto.tagName.trim().removePrefix("v").removePrefix("V")
        val releaseUrl = dto.htmlUrl.trim()
        if (releaseUrl.isNotEmpty() && !isTrustedReleaseUrl(releaseUrl)) {
            throw IOException("更新接口返回了不受信任的发布页地址")
        }
        val apkUrl = dto.assets
            .firstOrNull {
                it.name.endsWith("-release.apk", ignoreCase = true) && isTrustedReleaseUrl(it.downloadUrl)
            }
            ?.downloadUrl
            ?.trim()
            ?: dto.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && isTrustedReleaseUrl(it.downloadUrl)
            }?.downloadUrl?.trim()
            ?: releaseUrl
        UpdateCheckResult(
            currentVersion = currentVersion,
            latestVersion = latest,
            hasUpdate = compareSemver(latest, currentVersion) > 0,
            releaseUrl = releaseUrl,
            downloadUrl = apkUrl,
            releaseNotes = dto.body,
            publishedAt = dto.publishedAt
        )
    }

    private suspend fun executeCancellable(req: Request): FetchResult =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        try {
                            val location = resp.header("Location")
                            val body = if (resp.code in REDIRECT_STATUS_CODES) "" else readBodyCapped(resp.body)
                            if (cont.isActive) {
                                cont.resume(FetchResult(resp.code, location, body))
                            }
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private fun readBodyCapped(body: ResponseBody?): String {
        if (body == null) return ""
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw IOException("更新响应超过 4 MiB 上限")
        }
        return body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_RESPONSE_BYTES) {
                    throw IOException("更新响应超过 4 MiB 上限")
                }
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        const val DEFAULT_RELEASE_API = "https://api.github.com/repos/yellowdking/GravitySSH-Android/releases/latest"
        private const val TRUSTED_API_HOST = "api.github.com"
        private const val TRUSTED_RELEASE_HOST = "github.com"
        private const val TRUSTED_RELEASE_PATH_PREFIX = "/yellowdking/GravitySSH-Android/releases/"
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        fun isTrustedApiRedirectUrl(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            val parsedUri = runCatching { URI(trimmed) }.getOrNull() ?: return false
            if (!parsedUri.scheme.equals("https", ignoreCase = true)) return false
            if (parsedUri.rawUserInfo != null || parsedUri.port != -1) return false
            val url = trimmed.toHttpUrlOrNull() ?: return false
            return url.scheme == "https" &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.port == 443 &&
                url.host.equals(TRUSTED_API_HOST, ignoreCase = true)
        }

        fun isTrustedReleaseUrl(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            val parsedUri = runCatching { URI(trimmed) }.getOrNull() ?: return false
            if (!parsedUri.scheme.equals("https", ignoreCase = true)) return false
            if (parsedUri.rawUserInfo != null || parsedUri.port != -1) return false
            val url = trimmed.toHttpUrlOrNull() ?: return false
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443) {
                return false
            }
            if (!url.host.equals(TRUSTED_RELEASE_HOST, ignoreCase = true)) return false
            return url.encodedPath.startsWith(TRUSTED_RELEASE_PATH_PREFIX)
        }

        fun compareSemver(a: String, b: String): Int {
            val ca = a.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val cb = b.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val dashA = ca.indexOf('-')
            val dashB = cb.indexOf('-')
            val coreA = if (dashA >= 0) ca.substring(0, dashA) else ca
            val coreB = if (dashB >= 0) cb.substring(0, dashB) else cb

            val pa = coreA.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = coreB.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }

            val preA = if (dashA >= 0) ca.substring(dashA + 1) else ""
            val preB = if (dashB >= 0) cb.substring(dashB + 1) else ""
            if (preA.isEmpty() && preB.isEmpty()) return 0
            if (preA.isEmpty()) return 1
            if (preB.isEmpty()) return -1

            val ia = preA.split('.')
            val ib = preB.split('.')
            val m = maxOf(ia.size, ib.size)
            for (i in 0 until m) {
                val sa = ia.getOrNull(i) ?: return -1
                val sb = ib.getOrNull(i) ?: return 1
                val na = sa.toIntOrNull()
                val nb = sb.toIntOrNull()
                val cmp = when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1
                    nb != null -> 1
                    else -> sa.compareTo(sb)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
