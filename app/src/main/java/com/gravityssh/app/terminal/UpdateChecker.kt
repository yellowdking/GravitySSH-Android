package com.gravityssh.app.terminal

import com.gravityssh.app.model.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GravitySSH-Android/$currentVersion")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("检查更新失败 (HTTP ${resp.code})")
            val raw = resp.body?.string().orEmpty()
            val dto = json.decodeFromString(GitHubReleaseDto.serializer(), raw)
            val latest = dto.tagName.trim().removePrefix("v").removePrefix("V")
            val apkUrl = dto.assets
                .firstOrNull { it.name.endsWith("-release.apk", ignoreCase = true) }
                ?.downloadUrl
                ?: dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.downloadUrl
                ?: dto.htmlUrl
            UpdateCheckResult(
                currentVersion = currentVersion,
                latestVersion = latest,
                hasUpdate = compareSemver(latest, currentVersion) > 0,
                releaseUrl = dto.htmlUrl,
                downloadUrl = apkUrl,
                releaseNotes = dto.body,
                publishedAt = dto.publishedAt
            )
        }
    }

    companion object {
        const val DEFAULT_RELEASE_API = "https://api.github.com/repos/yellowdking/GravitySSH-Android/releases/latest"

        fun compareSemver(a: String, b: String): Int {
            val pa = a.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }
    }
}
