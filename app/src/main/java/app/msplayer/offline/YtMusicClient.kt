package app.msplayer.offline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String = "",
    val thumbUrl: String = "",
)

object YtMusicClient {

    private const val YT_MUSIC_SEARCH = "https://music.youtube.com/youtubei/v1/search"
    private const val YT_PLAYER = "https://www.youtube.com/youtubei/v1/player"
    private const val LRCLIB_API = "https://lrclib.net/api/get"

    private const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val USER_AGENT_ANDROID = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"

    suspend fun searchSongs(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<YtTrack>()
        try {
            val url = URL(YT_MUSIC_SEARCH)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7000
                readTimeout = 7000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT_WEB)
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                // Filter specifically for Songs
                put("params", "EgWKAQIIAWoKEAkQChAFEAMQBA==")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(respText)
                val tabs = root.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")
                val tab0 = tabs?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
                val sections = tab0?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

                if (sections != null) {
                    for (s in 0 until sections.length()) {
                        val sec = sections.optJSONObject(s) ?: continue
                        val shelf = sec.optJSONObject("musicShelfRenderer") ?: continue
                        val contents = shelf.optJSONArray("contents") ?: continue
                        for (c in 0 until contents.length()) {
                            val item = contents.optJSONObject(c)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val flexCols = item.optJSONArray("flexColumns") ?: continue
                            
                            var trackTitle = "Unknown"
                            var artistName = "Unknown"
                            var dur = ""

                            if (flexCols.length() > 0) {
                                val col0Runs = flexCols.optJSONObject(0)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")?.optJSONArray("runs")
                                if (col0Runs != null && col0Runs.length() > 0) {
                                    trackTitle = col0Runs.optJSONObject(0)?.optString("text", "Unknown") ?: "Unknown"
                                }
                            }

                            if (flexCols.length() > 1) {
                                val col1Runs = flexCols.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")?.optJSONArray("runs")
                                if (col1Runs != null && col1Runs.length() > 0) {
                                    artistName = col1Runs.optJSONObject(0)?.optString("text", "Unknown") ?: "Unknown"
                                    if (col1Runs.length() >= 3) {
                                        dur = col1Runs.optJSONObject(col1Runs.length() - 1)?.optString("text", "") ?: ""
                                    }
                                }
                            }

                            val playNav = item.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                            val vid = playNav?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""

                            val thumbs = item.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            var thumbUrl = ""
                            if (thumbs != null && thumbs.length() > 0) {
                                thumbUrl = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                            }

                            if (vid.isNotBlank() && trackTitle != "Unknown") {
                                results.add(
                                    YtTrack(
                                        videoId = vid,
                                        title = trackTitle,
                                        artist = artistName,
                                        durationText = dur,
                                        thumbUrl = thumbUrl,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        results
    }

    suspend fun getTrending(): List<YtTrack> = withContext(Dispatchers.IO) {
        // Query top popular music by default
        val popular = listOf("Trending Songs 2026", "Top Global Hits", "Viral Pop Hits", "Chill Acoustic Hits")
        searchSongs(popular.random())
    }

    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        try {
            val url = URL(YT_PLAYER)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7000
                readTimeout = 7000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT_ANDROID)
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "21.26.364")
                        put("androidSdkVersion", 30)
                        put("userAgent", USER_AGENT_ANDROID)
                        put("osName", "Android")
                        put("osVersion", "11")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(respText)
                val streamingData = root.optJSONObject("streamingData") ?: return@withContext null

                // 1. First priority: Universal playable audio format from formats (Format 18: AAC audio)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val directUrl = f.optString("url", "")
                        if (directUrl.isNotBlank()) {
                            return@withContext directUrl
                        }
                    }
                }

                // 2. Second priority: Adaptive formats with direct URL
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val f = adaptive.optJSONObject(i) ?: continue
                        val mime = f.optString("mimeType", "")
                        if (mime.contains("audio")) {
                            val directUrl = f.optString("url", "")
                            if (directUrl.isNotBlank()) {
                                return@withContext directUrl
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            val cleanArtist = artist.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            val encTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val targetUrl = "$LRCLIB_API?track_name=$encTitle&artist_name=$encArtist"

            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "MSPlayer/0.7.0 (Android; OpenSource)")
            }

            if (conn.responseCode in 200..299) {
                val json = JSONObject(BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() })
                val synced = json.optString("syncedLyrics", "")
                if (synced.isNotBlank()) return@withContext synced
                val plain = json.optString("plainLyrics", "")
                if (plain.isNotBlank()) return@withContext plain
            }
        } catch (_: Exception) {}
        null
    }
}
