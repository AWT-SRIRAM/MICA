package app.msplayer.offline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FeedCacheManager {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _cachedTopHits = MutableStateFlow<List<YtTrack>>(emptyList())
    val cachedTopHits = _cachedTopHits.asStateFlow()

    private val _cachedTrendingPlaylists = MutableStateFlow<List<PlaylistCard>>(emptyList())
    val cachedTrendingPlaylists = _cachedTrendingPlaylists.asStateFlow()

    private val _cachedArtists = MutableStateFlow<List<ArtistCard>>(emptyList())
    val cachedArtists = _cachedArtists.asStateFlow()

    private val _isPreloaded = MutableStateFlow(false)
    val isPreloaded = _isPreloaded.asStateFlow()

    private var hasPreloadStarted = false

    fun preloadAll(context: Context) {
        if (hasPreloadStarted) return
        hasPreloadStarted = true

        scope.launch {
            // 1. Immediately hydrate from disk cache (0ms instant display)
            loadFromDisk(context)

            // If disk was completely empty, seed with high quality curated seeds
            if (_cachedTopHits.value.isEmpty()) {
                _cachedTopHits.value = defaultCuratedHits()
            }
            if (_cachedTrendingPlaylists.value.isEmpty()) {
                _cachedTrendingPlaylists.value = defaultCuratedPlaylists()
            }
            if (_cachedArtists.value.isEmpty()) {
                _cachedArtists.value = defaultCuratedArtists()
            }
            _isPreloaded.value = true

            // 2. Pre-warm ad-shield rules
            MicaAdShield.preload(context)

            // 3. Quietly fetch fresh online updates without blocking UI
            refreshOnlineFeeds(context)
        }
    }

    private suspend fun refreshOnlineFeeds(context: Context) = withContext(Dispatchers.IO) {
        try {
            // Fetch top trending hits (Tamil + Global)
            val freshHits = runCatching {
                val ytHits = YtMusicClient.searchSongs("Tamil Trending Songs 2026")
                if (ytHits.isNotEmpty()) ytHits else {
                    JioSaavnClient.searchSongs("Tamil Hits").map { s ->
                        YtTrack(
                            videoId = "saavn_${s.id}",
                            title = s.title,
                            artist = s.artist,
                            durationText = "",
                            thumbUrl = s.thumbUrl,
                            directStreamUrl = s.streamUrl
                        )
                    }
                }
            }.getOrDefault(emptyList())

            if (freshHits.isNotEmpty()) {
                val deduped = freshHits.deduplicated()
                _cachedTopHits.value = deduped
                saveListToDisk(context, "feed_top_hits.json", deduped) { it.toJson() }
            }

            // Fetch playlists
            val freshPlaylists = runCatching {
                YtMusicClient.searchPlaylists("Tamil Hits Playlist")
            }.getOrDefault(emptyList())

            if (freshPlaylists.isNotEmpty()) {
                val merged = (freshPlaylists + defaultCuratedPlaylists()).distinctBy { it.title }
                _cachedTrendingPlaylists.value = merged
                saveListToDisk(context, "feed_playlists.json", merged) { it.toJson() }
            }
        } catch (_: Exception) {}
    }

    private fun loadFromDisk(context: Context) {
        runCatching {
            val hits = loadListFromDisk(context, "feed_top_hits.json") { ytTrackFromJson(it) }
            if (hits.isNotEmpty()) _cachedTopHits.value = hits

            val playlists = loadListFromDisk(context, "feed_playlists.json") { playlistCardFromJson(it) }
            if (playlists.isNotEmpty()) _cachedTrendingPlaylists.value = playlists

            val artists = loadListFromDisk(context, "feed_artists.json") { artistCardFromJson(it) }
            if (artists.isNotEmpty()) _cachedArtists.value = artists
        }
    }

    private fun <T> saveListToDisk(context: Context, filename: String, list: List<T>, serializer: (T) -> JSONObject) {
        runCatching {
            val file = File(context.cacheDir, filename)
            val jsonArray = JSONArray()
            list.take(60).forEach { jsonArray.put(serializer(it)) }
            file.writeText(jsonArray.toString())
        }
    }

    private fun <T> loadListFromDisk(context: Context, filename: String, deserializer: (JSONObject) -> T?): List<T> {
        return runCatching {
            val file = File(context.cacheDir, filename)
            if (!file.exists()) return emptyList()
            val content = file.readText()
            val jsonArray = JSONArray(content)
            val result = mutableListOf<T>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                deserializer(obj)?.let { result.add(it) }
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun YtTrack.toJson() = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        put("durationText", durationText)
        put("thumbUrl", thumbUrl)
        put("directStreamUrl", directStreamUrl)
    }

    private fun ytTrackFromJson(obj: JSONObject): YtTrack? {
        val vid = obj.optString("videoId")
        val title = obj.optString("title")
        if (vid.isBlank() || title.isBlank()) return null
        return YtTrack(
            videoId = vid,
            title = title,
            artist = obj.optString("artist", "Unknown"),
            durationText = obj.optString("durationText", ""),
            thumbUrl = obj.optString("thumbUrl", ""),
            directStreamUrl = obj.optString("directStreamUrl", "")
        )
    }

    private fun PlaylistCard.toJson() = JSONObject().apply {
        put("title", title)
        put("subtitle", subtitle)
        put("coverUrl", coverUrl)
        put("searchQuery", searchQuery)
        put("listId", listId)
        put("songCount", songCount)
    }

    private fun playlistCardFromJson(obj: JSONObject): PlaylistCard? {
        val title = obj.optString("title")
        if (title.isBlank()) return null
        return PlaylistCard(
            title = title,
            subtitle = obj.optString("subtitle", ""),
            coverUrl = obj.optString("coverUrl", ""),
            searchQuery = obj.optString("searchQuery", ""),
            listId = obj.optString("listId", ""),
            songCount = obj.optInt("songCount", 0)
        )
    }

    private fun ArtistCard.toJson() = JSONObject().apply {
        put("name", name)
        put("subtitle", subtitle)
        put("avatarUrl", avatarUrl)
        put("searchQuery", searchQuery)
    }

    private fun artistCardFromJson(obj: JSONObject): ArtistCard? {
        val name = obj.optString("name")
        if (name.isBlank()) return null
        return ArtistCard(
            name = name,
            subtitle = obj.optString("subtitle", ""),
            avatarUrl = obj.optString("avatarUrl", ""),
            searchQuery = obj.optString("searchQuery", "")
        )
    }

    fun defaultCuratedHits(): List<YtTrack> = listOf(
        YtTrack("hit_1", "Hukum - Thalaivar Alappara", "Anirudh Ravichander", "3:27", "https://c.saavncdn.com/978/Jailer-Tamil-2023-20230728173617-500x500.jpg"),
        YtTrack("hit_2", "Badass", "Anirudh Ravichander", "3:49", "https://c.saavncdn.com/584/Leo-Tamil-2023-20231020083232-500x500.jpg"),
        YtTrack("hit_3", "Arabic Kuthu", "Anirudh Ravichander, Jonita Gandhi", "4:39", "https://c.saavncdn.com/152/Beast-Tamil-2022-20220504183850-500x500.jpg"),
        YtTrack("hit_4", "Naa Ready", "Thalapathy Vijay, Anirudh", "4:08", "https://c.saavncdn.com/584/Leo-Tamil-2023-20231020083232-500x500.jpg"),
        YtTrack("hit_5", "Illuminati", "Sushin Shyam, Dabzee", "3:12", "https://c.saavncdn.com/712/Aavesham-Malayalam-2024-20240412173452-500x500.jpg"),
        YtTrack("hit_6", "Fear Song", "Anirudh Ravichander", "3:16", "https://c.saavncdn.com/832/Devara-Part-1-Tamil-Tamil-2024-20240927163821-500x500.jpg"),
        YtTrack("hit_7", "Whistle Podu", "Thalapathy Vijay, Yuvan Shankar Raja", "3:55", "https://c.saavncdn.com/782/The-Greatest-Of-All-Time-Tamil-2024-20240905183421-500x500.jpg"),
        YtTrack("hit_8", "Chuttamalle", "Shilpa Rao, Anirudh", "3:44", "https://c.saavncdn.com/832/Devara-Part-1-Tamil-Tamil-2024-20240927163821-500x500.jpg")
    )

    fun defaultCuratedPlaylists(): List<PlaylistCard> = listOf(
        PlaylistCard("Tamil Trending Songs", "Chart-toppers and viral hits", "https://c.saavncdn.com/editorial/Tamil-TopTrending_20231120063715.jpg", "Tamil Trending 2026", "RDCLAK5uy_k"),
        PlaylistCard("Anirudh Musical Universe", "High-octane mass beats & soul anthems", "https://c.saavncdn.com/editorial/BestofAnirudhRavichander_20231016104812.jpg", "Anirudh Ravichander Hits", "RDCLAK5uy_l"),
        PlaylistCard("A.R. Rahman Classics", "Evergreen timeless masterpieces", "https://c.saavncdn.com/editorial/BestofARRahmanTamil_20231110093845.jpg", "AR Rahman Tamil Hits", "RDCLAK5uy_m"),
        PlaylistCard("Harris Jayaraj Melodies", "Breezy romantic nostalgia", "https://c.saavncdn.com/editorial/BestofHarrisJayaraj_20231019053218.jpg", "Harris Jayaraj Melodies", "RDCLAK5uy_n"),
        PlaylistCard("Yuvan Drug Anthems", "The iconic soul healer tracks", "https://c.saavncdn.com/editorial/BestofYuvanShankarRaja_20231019053304.jpg", "Yuvan Shankar Raja Hits", "RDCLAK5uy_o"),
        PlaylistCard("Top 50 Global Hits", "Worldwide popular trending hits", "https://i.scdn.co/image/ab67706c0000da84fc11649934e62248ceae20ea", "Top 50 Global", "RDCLAK5uy_p")
    )

    fun defaultCuratedArtists(): List<ArtistCard> = listOf(
        ArtistCard("Anirudh Ravichander", "Rockstar Composer", "https://c.saavncdn.com/artists/Anirudh_Ravichander_004_20231018073539_500x500.jpg", "Anirudh Ravichander"),
        ArtistCard("A.R. Rahman", "Mozart of Madras", "https://c.saavncdn.com/artists/A_R_Rahman_002_20231102073952_500x500.jpg", "A.R. Rahman"),
        ArtistCard("Yuvan Shankar Raja", "U1 Anthems", "https://c.saavncdn.com/artists/Yuvan_Shankar_Raja_003_20231018074812_500x500.jpg", "Yuvan Shankar Raja"),
        ArtistCard("Harris Jayaraj", "Melody King", "https://c.saavncdn.com/artists/Harris_Jayaraj_002_20231018073612_500x500.jpg", "Harris Jayaraj"),
        ArtistCard("Sid Sriram", "Soulful Vocals", "https://c.saavncdn.com/artists/Sid_Sriram_002_20231018074218_500x500.jpg", "Sid Sriram"),
        ArtistCard("Jonita Gandhi", "Vocal Powerhouse", "https://c.saavncdn.com/artists/Jonita_Gandhi_002_20231018073745_500x500.jpg", "Jonita Gandhi")
    )

    fun normalizeTrackTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\(from\s+["'].*?["']\)"""), "")
            .replace(Regex("""(?i)\(from\s+.*?\)"""), "")
            .replace(Regex("""(?i)\[from\s+.*?\]"""), "")
            .replace(Regex("""(?i)\(feat\..*?\)"""), "")
            .replace(Regex("""(?i)\(official.*?\)"""), "")
            .replace(Regex("""(?i)\(lyric.*?\)"""), "")
            .replace(Regex("""(?i)\(video.*?\)"""), "")
            .replace(Regex("""(?i)-?\s*best of\s+.*$"""), "")
            .replace(Regex("""(?i)-?\s*original sound.*$"""), "")
            .replace(Regex("""[^a-zA-Z0-9\u0B80-\u0BFF]"""), "")
            .lowercase()
            .trim()
    }

    fun List<YtTrack>.deduplicated(): List<YtTrack> {
        val seenTitles = mutableSetOf<String>()
        val seenVids = mutableSetOf<String>()
        return filter { track ->
            val norm = normalizeTrackTitle(track.title)
            val isUnique = (norm.isBlank() || seenTitles.add(norm)) && seenVids.add(track.videoId)
            isUnique
        }
    }
}
