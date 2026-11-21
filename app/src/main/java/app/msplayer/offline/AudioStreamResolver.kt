package app.msplayer.offline

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ResolvedAudio(
    val streamUrl: String,
    val sourceQuality: String, // "JioSaavn 320kbps AAC" or "YouTube 160kbps Opus"
    val hdThumbUrl: String = "",
    val userAgent: String = "",
    val resolvedAtMs: Long = System.currentTimeMillis(),
)

object AudioStreamResolver {
    private const val TAG = "AudioStreamResolver"
    private const val CACHE_TTL_MS = 2 * 3600 * 1000L
    private const val DEFAULT_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val resolutionCache = LruCache<String, ResolvedAudio>(200)

    private fun cacheKey(title: String, artist: String, ytVideoId: String): String =
        "$title::$artist::$ytVideoId".lowercase()

    fun invalidate(title: String, artist: String, ytVideoId: String = "") {
        resolutionCache.remove(cacheKey(title, artist, ytVideoId))
        resolutionCache.remove(cacheKey(title, artist, ""))
        val cleanYtId = if (ytVideoId.startsWith("saavn_")) "" else ytVideoId
        if (cleanYtId.isNotBlank()) {
            YtMusicClient.invalidateStreamCache(cleanYtId)
        }
    }

    private fun isStreamReachable(streamUrl: String): Boolean {
        if (!streamUrl.contains("saavn", ignoreCase = true)) return true
        return runCatching {
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 2200
                readTimeout = 2200
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", DEFAULT_WEB_UA)
            }
            conn.responseCode in 200..399
        }.getOrDefault(false)
    }

    /**
     * Resolves the highest quality stream with 100% backend fallback:
     * 1. JioSaavn 320kbps AAC primary
     * 2. YouTube Music iOS / Android / Android VR fallback with exact matched User-Agent
     */
    suspend fun resolveBestStream(
        title: String,
        artist: String,
        ytVideoId: String = "",
        rawThumbUrl: String = "",
        directStreamUrl: String = "",
        context: Context? = null,
    ): ResolvedAudio? = withContext(Dispatchers.IO) {
        if (context != null) {
            val (_, localAudios) = LibraryCache.load(context)
            val flacMatch = localAudios.firstOrNull { audio ->
                (audio.uri.endsWith(".flac", ignoreCase = true) || audio.title.contains("FLAC", ignoreCase = true)) &&
                PlaybackService.isDuplicateTrack(audio.title, audio.artist, title, artist)
            }
            if (flacMatch != null) {
                Log.d(TAG, "Lossless FLAC upgrade auto-selected from local storage: ${flacMatch.title}")
                return@withContext ResolvedAudio(
                    streamUrl = flacMatch.uri,
                    sourceQuality = "Lossless FLAC (Local Storage)",
                    hdThumbUrl = flacMatch.thumbUri ?: rawThumbUrl,
                    userAgent = "",
                )
            }
        }
        if (directStreamUrl.isNotBlank() && directStreamUrl.startsWith("http")) {
            val isSaavn = directStreamUrl.contains("saavn", ignoreCase = true)
            if (!isSaavn || isStreamReachable(directStreamUrl)) {
                return@withContext ResolvedAudio(
                    streamUrl = directStreamUrl,
                    sourceQuality = if (directStreamUrl.contains("flac", ignoreCase = true) || title.contains("FLAC", ignoreCase = true)) "Lossless FLAC" else "JioSaavn 320kbps AAC",
                    hdThumbUrl = if (rawThumbUrl.isNotBlank()) rawThumbUrl else YtMusicClient.boostThumbResolution(rawThumbUrl, ytVideoId),
                    userAgent = DEFAULT_WEB_UA,
                )
            }
        }
        val key = cacheKey(title, artist, ytVideoId)
        val now = System.currentTimeMillis()
        resolutionCache.get(key)?.let { cached ->
            if (now - cached.resolvedAtMs < CACHE_TTL_MS) {
                return@withContext cached
            } else {
                resolutionCache.remove(key)
            }
        }

        val cleanYtId = if (ytVideoId.startsWith("saavn_")) "" else ytVideoId

        // Fast concurrent race when a valid YouTube ID or title is present
        if (cleanYtId.isNotBlank() || title.isNotBlank()) {
            val jioDeferred = async {
                withTimeoutOrNull(3000) {
                    try {
                        JioSaavnClient.resolve320kbps(title, artist)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val ytDeferred = async {
                withTimeoutOrNull(5000) {
                    try {
                        if (cleanYtId.isNotBlank()) {
                            YtMusicClient.resolveStreamUrlWithUa(cleanYtId)
                        } else {
                            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
                            val searchHits = YtMusicClient.searchSongs("$cleanTitle $artist")
                            val vid = searchHits.firstOrNull()?.videoId ?: ""
                            if (vid.isNotBlank()) YtMusicClient.resolveStreamUrlWithUa(vid) else null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            // Give JioSaavn up to 900ms for 320k AAC audiophile quality
            val jio = withTimeoutOrNull(900) { jioDeferred.await() }
            if (jio != null && jio.streamUrl.isNotBlank() && isStreamReachable(jio.streamUrl)) {
                val resolved = ResolvedAudio(
                    streamUrl = jio.streamUrl,
                    sourceQuality = "JioSaavn 320kbps AAC",
                    hdThumbUrl = if (jio.thumbUrl.isNotBlank()) jio.thumbUrl else YtMusicClient.boostThumbResolution(rawThumbUrl, cleanYtId),
                    userAgent = DEFAULT_WEB_UA,
                )
                resolutionCache.put(key, resolved)
                Log.d(TAG, "Resolved via JioSaavn 320kbps: ${jio.title}")
                return@withContext resolved
            }

            // Immediately fallback to YouTube stream if JioSaavn wasn't ready or firewall blocked
            val ytStream = ytDeferred.await()
            if (ytStream != null && ytStream.url.isNotBlank()) {
                val resolved = ResolvedAudio(
                    streamUrl = ytStream.url,
                    sourceQuality = "YouTube Music 160kbps Opus",
                    hdThumbUrl = YtMusicClient.boostThumbResolution(rawThumbUrl, cleanYtId),
                    userAgent = ytStream.userAgent,
                )
                resolutionCache.put(key, resolved)
                Log.d(TAG, "Resolved via YouTube Music (fallback): $title")
                return@withContext resolved
            }

            // If JioSaavn finishes slightly later
            val jioLate = jioDeferred.await()
            if (jioLate != null && jioLate.streamUrl.isNotBlank() && isStreamReachable(jioLate.streamUrl)) {
                val resolved = ResolvedAudio(
                    streamUrl = jioLate.streamUrl,
                    sourceQuality = "JioSaavn 320kbps AAC",
                    hdThumbUrl = if (jioLate.thumbUrl.isNotBlank()) jioLate.thumbUrl else YtMusicClient.boostThumbResolution(rawThumbUrl, cleanYtId),
                    userAgent = DEFAULT_WEB_UA,
                )
                resolutionCache.put(key, resolved)
                Log.d(TAG, "Resolved via JioSaavn 320kbps (delayed): ${jioLate.title}")
                return@withContext resolved
            }
        }

        // Sequential fallback when resolving by title/artist text only (3-sec timeout for college Wi-Fi)
        withTimeoutOrNull(3000) {
            try {
                val jio = JioSaavnClient.resolve320kbps(title, artist)
                if (jio != null && jio.streamUrl.isNotBlank()) {
                    val resolved = ResolvedAudio(
                        streamUrl = jio.streamUrl,
                        sourceQuality = "JioSaavn 320kbps AAC",
                        hdThumbUrl = if (jio.thumbUrl.isNotBlank()) jio.thumbUrl else YtMusicClient.boostThumbResolution(rawThumbUrl, ytVideoId),
                        userAgent = DEFAULT_WEB_UA,
                    )
                    resolutionCache.put(key, resolved)
                    return@withTimeoutOrNull resolved
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            null
        }?.let { return@withContext it }

        withTimeoutOrNull(5000) {
            try {
                val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
                val ytResults = YtMusicClient.searchSongs("$cleanTitle $artist")
                val vid = ytResults.firstOrNull()?.videoId ?: ""
                if (vid.isNotBlank()) {
                    val ytStream = YtMusicClient.resolveStreamUrlWithUa(vid)
                    if (ytStream != null && ytStream.url.isNotBlank()) {
                        val resolved = ResolvedAudio(
                            streamUrl = ytStream.url,
                            sourceQuality = "YouTube Music 160kbps Opus",
                            hdThumbUrl = YtMusicClient.boostThumbResolution(rawThumbUrl, vid),
                            userAgent = ytStream.userAgent,
                        )
                        resolutionCache.put(key, resolved)
                        return@withTimeoutOrNull resolved
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            null
        }
    }

    /**
     * Resolves an alternate engine URL after a playback error, strictly avoiding the failed URL/host.
     */
    suspend fun resolveAlternateStream(
        title: String,
        artist: String,
        ytVideoId: String = "",
        rawThumbUrl: String = "",
        excludeUrl: String,
    ): ResolvedAudio? = withContext(Dispatchers.IO) {
        invalidate(title, artist, ytVideoId)
        val isLiveFlac = ytVideoId.startsWith("flac_") || excludeUrl.contains("flac", ignoreCase = true) || excludeUrl.contains("motherearth", ignoreCase = true)
        if (isLiveFlac) return@withContext null

        val failedWasSaavn = excludeUrl.contains("saavn", ignoreCase = true)
        val cleanYtId = if (ytVideoId.startsWith("saavn_")) "" else ytVideoId
        val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()

        if (failedWasSaavn) {
            // Try YouTube Music first since JioSaavn failed
            val yt = withTimeoutOrNull(5500) {
                val vid = cleanYtId.ifBlank {
                    YtMusicClient.searchSongs("$cleanTitle $artist").firstOrNull()?.videoId ?: ""
                }
                if (vid.isNotBlank()) YtMusicClient.resolveStreamUrlWithUa(vid, forceRefresh = true) else null
            }
            if (yt != null && yt.url.isNotBlank() && yt.url != excludeUrl) {
                val resolved = ResolvedAudio(
                    streamUrl = yt.url,
                    sourceQuality = "YouTube Music 160kbps Opus",
                    hdThumbUrl = YtMusicClient.boostThumbResolution(rawThumbUrl, cleanYtId),
                    userAgent = yt.userAgent,
                )
                resolutionCache.put(cacheKey(title, artist, ytVideoId), resolved)
                return@withContext resolved
            }
        } else {
            // Try JioSaavn first since YouTube failed
            val jio = withTimeoutOrNull(4500) {
                JioSaavnClient.resolve320kbps(title, artist)
            }
            if (jio != null && jio.streamUrl.isNotBlank() && jio.streamUrl != excludeUrl) {
                val resolved = ResolvedAudio(
                    streamUrl = jio.streamUrl,
                    sourceQuality = "JioSaavn 320kbps AAC",
                    hdThumbUrl = if (jio.thumbUrl.isNotBlank()) jio.thumbUrl else YtMusicClient.boostThumbResolution(rawThumbUrl, cleanYtId),
                    userAgent = DEFAULT_WEB_UA,
                )
                resolutionCache.put(cacheKey(title, artist, ytVideoId), resolved)
                return@withContext resolved
            }
            // Retry YouTube with fresh uncached client token
            val ytFresh = withTimeoutOrNull(5500) {
                val vid = cleanYtId.ifBlank {
                    YtMusicClient.searchSongs("$cleanTitle $artist").firstOrNull()?.videoId ?: ""
                }
                if (vid.isNotBlank()) YtMusicClient.resolveStreamUrlWithUa(vid, forceRefresh = true) else null
            }
            if (ytFresh != null && ytFresh.url.isNotBlank() && ytFresh.url != excludeUrl) {
                val resolved = ResolvedAudio(
                    streamUrl = ytFresh.url,
                    sourceQuality = "YouTube Music 160kbps Opus",
                    hdThumbUrl = YtMusicClient.boostThumbResolution(rawThumbUrl, cleanYtId),
                    userAgent = ytFresh.userAgent,
                )
                resolutionCache.put(cacheKey(title, artist, ytVideoId), resolved)
                return@withContext resolved
            }
        }
        null
    }
}
