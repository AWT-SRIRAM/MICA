package app.msplayer.offline

import android.content.Context
import android.util.Log
import android.util.LruCache
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

data class YtResolvedStream(
    val url: String,
    val userAgent: String,
)

data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String = "",
    val thumbUrl: String = "",
    val directStreamUrl: String = "",
)

data class ArtistCard(
    val name: String,
    val subtitle: String,
    val avatarUrl: String,
    val searchQuery: String,
)

data class AlbumCard(
    val title: String,
    val artist: String,
    val coverUrl: String,
    val searchQuery: String,
)

data class PlaylistCard(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val searchQuery: String = "",
    val listId: String = "",
    val songCount: Int = 0,
)

object YtMusicClient {

    fun boostThumbResolution(rawUrl: String, videoId: String = ""): String {
        if (rawUrl.isBlank()) {
            return if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
        }
        var url = rawUrl
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            url = url.replace(Regex("""=w\d+-h\d+.*"""), "=w800-h800-l90-rj")
                .replace(Regex("""=s\d+.*"""), "=s800-c")
        }
        if (url.contains("saavncdn.com")) {
            url = url.replace("150x150.jpg", "500x500.jpg")
                .replace("50x50.jpg", "500x500.jpg")
        }
        if (url.contains("i.ytimg.com") && videoId.isNotBlank()) {
            if (url.endsWith("default.jpg") && !url.endsWith("hqdefault.jpg") && !url.endsWith("maxresdefault.jpg")) {
                url = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }
        }
        return url
    }

    fun getPopularArtists(): List<ArtistCard> = listOf(
        ArtistCard("Anirudh", "Composer • Hits", "https://c.saavncdn.com/artists/Anirudh_Ravichander_003_20260121134149_500x500.jpg", "Anirudh Ravichander Hit Songs"),
        ArtistCard("A.R. Rahman", "Maestro • Hits", "https://c.saavncdn.com/artists/AR_Rahman_002_20210120084455_500x500.jpg", "A.R. Rahman Greatest Tamil Hits"),
        ArtistCard("Harris Jayaraj", "Melody King", "https://c.saavncdn.com/artists/Harris_Jayaraj_002_20230718071330_500x500.jpg", "Harris Jayaraj Best Songs"),
        ArtistCard("Sai Abhyankkar", "Think Indie", "https://c.saavncdn.com/artists/Sai_Abhyankkar_003_20250707122433_500x500.jpg", "Sai Abhyankkar Songs"),
        ArtistCard("Arijit Singh", "Top Hits", "https://c.saavncdn.com/artists/Arijit_Singh_004_20241118063717_500x500.jpg", "Arijit Singh Best Songs"),
        ArtistCard("Yuvan Shankar", "BGM King", "https://c.saavncdn.com/artists/Yuvan_Shankar_Raja_002_20180802174245_500x500.jpg", "Yuvan Shankar Raja Hits"),
        ArtistCard("Sid Sriram", "Soulful Vocals", "https://c.saavncdn.com/artists/Sid_Sriram_005_20240425180600_500x500.jpg", "Sid Sriram Tamil Hits")
    )

    fun getPopularAlbums(): List<AlbumCard> = listOf(
        AlbumCard("Jailer", "Anirudh", "https://c.saavncdn.com/187/Jailer-Tamil-2023-20230728081443-500x500.jpg", "Jailer Songs Anirudh"),
        AlbumCard("Think Indie Hits", "Sai Abhyankkar", "https://c.saavncdn.com/696/Veezha-Veera-From-OM-Chapter-1-Udhiram-The-Blood-Wood-Tamil-Tamil-2026-20261019161818-500x500.jpg", "Think Indie Tamil Songs"),
        AlbumCard("Ramayana", "A.R. Rahman", "https://c.saavncdn.com/316/Mangal-Rang-From-Ramayana-Hindi-2026-20261024141033-500x500.jpg", "Ramayana Songs AR Rahman"),
        AlbumCard("KING Hits", "Arijit Singh", "https://c.saavncdn.com/320/Tu-Mil-Jaaye-From-KING-Hindi-2026-20261031131042-500x500.jpg", "King Songs Arijit Singh"),
        AlbumCard("Awaara Hits", "Yuvan Shankar", "https://c.saavncdn.com/612/Awaara-Telugu-2013-500x500.jpg", "Awaara Songs Yuvan Shankar Raja")
    )

    fun getCuratedPlaylists(): List<PlaylistCard> = listOf(
        PlaylistCard("Tamil 1990s Chartbusters", "100 Golden Retro Hits", "https://c.saavncdn.com/editorial/charts_Tamil1990s_190250_20240408062124_500x500.jpg", "Tamil 1990s Classic Golden Hits", listId = "1170578779", songCount = 100),
        PlaylistCard("Tamil 2000s Melodies", "100 Romantic & Dance Beats", "https://c.saavncdn.com/editorial/charts_Tamil2000s_171230_20240408062044_500x500.jpg", "Tamil 2000s Hits Harris AR Rahman", listId = "1170578783", songCount = 100),
        PlaylistCard("Tamil 2010s Viral Hits", "100 Modern Chartbusters", "https://c.saavncdn.com/editorial/charts_Tamil2010s_138543_20240408061942_500x500.jpg", "Tamil 2010s Anirudh Yuvan Hits", listId = "1170578788", songCount = 100),
        PlaylistCard("Top 50 Tamil Today", "Weekly Most Streamed Hits", "https://c.saavncdn.com/artists/Anirudh_Ravichander_003_20260121134149_500x500.jpg", "Top 50 Tamil Songs 2026", listId = "1302055776", songCount = 50),
        PlaylistCard("A.R. Rahman Maestro", "Oscar Winning Melodies", "https://c.saavncdn.com/artists/AR_Rahman_002_20210120084455_500x500.jpg", "A.R. Rahman Greatest Tamil Hits", listId = "158221976", songCount = 50),
        PlaylistCard("Harris Jayaraj Magic", "Melody & Romance", "https://c.saavncdn.com/artists/Harris_Jayaraj_002_20230718071330_500x500.jpg", "Harris Jayaraj Best Songs", listId = "82903723", songCount = 50),
        PlaylistCard("Top 50 Global Hits", "Viral Worldwide Chartbusters", "https://c.saavncdn.com/artists/Arijit_Singh_004_20241118063717_500x500.jpg", "Top 50 Global Songs 2026", listId = "1134543202", songCount = 50),
    )

    private const val YT_MUSIC_SEARCH = "https://music.youtube.com/youtubei/v1/search"
    private const val YT_MUSIC_BROWSE = "https://music.youtube.com/youtubei/v1/browse"
    private const val YT_MUSIC_NEXT = "https://music.youtube.com/youtubei/v1/next"
    private const val YT_PLAYER = "https://www.youtube.com/youtubei/v1/player"
    private const val LRCLIB_API = "https://lrclib.net/api/get"

    private const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private const val USER_AGENT_IOS = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
    private const val USER_AGENT_ANDROID = "com.google.android.youtube/21.26.364 (Linux; U; Android 14)"
    private const val USER_AGENT_ANDROID_VR = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; eureka-user Build/SQ3A.220605.009.A1)"

    // In-memory cache for instant 0ms responses on repeated searches and tab switches
    private val searchCache = LruCache<String, List<YtTrack>>(80)
    private val streamUrlCache = LruCache<String, Pair<YtResolvedStream, Long>>(150) // videoId -> (stream, expiryMs)
    private val lyricsCache = LruCache<String, String>(100)

    // Optional Google Auth cookies (from InnerTune/OuterTune style login)
    private var googleAuthCookies: String? = null

    fun setGoogleAuthCookies(context: Context, cookies: String?) {
        googleAuthCookies = cookies
        Store.prefs(context).edit().putString("yt_google_cookies", cookies).apply()
    }

    fun getGoogleAuthCookies(context: Context): String? {
        if (googleAuthCookies == null) {
            googleAuthCookies = Store.prefs(context).getString("yt_google_cookies", null)
        }
        return googleAuthCookies
    }

    fun isGoogleSignedIn(context: Context): Boolean = !getGoogleAuthCookies(context).isNullOrBlank()

    init {
        // Enable persistent HTTP keep-alive connection pooling
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "10")
    }

    suspend fun searchSongs(query: String, forceRefresh: Boolean = false): List<YtTrack> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        if (!forceRefresh) {
            searchCache.get(cleanQuery.lowercase())?.let { cached ->
                if (cached.isNotEmpty()) return@withContext cached
            }
        }

        val results = mutableListOf<YtTrack>()
        try {
            val url = URL(YT_MUSIC_SEARCH)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT_WEB)
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("Connection", "keep-alive")
                googleAuthCookies?.let { setRequestProperty("Cookie", it) }
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240801.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", cleanQuery)
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
                            if (thumbUrl.isNotBlank() && vid.isNotBlank()) {
                                thumbUrl = boostThumbResolution(thumbUrl, vid)
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

        if (results.isNotEmpty()) {
            searchCache.put(cleanQuery.lowercase(), results)
        }
        results
    }

    /**
     * Searches YouTube Music for playlists & albums matching a search query.
     */
    suspend fun searchPlaylists(query: String): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val results = mutableListOf<PlaylistCard>()
        try {
            val url = URL(YT_MUSIC_SEARCH)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT_WEB)
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("Connection", "keep-alive")
                googleAuthCookies?.let { setRequestProperty("Cookie", it) }
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241028.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", if (cleanQuery.contains("playlist", ignoreCase = true)) cleanQuery else "$cleanQuery playlist")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(respText)
                val sections = root.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (sections != null) {
                    for (i in 0 until sections.length()) {
                        val sec = sections.optJSONObject(i) ?: continue
                        val itemSec = sec.optJSONObject("itemSectionRenderer") ?: continue
                        val contents = itemSec.optJSONArray("contents") ?: continue
                        for (j in 0 until contents.length()) {
                            val card = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val flexCols = card.optJSONArray("flexColumns") ?: continue
                            val titleCol = flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val titleRuns = titleCol?.optJSONObject("text")?.optJSONArray("runs")
                            val title = (0 until (titleRuns?.length() ?: 0)).joinToString("") {
                                titleRuns?.optJSONObject(it)?.optString("text", "") ?: ""
                            }
                            val subCol = flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val subRuns = subCol?.optJSONObject("text")?.optJSONArray("runs")
                            val subtitle = (0 until (subRuns?.length() ?: 0)).joinToString("") {
                                subRuns?.optJSONObject(it)?.optString("text", "") ?: ""
                            }

                            val navEndpoint = card.optJSONObject("navigationEndpoint")
                            val browseId = navEndpoint?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: ""
                            val isPlaylistOrAlbum = browseId.startsWith("VL") || browseId.startsWith("MPREb_") || subtitle.contains("Playlist", ignoreCase = true) || subtitle.contains("Album", ignoreCase = true)

                            if (isPlaylistOrAlbum && browseId.isNotBlank() && title.isNotBlank()) {
                                var thumbUrl = ""
                                val thumbs = card.optJSONObject("thumbnail")
                                    ?.optJSONObject("musicThumbnailRenderer")
                                    ?.optJSONObject("thumbnail")
                                    ?.optJSONArray("thumbnails")
                                if (thumbs != null && thumbs.length() > 0) {
                                    val lastThumb = thumbs.optJSONObject(thumbs.length() - 1)
                                    thumbUrl = lastThumb?.optString("url", "") ?: ""
                                }
                                results.add(
                                    PlaylistCard(
                                        title = title,
                                        subtitle = subtitle.ifBlank { "YouTube Music Collection" },
                                        coverUrl = boostThumbResolution(thumbUrl),
                                        searchQuery = title,
                                        listId = browseId,
                                        songCount = 0
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("YtMusicClient", "searchPlaylists error: ${e.message}")
        }
        results
    }

    /**
     * Fetches tracks from a YouTube Music playlist or album browse endpoint.
     */
    suspend fun fetchPlaylistTracks(browseId: String): List<YtTrack> = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext emptyList()
        val tracks = mutableListOf<YtTrack>()
        try {
            val url = URL(YT_MUSIC_BROWSE)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 6500
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT_WEB)
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("Connection", "keep-alive")
                googleAuthCookies?.let { setRequestProperty("Cookie", it) }
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241028.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("browseId", if (browseId.startsWith("VL") || browseId.startsWith("MPREb_")) browseId else "VL$browseId")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(respText)
                val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                val sections = twoCol?.optJSONObject("secondaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                val playlistShelf = if (sections != null && sections.length() > 0) {
                    sections.optJSONObject(0)?.optJSONObject("musicPlaylistShelfRenderer")
                } else {
                    root.optJSONObject("contents")
                        ?.optJSONObject("singleColumnBrowseResultsRenderer")
                        ?.optJSONArray("tabs")?.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")?.optJSONObject(0)
                        ?.optJSONObject("musicPlaylistShelfRenderer")
                }

                val contents = playlistShelf?.optJSONArray("contents")
                if (contents != null) {
                    for (i in 0 until contents.length()) {
                        val card = contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val flexCols = card.optJSONArray("flexColumns") ?: continue
                        val titleCol = flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val titleRuns = titleCol?.optJSONObject("text")?.optJSONArray("runs")
                        val title = (0 until (titleRuns?.length() ?: 0)).joinToString("") {
                            titleRuns?.optJSONObject(it)?.optString("text", "") ?: ""
                        }
                        val artistCol = flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val artistRuns = artistCol?.optJSONObject("text")?.optJSONArray("runs")
                        val artist = (0 until (artistRuns?.length() ?: 0)).joinToString("") {
                            artistRuns?.optJSONObject(it)?.optString("text", "") ?: ""
                        }

                        val durationCol = if (flexCols.length() > 2) flexCols.optJSONObject(2)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer") else null
                        val durationRuns = durationCol?.optJSONObject("text")?.optJSONArray("runs")
                        val durationText = (0 until (durationRuns?.length() ?: 0)).joinToString("") {
                            durationRuns?.optJSONObject(it)?.optString("text", "") ?: ""
                        }

                        val playlistItemData = card.optJSONObject("playlistItemData")
                        var videoId = playlistItemData?.optString("videoId", "") ?: ""
                        if (videoId.isBlank()) {
                            val playNav = card.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                            videoId = playNav?.optString("videoId", "") ?: ""
                        }

                        var thumbUrl = ""
                        val thumbs = card.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        if (thumbs != null && thumbs.length() > 0) {
                            val lastThumb = thumbs.optJSONObject(thumbs.length() - 1)
                            thumbUrl = lastThumb?.optString("url", "") ?: ""
                        }

                        if (videoId.isNotBlank() && title.isNotBlank()) {
                            tracks.add(
                                YtTrack(
                                    videoId = videoId,
                                    title = title,
                                    artist = artist.ifBlank { "YouTube Music" },
                                    durationText = durationText,
                                    thumbUrl = boostThumbResolution(thumbUrl, videoId),
                                    directStreamUrl = ""
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("YtMusicClient", "fetchPlaylistTracks error: ${e.message}")
        }
        tracks
    }

    /**
     * Resolves Tamil and English top trending hits.
     */
    suspend fun getTrending(languages: Set<String> = setOf("Tamil", "English")): List<YtTrack> = withContext(Dispatchers.IO) {
        val query = when {
            "Tamil" in languages && "English" in languages -> "Latest Tamil English Hit Songs 2026"
            "Tamil" in languages -> "Latest Tamil Songs 2026 Anirudh AR Rahman"
            "Telugu" in languages -> "Latest Telugu Hit Songs 2026"
            "Hindi" in languages -> "Top Bollywood Songs 2026"
            "Malayalam" in languages -> "Latest Malayalam Songs 2026"
            else -> "Top Trending Global Songs 2026"
        }
        searchSongs(query)
    }

    fun invalidateStreamCache(videoId: String) {
        if (videoId.isNotBlank()) streamUrlCache.remove(videoId)
    }

    /**
     * Fetches official YouTube Music "Up Next" radio queue of similar tracks based on the currently playing song.
     */
    suspend fun fetchUpNextRadio(videoId: String, title: String, artist: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val cleanVid = if (videoId.startsWith("saavn_")) "" else videoId.trim()
        val seedLower = title.lowercase().trim()
        val tracks = mutableListOf<YtTrack>()

        val resolvedVid = cleanVid.ifBlank {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            searchSongs("$cleanTitle $artist").firstOrNull()?.videoId ?: ""
        }

        if (resolvedVid.isNotBlank()) {
            try {
                val conn = (URL(YT_MUSIC_NEXT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4500
                    readTimeout = 5500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", USER_AGENT_WEB)
                    setRequestProperty("Origin", "https://music.youtube.com")
                    setRequestProperty("Referer", "https://music.youtube.com/")
                    setRequestProperty("Connection", "keep-alive")
                    googleAuthCookies?.let { setRequestProperty("Cookie", it) }
                }

                val payload = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", "1.20241028.01.00")
                            put("hl", "en")
                            put("gl", "IN")
                        })
                    })
                    put("videoId", resolvedVid)
                    put("playlistId", "RDAMVM$resolvedVid")
                    put("isAudioOnly", true)
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

                if (conn.responseCode in 200..299) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val root = JSONObject(respText)
                    val tabs = root.optJSONObject("contents")
                        ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                        ?.optJSONObject("tabbedRenderer")
                        ?.optJSONObject("watchNextTabbedResultsRenderer")
                        ?.optJSONArray("tabs")
                    val contents = tabs?.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicQueueRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("playlistPanelRenderer")
                        ?.optJSONArray("contents")

                    if (contents != null) {
                        for (i in 0 until contents.length()) {
                            val itemObj = contents.optJSONObject(i) ?: continue
                            val panelItem = itemObj.optJSONObject("playlistPanelVideoRenderer")
                                ?: itemObj.optJSONObject("playlistPanelVideoWrapperRenderer")
                                    ?.optJSONObject("primaryRenderer")
                                    ?.optJSONObject("playlistPanelVideoRenderer")
                                ?: continue
                            val vid = panelItem.optString("videoId", "")
                            if (vid.isBlank() || vid == resolvedVid) continue

                            val titleRuns = panelItem.optJSONObject("title")?.optJSONArray("runs")
                            val t = titleRuns?.optJSONObject(0)?.optString("text", "") ?: ""
                            val bylineRuns = panelItem.optJSONObject("shortBylineText")?.optJSONArray("runs")
                                ?: panelItem.optJSONObject("longBylineText")?.optJSONArray("runs")
                            val a = bylineRuns?.optJSONObject(0)?.optString("text", artist.ifBlank { "YouTube Music" })
                                ?: artist.ifBlank { "YouTube Music" }
                            val dur = panelItem.optJSONObject("lengthText")?.optJSONArray("runs")
                                ?.optJSONObject(0)?.optString("text", "") ?: ""
                            val thumbs = panelItem.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val rawThumb = if (thumbs != null && thumbs.length() > 0) {
                                thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                            } else ""

                            if (t.isNotBlank() && t.lowercase().trim() != seedLower && tracks.none { it.videoId == vid || it.title.equals(t, ignoreCase = true) }) {
                                tracks.add(
                                    YtTrack(
                                        videoId = vid,
                                        title = t,
                                        artist = a,
                                        durationText = dur,
                                        thumbUrl = boostThumbResolution(rawThumb, vid),
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("YtMusicClient", "fetchUpNextRadio error: ${e.message}")
            }
        }

        // Fallback to smart artist/vibe search if radio returned fewer than 5 items
        if (tracks.size < 5) {
            val primaryArtist = artist.substringBefore(",").substringBefore("•").trim()
            val query = if (primaryArtist.isNotBlank() && !primaryArtist.equals("Unknown", ignoreCase = true) && !primaryArtist.equals("YouTube Music", ignoreCase = true)) {
                "$primaryArtist best songs"
            } else {
                "${title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()} similar songs"
            }
            searchSongs(query).forEach { hit ->
                if (hit.videoId != resolvedVid && hit.title.lowercase().trim() != seedLower &&
                    tracks.none { it.videoId == hit.videoId || it.title.equals(hit.title, ignoreCase = true) }
                ) {
                    tracks.add(hit)
                }
            }
        }
        tracks.take(16)
    }

    /**
     * Ultra-fast direct audio stream resolution returning stream URL + exact matching User-Agent.
     * 3-tier fallback: iOS -> Android -> Android VR.
     */
    suspend fun resolveStreamUrlWithUa(videoId: String, forceRefresh: Boolean = false): YtResolvedStream? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            streamUrlCache.get(videoId)?.let { (cached, expiry) ->
                if (now < expiry) return@withContext cached
            }
        }

        val clients = listOf("IOS", "ANDROID", "ANDROID_VR")
        for (client in clients) {
            val resolved = fetchStreamWithClient(videoId, client)
            if (resolved != null && resolved.url.isNotBlank()) {
                streamUrlCache.put(videoId, resolved to (now + 2 * 3600 * 1000L))
                return@withContext resolved
            }
        }
        null
    }

    suspend fun resolveStreamUrl(videoId: String): String? = resolveStreamUrlWithUa(videoId)?.url

    private fun fetchStreamWithClient(videoId: String, clientType: String): YtResolvedStream? {
        try {
            val ua = when (clientType) {
                "IOS" -> USER_AGENT_IOS
                "ANDROID_VR" -> USER_AGENT_ANDROID_VR
                else -> USER_AGENT_ANDROID
            }
            val url = URL(YT_PLAYER)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4500
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Connection", "keep-alive")
                googleAuthCookies?.let { setRequestProperty("Cookie", it) }
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        when (clientType) {
                            "IOS" -> {
                                put("clientName", "IOS")
                                put("clientVersion", "19.29.1")
                                put("deviceMake", "Apple")
                                put("deviceModel", "iPhone16,2")
                                put("hl", "en")
                                put("gl", "IN")
                            }
                            "ANDROID_VR" -> {
                                put("clientName", "ANDROID_VR")
                                put("clientVersion", "1.56.21")
                                put("deviceMake", "Oculus")
                                put("deviceModel", "Quest 3")
                                put("androidSdkVersion", 32)
                                put("osName", "Android")
                                put("osVersion", "12")
                                put("hl", "en")
                                put("gl", "IN")
                            }
                            else -> {
                                put("clientName", "ANDROID")
                                put("clientVersion", "21.26.364")
                                put("androidSdkVersion", 30)
                                put("osName", "Android")
                                put("osVersion", "11")
                                put("hl", "en")
                                put("gl", "IN")
                            }
                        }
                    })
                })
                put("videoId", videoId)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(respText)
                val streamingData = root.optJSONObject("streamingData") ?: return null

                // A. Prefer adaptive audio-only formats (highest bitrate Opus 251 / AAC 140) for fast audio-only streaming
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                if (adaptive != null) {
                    var bestAudioUrl: String? = null
                    var bestBitrate = 0

                    for (i in 0 until adaptive.length()) {
                        val f = adaptive.optJSONObject(i) ?: continue
                        val mime = f.optString("mimeType", "")
                        if (mime.contains("audio")) {
                            val directUrl = f.optString("url", "")
                            val bitrate = f.optInt("bitrate", 0)
                            if (directUrl.isNotBlank() && bitrate > bestBitrate) {
                                bestAudioUrl = directUrl
                                bestBitrate = bitrate
                            }
                        }
                    }
                    if (bestAudioUrl != null) return YtResolvedStream(bestAudioUrl, ua)
                }

                // B. Fallback to combined formats (Format 18: AAC audio 44.1kHz)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val directUrl = f.optString("url", "")
                        if (directUrl.isNotBlank()) return YtResolvedStream(directUrl, ua)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val cacheKey = "$title::$artist".lowercase()
        lyricsCache.get(cacheKey)?.let { return@withContext it }

        try {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            val cleanArtist = artist.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            val encTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val targetUrl = "$LRCLIB_API?track_name=$encTitle&artist_name=$encArtist"

            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3500
                setRequestProperty("User-Agent", "MSPlayer/0.7.0 (Android; OpenSource)")
                setRequestProperty("Connection", "keep-alive")
            }

            if (conn.responseCode in 200..299) {
                val json = JSONObject(BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() })
                val synced = if (!json.isNull("syncedLyrics")) json.optString("syncedLyrics", "").trim() else ""
                if (synced.isNotBlank() && !synced.equals("null", ignoreCase = true)) {
                    lyricsCache.put(cacheKey, synced)
                    return@withContext synced
                }
                val plain = if (!json.isNull("plainLyrics")) json.optString("plainLyrics", "").trim() else ""
                if (plain.isNotBlank() && !plain.equals("null", ignoreCase = true)) {
                    lyricsCache.put(cacheKey, plain)
                    return@withContext plain
                }
            }
        } catch (_: Exception) {}
        null
    }
}
