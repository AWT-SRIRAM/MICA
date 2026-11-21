package app.msplayer.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val isAvailable: Boolean,
    val latestVersion: String,
    val releaseTitle: String,
    val changelog: String,
    val downloadUrl: String,
    val releasePageUrl: String
)

object UpdateChecker {

    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/AWT-SRIRAM/MICA/releases/latest"

    fun isNewerVersion(remoteTag: String, currentVer: String): Boolean {
        val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVer.trim().removePrefix("v").removePrefix("V")
        if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false
        if (cleanRemote == cleanCurrent) return false

        val remoteParts = cleanRemote.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split('.').mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun check(currentVer: String = BuildConfig.VERSION_NAME): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(GITHUB_RELEASES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MS-PLAYER-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", tagName).trim()
            val changelog = json.optString("body", "").trim()
            val htmlUrl = json.optString("html_url", "https://github.com/AWT-SRIRAM/MICA/releases").trim()

            var apkDownloadUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").lowercase()
                    if (name.endsWith(".apk")) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        // Prefer arm64 if multiple
                        if (name.contains("arm64")) break
                    }
                }
            }
            if (apkDownloadUrl.isBlank()) apkDownloadUrl = htmlUrl

            val hasUpdate = isNewerVersion(tagName, currentVer)
            UpdateInfo(
                isAvailable = hasUpdate,
                latestVersion = tagName.removePrefix("v"),
                releaseTitle = releaseTitle,
                changelog = changelog,
                downloadUrl = apkDownloadUrl,
                releasePageUrl = htmlUrl
            )
        }.getOrNull()
    }
}
