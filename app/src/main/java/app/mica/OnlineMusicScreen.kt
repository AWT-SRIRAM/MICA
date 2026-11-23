package app.mica

import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object OnlineImageCache {
    private val memoryCache = LruCache<String, Bitmap>(80)

    fun get(url: String): Bitmap? = memoryCache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        memoryCache.put(url, bitmap)
    }
    fun evict(url: String) {
        memoryCache.remove(url)
    }
}

@Composable
fun rememberRemoteBitmap(url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf(OnlineImageCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                val realUrl = if (url.startsWith("spotify_track://")) {
                    val trId = url.removePrefix("spotify_track://")
                    SpotifyClient.resolveTrackThumbnail(trId)
                } else url

                val localFilePath = when {
                    realUrl?.startsWith("file://") == true -> realUrl.removePrefix("file://")
                    realUrl?.startsWith("/") == true -> realUrl
                    else -> null
                }
                if (localFilePath != null) {
                    val file = java.io.File(localFilePath)
                    if (file.exists() && file.canRead()) {
                        val loaded = runCatching {
                            BitmapFactory.decodeFile(file.absolutePath)
                        }.getOrNull()
                        if (loaded != null) {
                            OnlineImageCache.put(url, loaded)
                            withContext(Dispatchers.Main) { bitmap = loaded }
                            return@withContext
                        }
                    }
                }

                if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                    val cached = OnlineImageCache.get(realUrl)
                    if (cached != null) {
                        OnlineImageCache.put(url, cached)
                        withContext(Dispatchers.Main) { bitmap = cached }
                        return@withContext
                    }
                    val fetchBitmap: (String) -> Bitmap? = { targetUrl ->
                        runCatching {
                            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 4000
                                readTimeout = 4000
                                instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                setRequestProperty("Connection", "keep-alive")
                            }
                            if (conn.responseCode in 200..299) {
                                val bytes = conn.inputStream.use { it.readBytes() }
                                val opts = BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            } else null
                        }.getOrNull()
                    }

                    var loaded = fetchBitmap(realUrl)
                    // If 500x500 fails (e.g. JioSaavn CDN returns 404 for older tracks), fallback to 150x150
                    if (loaded == null && realUrl.contains("500x500.jpg")) {
                        loaded = fetchBitmap(realUrl.replace("500x500.jpg", "150x150.jpg"))
                    }
                    // If YouTube maxresdefault fails, fallback to hqdefault
                    if (loaded == null && realUrl.contains("maxresdefault.jpg")) {
                        loaded = fetchBitmap(realUrl.replace("maxresdefault.jpg", "hqdefault.jpg"))
                    }

                    if (loaded != null) {
                        OnlineImageCache.put(realUrl, loaded)
                        OnlineImageCache.put(url, loaded)
                        withContext(Dispatchers.Main) {
                            bitmap = loaded
                        }
                    }
                }
            }
        }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineMusicScreen(
    activity: MainActivity,
    service: PlaybackService?,
    palette: AppPalette,
    glassEnabled: Boolean,
    bottomPadding: Dp,
    onNavigateToTab: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val activeDownloads by AudioDownloadManager.activeDownloads.collectAsState()
    val fallbackPlayerState = remember { MutableStateFlow(PlayerState()) }
    val playerState by (service?.state ?: fallbackPlayerState).collectAsState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var userSelectedSuggestion by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<YtTrack>>(FeedCacheManager.cachedTopHits.value.ifEmpty { FeedCacheManager.defaultCuratedHits() }.let { FeedCacheManager.run { it.deduplicated() } }) }
    var activeCategory by rememberSaveable { mutableStateOf(activity.savedOnlineCategory) }
    LaunchedEffect(activeCategory) { activity.savedOnlineCategory = activeCategory }

    var searchHistory by remember {
        val raw = Store.prefs(context).getString("online_search_history_v1", "") ?: ""
        mutableStateOf(raw.split("\n").map { it.trim() }.filter { it.isNotBlank() })
    }

    fun saveToSearchHistory(q: String) {
        val clean = q.trim()
        if (clean.isBlank()) return
        val updated = (listOf(clean) + searchHistory.filter { !it.equals(clean, ignoreCase = true) }).take(25)
        searchHistory = updated
        Store.prefs(context).edit().putString("online_search_history_v1", updated.joinToString("\n")).apply()
    }

    fun removeFromSearchHistory(item: String) {
        val updated = searchHistory.filter { it != item }
        searchHistory = updated
        Store.prefs(context).edit().putString("online_search_history_v1", updated.joinToString("\n")).apply()
    }

    LaunchedEffect(searchQuery, isSearchFocused) {
        showSuggestions = false
    }
    var showLanguageModal by remember { mutableStateOf(false) }
    var showJamDialog by remember { mutableStateOf(false) }
    var showGoogleAuthModal by remember { mutableStateOf(false) }
    var showGoogleSignOutConfirm by remember { mutableStateOf(false) }
    var googleSignedIn by remember { mutableStateOf(YtMusicClient.isGoogleSignedIn(context)) }
    var loadingTrackId by remember { mutableStateOf<String?>(null) }
    var activePlayJob by remember { mutableStateOf<Job?>(null) }

    // Interactive Playlist View state
    var selectedPlaylist by remember { mutableStateOf<PlaylistCard?>(null) }
    var playlistTracks by remember { mutableStateOf<List<YtTrack>>(emptyList()) }
    var isLoadingPlaylistTracks by remember { mutableStateOf(false) }
    var searchedPlaylists by remember { mutableStateOf<List<PlaylistCard>>(emptyList()) }

    // Persistent language preferences (Default: Tamil)
    val availableLanguages = listOf("Tamil", "English", "Telugu", "Hindi", "Malayalam", "Punjabi", "Kannada")
    var selectedLanguages by remember {
        val saved = Store.prefs(context).getStringSet("online_languages_v1", setOf("Tamil")) ?: setOf("Tamil")
        mutableStateOf(saved)
    }

    // Track user's favorite / recently played artists for personalized "For You" recommendations
    var topPersonalArtists by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val savedArtists = Store.prefs(context).getString("recent_online_artists_v1", "")
                ?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val historyArtists = runCatching {
                val dao = Store.db(activity).dao()
                val allMedia = activity.audios
                val favUris = dao.entries("Favorites").map { it.uri }.toSet()
                val histUris = dao.history().take(50).map { it.uri }
                (allMedia.filter { it.uri in favUris } + histUris.mapNotNull { u -> allMedia.find { it.uri == u } })
                    .mapNotNull { it.artist.substringBefore(",").substringBefore("&").substringBefore("•").trim().takeIf { a -> a.length > 2 && !a.equals("Unknown", true) && !a.equals("Unknown Artist", true) } }
            }.getOrDefault(emptyList())
            val merged = (savedArtists + historyArtists).distinct().take(10)
            withContext(Dispatchers.Main) { topPersonalArtists = merged }
        }
    }

    val themeVibeLabel = when (palette.mode) {
        AppThemeMode.WHITE_LIQUID_GLASS -> "Crystal Light Vibes • Personalized"
        AppThemeMode.LIGHT -> "Daylight Energy • Personalized"
        AppThemeMode.DARK_LIQUID_GLASS -> "Midnight Glass Vibes • Personalized"
        AppThemeMode.DARK -> "Deep Obsidian Mix • Personalized"
    }

    val categories = remember(palette.mode) {
        listOf(
            "✨ For You",
            "🎬 Videos",
            "💎 Hi-Res FLAC",
            "#tamil dance",
            "#tamil pop",
            "🎵 Feel Good",
            "#trending",
            "Popular Artists",
            "Kollywood",
            "Spotify"
        )
    }

    fun recordPlayedArtist(artistName: String) {
        val primary = artistName.substringBefore(",").substringBefore("•").trim()
        if (primary.length < 3 || primary.equals("Unknown", true) || primary.equals("YouTube Music", true)) return
        val updated = (listOf(primary) + topPersonalArtists.filterNot { it.equals(primary, true) }).take(6)
        topPersonalArtists = updated
        Store.prefs(context).edit().putString("recent_online_artists_v1", updated.joinToString("|")).apply()
    }

    fun openPlaylist(playlist: PlaylistCard) {
        selectedPlaylist = playlist
        isLoadingPlaylistTracks = true
        scope.launch {
            val tracks = when {
                playlist.listId.startsWith("VL") || playlist.listId.startsWith("PL") || playlist.listId.startsWith("RD") || playlist.listId.startsWith("MPREb_") -> {
                    YtMusicClient.fetchPlaylistTracks(playlist.listId)
                }
                playlist.listId.isNotBlank() -> {
                    val jioTracks = JioSaavnClient.fetchPlaylistTracks(playlist.listId)
                    if (jioTracks.isNotEmpty()) jioTracks else YtMusicClient.fetchPlaylistTracks(playlist.listId)
                }
                else -> {
                    val ytHits = runCatching { YtMusicClient.searchSongs(playlist.searchQuery.ifBlank { playlist.title }) }.getOrDefault(emptyList())
                    if (ytHits.isNotEmpty()) ytHits else {
                        runCatching {
                            JioSaavnClient.searchSongs(playlist.searchQuery.ifBlank { playlist.title }).map { s ->
                                YtTrack(
                                    videoId = "saavn_${s.id}",
                                    title = s.title,
                                    artist = s.artist,
                                    thumbUrl = s.thumbUrl,
                                    directStreamUrl = s.streamUrl
                                )
                            }
                        }.getOrDefault(emptyList())
                    }
                }
            }
            playlistTracks = FeedCacheManager.run { tracks.deduplicated() }
            isLoadingPlaylistTracks = false
        }
    }

    fun playTrack(track: YtTrack, contextTracks: List<YtTrack> = emptyList()) {
        // Cancel any previous pending tap resolution so rapid taps never collide or fail silently
        activePlayJob?.cancel()
        val requestId = track.videoId
        loadingTrackId = requestId
        recordPlayedArtist(track.artist)
        activePlayJob = activity.lifecycleScope.launch {
            try {
                // High-speed Backend Dual Engine (JioSaavn 320k primary + YT Music iOS/Android/VR fallback)
                val resolved = AudioStreamResolver.resolveBestStream(
                    title = track.title,
                    artist = track.artist,
                    ytVideoId = track.videoId,
                    rawThumbUrl = track.thumbUrl,
                    directStreamUrl = track.directStreamUrl
                )
                val currentFolderName = selectedPlaylist?.title?.ifBlank { null }
                    ?: if (searchQuery.isNotBlank()) searchQuery else "Top Hits"
                if (resolved != null && service != null) {
                    val mediaItem = MediaItem(
                        uri = resolved.streamUrl,
                        title = track.title,
                        artist = track.artist,
                        audio = true,
                        thumbUri = resolved.hdThumbUrl.ifBlank { track.thumbUrl },
                        sourceVideoId = track.videoId,
                        streamUserAgent = resolved.userAgent,
                        folder = currentFolderName,
                    )
                    val queueItems = if (contextTracks.isNotEmpty()) {
                        val startIdx = contextTracks.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
                        val subsequent = contextTracks.drop(startIdx + 1).take(50).map { t ->
                            val lazyUri = t.directStreamUrl.takeIf { it.startsWith("http") }
                                ?: if (t.videoId.startsWith("saavn_")) t.videoId else "yt_${t.videoId}"
                            MediaItem(
                                uri = lazyUri,
                                title = t.title,
                                artist = t.artist,
                                audio = true,
                                thumbUri = t.thumbUrl,
                                sourceVideoId = t.videoId,
                                folder = currentFolderName,
                            )
                        }
                        listOf(mediaItem) + subsequent
                    } else {
                        listOf(mediaItem)
                    }
                    service.open(queueItems, mediaItem, autoPlay = true)
                    activity.showPlayer = true
                } else {
                    Toast.makeText(context, "Could not stream audio track — retrying alternate engine", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadingTrackId == requestId) {
                    loadingTrackId = null
                }
            }
        }
    }

    fun playAllPlaylist(tracks: List<YtTrack>, customTitle: String? = null) {
        if (tracks.isEmpty() || service == null) return
        activePlayJob?.cancel()
        val first = tracks.first()
        loadingTrackId = first.videoId
        recordPlayedArtist(first.artist)
        val playlistFolder = customTitle ?: selectedPlaylist?.title ?: "Featured Playlist"
        activePlayJob = activity.lifecycleScope.launch {
            try {
                val items = tracks.map { t ->
                    val lazyUri = t.directStreamUrl.takeIf { it.startsWith("http") }
                        ?: if (t.videoId.startsWith("saavn_")) t.videoId else "yt_${t.videoId}"
                    MediaItem(
                        uri = lazyUri,
                        title = t.title,
                        artist = t.artist,
                        audio = true,
                        thumbUri = t.thumbUrl,
                        sourceVideoId = t.videoId,
                        folder = playlistFolder,
                    )
                }
                val resolved = AudioStreamResolver.resolveBestStream(
                    first.title, first.artist, first.videoId, first.thumbUrl, first.directStreamUrl
                )
                if (resolved != null) {
                    val playable = items.toMutableList()
                    playable[0] = MediaItem(
                        uri = resolved.streamUrl,
                        title = first.title,
                        artist = first.artist,
                        audio = true,
                        thumbUri = resolved.hdThumbUrl.ifBlank { first.thumbUrl },
                        sourceVideoId = first.videoId,
                        streamUserAgent = resolved.userAgent,
                        folder = playlistFolder,
                    )
                    service.open(playable, playable[0], autoPlay = true)
                    activity.showPlayer = true
                } else {
                    Toast.makeText(context, "Could not start playlist playback", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadingTrackId == first.videoId) loadingTrackId = null
            }
        }
    }

    fun performSearch(q: String, forceRefresh: Boolean = false, userInitiated: Boolean = false) {
        if (q.isBlank()) return
        if (userInitiated) {
            saveToSearchHistory(q)
        }
        showSuggestions = false
        focusManager.clearFocus()

        // Direct Stream & YouTube Link Interception
        val isLink = q.contains("http://") || q.contains("https://") ||
                q.contains("youtube.com") || q.contains("youtu.be") || q.contains("spotify.com")
        if (isLink) {
            Store.recordStream(context, q)
            val listId = Regex("""[?&]list=([\w-]+)""").find(q)?.groupValues?.getOrNull(1)
            if (listId != null) {
                scope.launch {
                    isSearching = true
                    val tracks = YtMusicClient.fetchPlaylistTracks(listId)
                    if (tracks.isNotEmpty()) {
                        playlistTracks = FeedCacheManager.run { tracks.deduplicated() }
                        selectedPlaylist = PlaylistCard("Imported Playlist", "${tracks.size} tracks", tracks.first().thumbUrl, q, listId = listId, songCount = tracks.size)
                        playAllPlaylist(tracks)
                    } else {
                        Toast.makeText(context, "Could not load YouTube playlist", Toast.LENGTH_SHORT).show()
                    }
                    isSearching = false
                }
                return
            }

            scope.launch {
                isSearching = true
                try {
                    val resolved = StreamResolver.resolve(q)
                    if (resolved.streamUrl.isNotBlank()) {
                        if (resolved.isVideo) {
                            activity.playStream(
                                url = resolved.streamUrl,
                                title = resolved.title,
                                thumbUrl = resolved.thumbUrl,
                                isVideo = true,
                                userAgent = resolved.userAgent,
                                subtitleUrl = resolved.subtitleUrl
                            )
                        } else {
                            val track = YtTrack(
                                videoId = resolved.streamUrl,
                                title = resolved.title,
                                artist = resolved.author.ifBlank { "Online Stream" },
                                thumbUrl = resolved.thumbUrl,
                                directStreamUrl = resolved.streamUrl
                            )
                            playTrack(track)
                        }
                    } else {
                        Toast.makeText(context, "Could not resolve stream from link", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Stream resolution error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isSearching = false
                }
            }
            return
        }

        if (activeCategory != "Playlists") {
            val cached = if (!forceRefresh) OnlineMusicCache.get(context, q) else null
            if (cached != null && cached.isNotEmpty()) {
                searchResults = FeedCacheManager.run { cached.deduplicated() }
                isSearching = false
                return
            }
        }
        isSearching = true
        scope.launch {
            val primaryLang = selectedLanguages.firstOrNull { it.equals("Tamil", true) } ?: selectedLanguages.firstOrNull() ?: "Tamil"
            val langQuery = if (!q.contains(primaryLang, ignoreCase = true)) "$q $primaryLang" else ""

            if (activeCategory == "Playlists") {
                val jioDef = async { JioSaavnClient.searchPlaylists(q) }
                val ytDef = async { YtMusicClient.searchPlaylists(q) }
                val jioLangDef = async { if (langQuery.isNotBlank()) JioSaavnClient.searchPlaylists(langQuery) else emptyList() }
                val ytLangDef = async { if (langQuery.isNotBlank()) YtMusicClient.searchPlaylists(langQuery) else emptyList() }
                val jio = runCatching { jioDef.await() }.getOrDefault(emptyList())
                val yt = runCatching { ytDef.await() }.getOrDefault(emptyList())
                val jioLang = runCatching { jioLangDef.await() }.getOrDefault(emptyList())
                val ytLang = runCatching { ytLangDef.await() }.getOrDefault(emptyList())
                searchedPlaylists = (jioLang + ytLang + jio + yt).distinctBy { it.title.lowercase() }
            } else {
                val ytDef = async { runCatching { YtMusicClient.searchSongs(q) }.getOrDefault(emptyList()) }
                val ytLangDef = async {
                    if (langQuery.isNotBlank()) runCatching { YtMusicClient.searchSongs(langQuery) }.getOrDefault(emptyList())
                    else emptyList()
                }
                val jioDef = async {
                    runCatching {
                        JioSaavnClient.searchSongs(q).map { s ->
                            YtTrack(
                                videoId = "saavn_${s.id}",
                                title = s.title,
                                artist = s.artist,
                                thumbUrl = s.thumbUrl,
                                directStreamUrl = s.streamUrl,
                                language = s.language
                            )
                        }
                    }.getOrDefault(emptyList())
                }
                val jioLangDef = async {
                    if (langQuery.isNotBlank()) {
                        runCatching {
                            JioSaavnClient.searchSongs(langQuery).map { s ->
                                YtTrack(
                                    videoId = "saavn_${s.id}",
                                    title = s.title,
                                    artist = s.artist,
                                    thumbUrl = s.thumbUrl,
                                    directStreamUrl = s.streamUrl,
                                    language = s.language
                                )
                            }
                        }.getOrDefault(emptyList())
                    } else emptyList()
                }

                val ytResults = ytDef.await()
                val ytLangResults = ytLangDef.await()
                val jioResults = jioDef.await()
                val jioLangResults = jioLangDef.await()

                val allBaseYt = if (userInitiated) {
                    (ytResults + ytLangResults).distinctBy { it.videoId }
                } else {
                    (ytLangResults + ytResults).distinctBy { it.videoId }
                }
                val allBaseJio = if (userInitiated) {
                    (jioResults + jioLangResults).distinctBy { it.videoId }
                } else {
                    (jioLangResults + jioResults).distinctBy { it.videoId }
                }

                val enrichedYt = allBaseYt.map { yt ->
                    if (yt.artist.equals("Unknown", true) || yt.artist.isBlank()) {
                        val match = allBaseJio.find { it.title.equals(yt.title, true) || it.title.contains(yt.title, true) || yt.title.contains(it.title, true) }
                        if (match != null && match.artist.isNotBlank() && !match.artist.equals("Unknown", true)) {
                            yt.copy(artist = match.artist)
                        } else yt
                    } else yt
                }

                // For user queries: put direct search query matches first, followed by language expanded results
                val directMatches = if (userInitiated) {
                    (enrichedYt + allBaseJio).distinctBy { it.videoId }
                } else emptyList()

                // Language-preferred tracks
                val langFiltered = (allBaseJio + enrichedYt).filter {
                    it.language.equals(primaryLang, ignoreCase = true) ||
                    it.title.contains(primaryLang, ignoreCase = true) ||
                    it.artist.contains(primaryLang, ignoreCase = true)
                }
                val preferredLanguageTracks = (jioLangResults + ytLangResults + langFiltered).distinctBy { it.title.lowercase().trim() }

                val combined = if (userInitiated) {
                    (directMatches + preferredLanguageTracks).distinctBy { it.videoId }
                } else {
                    (preferredLanguageTracks + enrichedYt + allBaseJio).distinctBy { it.videoId }
                }

                var results = FeedCacheManager.run { combined.deduplicated() }
                if (results.isEmpty() && userInitiated) {
                    val geminiQueries = GeminiRecommender.recommendMusicQueries(context, topPersonalArtists, preferredLanguage = primaryLang, mood = q)
                    val topRec = geminiQueries.firstOrNull()
                    if (!topRec.isNullOrBlank()) {
                        val recYt = runCatching { YtMusicClient.searchSongs(topRec) }.getOrDefault(emptyList())
                        val recJio = runCatching {
                            JioSaavnClient.searchSongs(topRec).map { s ->
                                YtTrack(videoId = "saavn_${s.id}", title = s.title, artist = s.artist, thumbUrl = s.thumbUrl, directStreamUrl = s.streamUrl, language = s.language)
                            }
                        }.getOrDefault(emptyList())
                        results = FeedCacheManager.run { (recJio + recYt).deduplicated() }
                    }
                }
                if (results.isNotEmpty()) {
                    searchResults = results
                    OnlineMusicCache.put(context, q, results)
                }
                // Background pre-fetch playlists for seamless instant filter toggle
                launch {
                    val jioDef = async { JioSaavnClient.searchPlaylists(q) }
                    val ytDef = async { YtMusicClient.searchPlaylists(q) }
                    val jio = runCatching { jioDef.await() }.getOrDefault(emptyList())
                    val yt = runCatching { ytDef.await() }.getOrDefault(emptyList())
                    searchedPlaylists = (yt + jio).distinctBy { it.title.lowercase() }
                }
                // High-speed pre-resolution of top 3 tracks in background for instant 0ms tap start
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    results.take(3).forEach { track ->
                        AudioStreamResolver.resolveBestStream(track.title, track.artist, track.videoId, track.thumbUrl, track.directStreamUrl)
                    }
                }
            }
            isSearching = false
        }
    }

    fun loadCategoryTracks(cat: String) {
        if (cat == "Playlists") {
            selectedPlaylist = null
            if (searchQuery.isNotBlank()) {
                performSearch(searchQuery)
            }
            return
        }
        if (cat == "💎 Hi-Res FLAC") {
            val flacStations = listOf(
                YtTrack(
                    videoId = "flac_rp_main",
                    title = "Radio Paradise (Main Mix)",
                    artist = "Audiophile 16-bit / 44.1 kHz FLAC",
                    durationText = "Live Hi-Res",
                    thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/7/78/Radio_Paradise_logo.png",
                    directStreamUrl = "https://stream.radioparadise.com/flac"
                ),
                YtTrack(
                    videoId = "flac_rp_mellow",
                    title = "Radio Paradise (Mellow Mix)",
                    artist = "Audiophile 16-bit / 44.1 kHz FLAC",
                    durationText = "Live Hi-Res",
                    thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/7/78/Radio_Paradise_logo.png",
                    directStreamUrl = "https://stream.radioparadise.com/mellow-flac"
                ),
                YtTrack(
                    videoId = "flac_rp_rock",
                    title = "Radio Paradise (Rock Mix)",
                    artist = "Audiophile 16-bit / 44.1 kHz FLAC",
                    durationText = "Live Hi-Res",
                    thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/7/78/Radio_Paradise_logo.png",
                    directStreamUrl = "https://stream.radioparadise.com/rock-flac"
                ),
                YtTrack(
                    videoId = "flac_mother_earth",
                    title = "Mother Earth Radio (Vinyl Master)",
                    artist = "Ultra HD 24-bit / 96 kHz Master FLAC",
                    durationText = "Live Master",
                    thumbUrl = "https://cdn-profiles.tunein.com/s341094/images/logoq.jpg",
                    directStreamUrl = "https://motherearth.stream:8443/motherearth"
                ),
                YtTrack(
                    videoId = "flac_jb_radio",
                    title = "JB Radio-2 (Audiophile Acoustic)",
                    artist = "FLAC 24-bit / 96 kHz Live Stream",
                    durationText = "Live Hi-Res",
                    thumbUrl = "https://cdn-profiles.tunein.com/s341094/images/logoq.jpg",
                    directStreamUrl = "http://199.189.111.30:8010/flac"
                )
            )
            searchResults = flacStations
            isSearching = false
            return
        }
        if (cat == "✨ For You") {
            scope.launch {
                val geminiQueries = GeminiRecommender.recommendMusicQueries(context, topPersonalArtists, "Tamil")
                val chosenQuery = geminiQueries.randomOrNull() ?: run {
                    val discoverySeeds = listOf("Melody Hits", "Radio Mix", "Unplugged", "Chill Vibes", "Acoustic Hits", "Essentials", "Top Tracks")
                    val seedVibe = discoverySeeds.random()
                    val pickedArtists = if (topPersonalArtists.isNotEmpty()) topPersonalArtists.shuffled().take(2).joinToString(" ") else "Anirudh AR Rahman"
                    "Tamil $pickedArtists $seedVibe 2026"
                }
                performSearch(chosenQuery, userInitiated = false)
            }
            return
        }
        val cached = OnlineMusicCache.get(context, cat)
        if (cached != null && cached.isNotEmpty()) {
            searchResults = FeedCacheManager.run { cached.deduplicated() }
            isSearching = false
            return
        }
        val personalSeed = topPersonalArtists.take(2).joinToString(" ")
        val langHint = selectedLanguages.take(2).joinToString(" ")
        val themeHint = when (palette.mode) {
            AppThemeMode.WHITE_LIQUID_GLASS -> "Acoustic Feel Good Pop"
            AppThemeMode.LIGHT -> "Upbeat Bright Hits"
            AppThemeMode.DARK_LIQUID_GLASS -> "Chill Midnight Lo-Fi"
            AppThemeMode.DARK -> "Bass Mass BGM"
        }
        val query = when (cat) {
            "Home" -> {
                if (personalSeed.isNotBlank()) "$personalSeed $langHint Top Hits 2026"
                else if ("Tamil" in selectedLanguages && "English" in selectedLanguages) "Top Tamil English Hits 2026 Anirudh"
                else if ("Tamil" in selectedLanguages) "Latest Tamil Hit Songs 2026"
                else "Top Global Hits 2026"
            }
            "Top Hits" -> "Top Tamil Hit Songs 2026 Anirudh"
            "Playlists" -> "Tamil Hit Playlists 2026"
            "🎬 Videos" -> "Tamil HD Video Songs 2026 Anirudh"
            "#tamil dance", "Tamil Dance" -> "Tamil Dance Hit Songs 2026"
            "#tamil pop", "Tamil Pop" -> "Tamil Indie Pop Songs 2026"
            "#feel good", "Feel Good", "🎵 Feel Good" -> "Tamil Feel Good Melody Songs 2026"
            "#trending", "Trending" -> "Tamil Trending Viral Songs 2026"
            "Popular Artists" -> "Anirudh AR Rahman Harris Jayaraj Best Hits"
            "Kollywood" -> "Kollywood Hitlist Anirudh AR Rahman Yuvan"
            "Spotify" -> "Spotify Global Top Hits 2026 Viral"
            "☀️ Crystal Pop", "☀️ Daylight Hits" -> "$langHint Bright Pop Feel Good Songs 2026"
            "🎸 Acoustic Glass", "🎸 Acoustic" -> "$langHint Acoustic Unplugged Melody Hits"
            "🌿 Fresh Morning" -> "$langHint Fresh Morning Chill Melodies"
            "🌙 Midnight Glass" -> "$langHint Late Night Chill Slowed Melodies"
            "🌌 Lo-Fi Chill", "Lo-Fi" -> "$langHint Lo-Fi Chill Slowed Reverb"
            "🔊 Deep Bass" -> "$langHint Deep Bass Boosted EDM Hits"
            "🖤 Night Drive" -> "$langHint Night Drive Synthwave Chill Hits"
            "🔥 Mass BGM", "Tamil OST & BGM" -> "Anirudh Vikram Jailer Leo Mass BGM OST"
            "🎧 Hip-Hop & EDM" -> "Top Hip Hop EDM Party Hits 2026"
            "Tamil Hits" -> "Latest Tamil Hit Songs 2026 Anirudh"
            "English Pop" -> "Top English Pop Hits 2026"
            "Bollywood" -> "Top Bollywood Songs Arijit Singh"
            "EDM" -> "EDM Party Hits 2026"
            else -> "$cat Tamil Songs 2026"
        }
        performSearch(query, userInitiated = false)
    }

    // Initial load & refresh when theme or language changes on Home
    LaunchedEffect(selectedLanguages, palette.mode) {
        if (searchResults.isEmpty() || (activeCategory == "✨ For You" && searchQuery.isBlank())) {
            loadCategoryTracks(activeCategory)
        }
    }

    // Dock tab tap returns to root
    LaunchedEffect(activity.onlineResetTrigger) {
        if (activity.onlineResetTrigger > 0) {
            searchQuery = ""
            selectedPlaylist = null
            activeCategory = "Home"
            loadCategoryTracks("Home")
        }
    }

    BackHandler(enabled = searchQuery.isNotBlank() || showSuggestions || isSearchFocused || selectedPlaylist != null) {
        if (showSuggestions || isSearchFocused) {
            showSuggestions = false
            isSearchFocused = false
            focusManager.clearFocus()
        } else if (selectedPlaylist != null) {
            selectedPlaylist = null
        } else if (searchQuery.isNotBlank()) {
            searchQuery = ""
            loadCategoryTracks(activeCategory)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
        ) {
        // Clean, Full-Width Liquid Glass Search Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isSearchFocused = it.isFocused },
                placeholder = {
                    Text(
                        if (activeCategory == "Playlists") "Search Playlists or paste URL..." else "Search Songs, Artists or paste link...",
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    IconButton(onClick = { performSearch(searchQuery, userInitiated = true) }) {
                        Icon(Icons.Rounded.Search, "Search", tint = palette.accent)
                    }
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            selectedPlaylist = null
                            showSuggestions = false
                            focusManager.clearFocus()
                            loadCategoryTracks(activeCategory)
                        }) {
                            Icon(Icons.Rounded.Close, "Clear search", tint = palette.accent, modifier = Modifier.size(22.dp))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(searchQuery, userInitiated = true) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = if (glassEnabled && palette.isLight) Color.White.copy(alpha = 0.90f) else if (glassEnabled) Color.White.copy(alpha = 0.18f) else palette.cardBorder,
                    focusedContainerColor = if (glassEnabled && palette.isLight) Color.White.copy(alpha = 0.80f) else if (glassEnabled) Color.White.copy(alpha = 0.08f) else palette.surface,
                    unfocusedContainerColor = if (glassEnabled && palette.isLight) Color.White.copy(alpha = 0.65f) else if (glassEnabled) Color.White.copy(alpha = 0.04f) else palette.surface,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
            )
        }



        // Category & Language Chips Row with Liquid Glass Surfaces
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val chipShape = RoundedCornerShape(18.dp)

            // 1. Dedicated [🏠 Home] Chip
            item(key = "home_chip") {
                val isHome = activeCategory == "Home" && searchQuery.isBlank() && selectedPlaylist == null
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (isHome) palette.accent.copy(alpha = if (palette.isLight) 0.20f else 0.32f) else if (palette.isLight) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.08f),
                            blurRadius = 8.dp,
                            refractionHeight = 8.dp,
                            refractionAmount = 14.dp,
                            fallbackColor = if (isHome) palette.accent else palette.surface,
                            isLight = palette.isLight,
                        )
                        .border(
                            width = if (isHome) 1.2.dp else 0.8.dp,
                            brush = if (isHome) Brush.linearGradient(listOf(palette.accent, palette.accentSecondary)) else liquidSpecularBrush(isLight = palette.isLight),
                            shape = chipShape
                        )
                        .clickable {
                            activeCategory = "Home"
                            searchQuery = ""
                            selectedPlaylist = null
                            loadCategoryTracks("Home")
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🏠 ", fontSize = 12.sp)
                        Text(
                            "Home",
                            color = if (isHome) (if (!glassEnabled) palette.onAccent else if (palette.isLight) palette.accent else Color.White) else palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = if (isHome) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 2. Dedicated [🔥 Top Hits] Chip
            item(key = "top_hits_chip") {
                val isTop = activeCategory == "Top Hits" && searchQuery.isBlank() && selectedPlaylist == null
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (isTop) palette.accent.copy(alpha = if (palette.isLight) 0.20f else 0.32f) else if (palette.isLight) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.08f),
                            blurRadius = 8.dp,
                            refractionHeight = 8.dp,
                            refractionAmount = 14.dp,
                            fallbackColor = if (isTop) palette.accent else palette.surface,
                            isLight = palette.isLight,
                        )
                        .border(
                            width = if (isTop) 1.2.dp else 0.8.dp,
                            brush = if (isTop) Brush.linearGradient(listOf(palette.accent, palette.accentSecondary)) else liquidSpecularBrush(isLight = palette.isLight),
                            shape = chipShape
                        )
                        .clickable {
                            activeCategory = "Top Hits"
                            searchQuery = ""
                            selectedPlaylist = null
                            loadCategoryTracks("Top Hits")
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥 ", fontSize = 12.sp)
                        Text(
                            "Top Hits",
                            color = if (isTop) (if (!glassEnabled) palette.onAccent else if (palette.isLight) palette.accent else Color.White) else palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 3. Dedicated [📁 Playlists] Chip
            item(key = "playlists_chip") {
                val isPlaylists = activeCategory == "Playlists" && selectedPlaylist == null
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (isPlaylists) palette.accent.copy(alpha = if (palette.isLight) 0.20f else 0.32f) else if (palette.isLight) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.08f),
                            blurRadius = 8.dp,
                            refractionHeight = 8.dp,
                            refractionAmount = 14.dp,
                            fallbackColor = if (isPlaylists) palette.accent else palette.surface,
                            isLight = palette.isLight,
                        )
                        .border(
                            width = if (isPlaylists) 1.2.dp else 0.8.dp,
                            brush = if (isPlaylists) Brush.linearGradient(listOf(palette.accent, palette.accentSecondary)) else liquidSpecularBrush(isLight = palette.isLight),
                            shape = chipShape
                        )
                        .clickable {
                            if (activeCategory == "Playlists") {
                                activeCategory = "Home"
                                if (searchQuery.isNotBlank()) performSearch(searchQuery) else loadCategoryTracks("Home")
                            } else {
                                activeCategory = "Playlists"
                                selectedPlaylist = null
                                if (searchQuery.isNotBlank() && searchedPlaylists.isEmpty()) {
                                    performSearch(searchQuery)
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📁 ", fontSize = 12.sp)
                        Text(
                            "Playlists",
                            color = if (isPlaylists) (if (!glassEnabled) palette.onAccent else if (palette.isLight) palette.accent else Color.White) else palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = if (isPlaylists) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 4. Theme-Aware & Personalized Category Chips
            items(categories) { cat ->
                val isSelected = activeCategory == cat && searchQuery.isBlank() && selectedPlaylist == null
                val chipSrc = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (isSelected) palette.accent.copy(alpha = if (palette.isLight) 0.20f else 0.32f) else if (palette.isLight) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.08f),
                            blurRadius = 8.dp,
                            refractionHeight = 8.dp,
                            refractionAmount = 14.dp,
                            fallbackColor = if (isSelected) palette.accent else palette.surface,
                            isLight = palette.isLight,
                        )
                        .border(
                            width = if (isSelected) 1.2.dp else 0.8.dp,
                            brush = if (isSelected) Brush.linearGradient(listOf(palette.accent, palette.accentSecondary)) else liquidSpecularBrush(isLight = palette.isLight),
                            shape = chipShape
                        )
                        .clickable(interactionSource = chipSrc, indication = null) {
                            if (cat == "🎬 Videos") {
                                activity.openBrowser("https://m.youtube.com", "YouTube")
                            } else {
                                activeCategory = cat
                                searchQuery = ""
                                selectedPlaylist = null
                                loadCategoryTracks(cat)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        cat,
                        color = if (isSelected) (if (!glassEnabled) palette.onAccent else if (palette.isLight) palette.accent else Color.White) else palette.textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            // 5. Clean Universal [🌐 Languages ▾] Chip (At the end)
            item(key = "lang_filter") {
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = palette.accent.copy(alpha = 0.16f),
                            blurRadius = 8.dp,
                            fallbackColor = palette.surface,
                        )
                        .border(1.dp, palette.accent.copy(alpha = 0.5f), chipShape)
                        .clickable { showLanguageModal = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌐 ", fontSize = 12.sp)
                        Text(
                            "Languages ▾",
                            color = palette.accent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // MICA Jam — Listen Together Chip
            item(key = "ms_jam_chip") {
                val jamState by JamSyncController.sessionState.collectAsState()
                val isJamActive = jamState.role != JamRole.NONE && jamState.isConnected
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (isJamActive) Color(0xFF10B981).copy(alpha = 0.2f) else palette.accent.copy(alpha = 0.16f),
                            blurRadius = 8.dp,
                            fallbackColor = palette.surface,
                        )
                        .border(1.dp, if (isJamActive) Color(0xFF10B981) else palette.accent.copy(alpha = 0.5f), chipShape)
                        .clickable { showJamDialog = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SpeakerGroup,
                            contentDescription = null,
                            tint = if (isJamActive) Color(0xFF10B981) else palette.accent,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isJamActive) "Jam: ${jamState.roomCode}" else "MICA JAM",
                            color = if (isJamActive) Color(0xFF10B981) else palette.accent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 6. Google Account Status Chip (At the end)
            item(key = "google_auth") {
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (googleSignedIn) palette.accent.copy(alpha = 0.20f) else if (palette.isLight) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.08f),
                            blurRadius = 8.dp,
                            fallbackColor = palette.surface,
                        )
                        .border(
                            1.dp,
                            if (googleSignedIn) palette.accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f),
                            chipShape
                        )
                        .clickable {
                            if (googleSignedIn) {
                                showGoogleSignOutConfirm = true
                            } else {
                                showGoogleAuthModal = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (googleSignedIn) "👤 Google: Active" else "👤 Google Sign In",
                            color = if (googleSignedIn) palette.accent else palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 7. [⬇️ Downloads] Chip (At the end)
            item(key = "downloads_chip") {
                Box(
                    modifier = Modifier
                        .liquidGlassSurface(
                            backdrop = null,
                            enabled = glassEnabled,
                            shape = chipShape,
                            tint = if (palette.isLight) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.08f),
                            blurRadius = 8.dp,
                            fallbackColor = palette.surface,
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.15f), chipShape)
                        .clickable { onNavigateToTab("Library") }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⬇️ ", fontSize = 12.sp)
                        Text(
                            "Downloads",
                            color = palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Loading or Results
        if (isSearching && searchResults.isEmpty() && searchedPlaylists.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Connecting to Online Music...",
                        color = palette.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedPlaylist != null) {
                    // Dedicated Playlist Detail View
                    val playlist = selectedPlaylist!!
                    item(key = "playlist_detail_header") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { selectedPlaylist = null }) {
                                    Icon(Icons.Rounded.ArrowBack, "Back", tint = palette.textPrimary)
                                }
                                Spacer(Modifier.width(4.dp))
                                Text("Featured Playlist", color = palette.textSecondary, fontSize = 13.sp)
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bmp = rememberRemoteBitmap(playlist.coverUrl)
                                val coverShape = RoundedCornerShape(16.dp)
                                Box(
                                    Modifier
                                        .size(108.dp)
                                        .clip(coverShape)
                                        .background(Color(0xFF1E293B))
                                        .border(1.2.dp, liquidSpecularBrush(), coverShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Rounded.QueueMusic, null, tint = palette.accent, modifier = Modifier.size(44.dp))
                                    }
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.title,
                                        color = palette.textPrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        playlist.subtitle,
                                        color = palette.textSecondary,
                                        fontSize = 12.5.sp,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${if (playlistTracks.isNotEmpty()) playlistTracks.size else playlist.songCount.coerceAtLeast(1)} Tracks • 320kbps High Fidelity",
                                        color = palette.accent,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Button(
                                onClick = { playAllPlaylist(playlistTracks) },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = palette.onAccent, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play All (${playlistTracks.size} Tracks)", color = palette.onAccent, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isLoadingPlaylistTracks) {
                        item(key = "playlist_loading") {
                            Box(
                                Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(32.dp))
                            }
                        }
                    } else {
                        items(playlistTracks, key = { it.videoId }) { track ->
                            val dlProgress = activeDownloads[track.videoId]
                            val isPlaying = playerState.playing && playerState.item?.title == track.title
                            OnlineTrackRow(
                                track = track,
                                palette = palette,
                                glassEnabled = glassEnabled,
                                isLoading = loadingTrackId == track.videoId,
                                isDownloading = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed,
                                isDlComplete = dlProgress?.isComplete == true || AudioDownloadManager.isDownloaded(track.title, track.artist),
                                dlProgress = dlProgress?.progress ?: 0f,
                                isPlaying = isPlaying,
                                onPlay = { playTrack(track, playlistTracks) },
                                onDownload = {
                                    AudioDownloadManager.downloadSong(context, scope, track.videoId, track.title, track.artist, track.thumbUrl)
                                }
                            )
                        }
                    }
                } else if (activeCategory == "Playlists") {
                    val playlistsToShow = if (searchQuery.isNotBlank() && searchedPlaylists.isNotEmpty()) searchedPlaylists else YtMusicClient.getCuratedPlaylists()
                    item(key = "curated_playlists_hdr") {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                            Text("Curated Collections & Playlists", color = palette.textSecondary, fontSize = 12.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (searchQuery.isNotBlank()) "Playlist Results for \"$searchQuery\"" else "Featured Playlists",
                                color = palette.textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(playlistsToShow, key = { it.title + it.listId }) { playlist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (glassEnabled && palette.isLight) Color.White.copy(alpha = 0.72f) else if (glassEnabled) Color.White.copy(alpha = 0.05f) else palette.surface)
                                .border(0.8.dp, if (glassEnabled && palette.isLight) Color.White.copy(alpha = 0.92f) else if (glassEnabled) Color.White.copy(alpha = 0.10f) else palette.cardBorder, RoundedCornerShape(16.dp))
                                .clickable {
                                    openPlaylist(playlist)
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bmp = rememberRemoteBitmap(playlist.coverUrl)
                            Box(
                                Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (palette.isLight) Color(0xFFE0F2FE) else Color(0xFF222B38)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(Icons.Rounded.QueueMusic, null, tint = palette.accent, modifier = Modifier.size(28.dp))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(playlist.subtitle, color = palette.textSecondary, fontSize = 12.sp)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                } else if (activeCategory == "Top Hits" && searchQuery.isBlank() && searchResults.isNotEmpty()) {
                    // Dedicated Top 50 Chartbusters Leaderboard (#1 - #50)
                    item(key = "top_hits_hdr") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("🔥 Top 50 Chartbusters", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Global trending hits & live ranks", color = palette.textSecondary, fontSize = 12.sp)
                            }
                            IconButton(onClick = { loadCategoryTracks("Top Hits") }) {
                                Icon(Icons.Rounded.Refresh, "Refresh charts", tint = palette.accent)
                            }
                        }
                    }

                    itemsIndexed(searchResults, key = { idx, trk -> "top_chart_${idx}_${trk.videoId}" }) { index, track ->
                        val rank = index + 1
                        val dlProgress = activeDownloads[track.videoId]
                        val isPlaying = playerState.playing && playerState.item?.title == track.title
                        val rankColor = when (rank) {
                            1 -> Color(0xFFFFD700) // Gold
                            2 -> Color(0xFFC0C0C0) // Silver
                            3 -> Color(0xFFCD7F32) // Bronze
                            else -> palette.accent.copy(alpha = 0.75f)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Rank Badge Number
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .padding(end = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "#$rank",
                                    color = rankColor,
                                    fontSize = if (rank in 1..9) 15.sp else 13.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                OnlineTrackRow(
                                    track = track,
                                    palette = palette,
                                    glassEnabled = glassEnabled,
                                    isLoading = loadingTrackId == track.videoId,
                                    isDownloading = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed,
                                    isDlComplete = dlProgress?.isComplete == true || AudioDownloadManager.isDownloaded(track.title, track.artist),
                                    dlProgress = dlProgress?.progress ?: 0f,
                                    isPlaying = isPlaying,
                                    onPlay = { playTrack(track, searchResults) },
                                    onDownload = {
                                        AudioDownloadManager.downloadSong(context, scope, track.videoId, track.title, track.artist, track.thumbUrl)
                                    }
                                )
                            }
                        }
                    }
                } else if (activeCategory == "Home" && searchQuery.isBlank() && searchResults.isNotEmpty()) {
                    // Section 1: "Quick Picks"
                    item(key = "home_start_listening_hdr") {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                            Text("Quick Picks", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    val topPicks = searchResults.take(8)
                    items(topPicks, key = { "pick_${it.videoId}" }) { track ->
                        val dlProgress = activeDownloads[track.videoId]
                        val isPlaying = playerState.playing && playerState.item?.title == track.title
                        OnlineTrackRow(
                            track = track,
                            palette = palette,
                            glassEnabled = glassEnabled,
                            isLoading = loadingTrackId == track.videoId,
                            isDownloading = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed,
                            isDlComplete = dlProgress?.isComplete == true || AudioDownloadManager.isDownloaded(track.title, track.artist),
                            dlProgress = dlProgress?.progress ?: 0f,
                            isPlaying = isPlaying,
                            onPlay = { playTrack(track, searchResults) },
                            onDownload = {
                                AudioDownloadManager.downloadSong(context, scope, track.videoId, track.title, track.artist, track.thumbUrl)
                            }
                        )
                    }

                    // Section 2: "Popular Artists" (Real portrait headshots)
                    item(key = "home_popular_artists_hdr") {
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                            Text("Popular Artists", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    item(key = "home_popular_artists_row") {
                        LazyRow(
                            Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(YtMusicClient.getPopularArtists()) { artist ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .clickable {
                                            userSelectedSuggestion = true
                                            showSuggestions = false
                                            isSearchFocused = false
                                            searchQuery = artist.searchQuery
                                            performSearch(artist.searchQuery)
                                        }
                                ) {
                                    val bmp = rememberRemoteBitmap(artist.avatarUrl)
                                    Box(
                                        Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF222B38))
                                            .border(1.5.dp, palette.accent.copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Icon(Icons.Rounded.AccountCircle, null, tint = palette.accent, modifier = Modifier.size(36.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(artist.name, color = palette.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(artist.subtitle, color = palette.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    // Section 3: "Featured Playlists Carousel"
                    item(key = "home_playlists_carousel_hdr") {
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                            Text("Featured Playlists & Collections", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    item(key = "home_playlists_carousel_row") {
                        LazyRow(
                            Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(YtMusicClient.getCuratedPlaylists()) { playlist ->
                                Column(
                                    modifier = Modifier
                                        .width(136.dp)
                                        .clickable {
                                            openPlaylist(playlist)
                                        }
                                ) {
                                    val bmp = rememberRemoteBitmap(playlist.coverUrl)
                                    val plShape = RoundedCornerShape(16.dp)
                                    Box(
                                        Modifier
                                            .size(136.dp)
                                            .clip(plShape)
                                            .background(Color(0xFF222B38))
                                            .border(1.dp, liquidSpecularBrush(), plShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Icon(Icons.Rounded.QueueMusic, null, tint = palette.accent, modifier = Modifier.size(36.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(playlist.title, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(playlist.subtitle, color = palette.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    // Section 4: "Trending Hits" (Remaining 20+ songs)
                    item(key = "home_trending_hdr") {
                        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)) {
                            Text("Trending & Recommended Hits", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val remaining = searchResults.drop(8)
                    items(remaining, key = { it.videoId }) { track ->
                        val dlProgress = activeDownloads[track.videoId]
                        val isPlaying = playerState.playing && playerState.item?.title == track.title
                        OnlineTrackRow(
                            track = track,
                            palette = palette,
                            glassEnabled = glassEnabled,
                            isLoading = loadingTrackId == track.videoId,
                            isDownloading = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed,
                            isDlComplete = dlProgress?.isComplete == true || AudioDownloadManager.isDownloaded(track.title, track.artist),
                            dlProgress = dlProgress?.progress ?: 0f,
                            isPlaying = isPlaying,
                            onPlay = { playTrack(track, searchResults) },
                            onDownload = {
                                AudioDownloadManager.downloadSong(context, scope, track.videoId, track.title, track.artist, track.thumbUrl)
                            }
                        )
                    }

                    // Section 5: "Popular albums and singles"
                    item(key = "home_popular_albums_hdr") {
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                            Text("Popular albums and singles", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    item(key = "home_popular_albums_row") {
                        LazyRow(
                            Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(YtMusicClient.getPopularAlbums()) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clickable {
                                            userSelectedSuggestion = true
                                            showSuggestions = false
                                            isSearchFocused = false
                                            searchQuery = album.searchQuery
                                            performSearch(album.searchQuery)
                                        }
                                ) {
                                    val bmp = rememberRemoteBitmap(album.coverUrl)
                                    Box(
                                        Modifier
                                            .size(130.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color(0xFF222B38))
                                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Icon(Icons.Rounded.Album, null, tint = palette.accent, modifier = Modifier.size(40.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(album.title, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(album.artist, color = palette.textSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
                    // Category / Search Results List
                    if (activeCategory == "✨ For You" && searchQuery.isBlank()) {
                        item(key = "for_you_hdr") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("For You", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text("Fresh mix based on your listening", color = palette.textSecondary, fontSize = 12.sp)
                                }
                                IconButton(onClick = { loadCategoryTracks("✨ For You") }) {
                                    Icon(Icons.Rounded.Refresh, "Refresh", tint = palette.accent, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    if (searchQuery.isNotBlank()) {
                        item(key = "search_active_hdr") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Results for \"$searchQuery\"",
                                    color = palette.textPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                                )
                                AssistChip(
                                    onClick = {
                                        searchQuery = ""
                                        selectedPlaylist = null
                                        loadCategoryTracks(activeCategory)
                                    },
                                    label = {
                                        Text(
                                            "Clear ✕",
                                            color = palette.accent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = palette.accent.copy(alpha = 0.12f)),
                                    border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.35f)),
                                    modifier = Modifier.wrapContentWidth()
                                )
                            }
                        }
                    }
                    items(searchResults, key = { it.videoId }) { track ->
                        val dlProgress = activeDownloads[track.videoId]
                        val isPlaying = playerState.playing && playerState.item?.title == track.title
                        OnlineTrackRow(
                            track = track,
                            palette = palette,
                            glassEnabled = glassEnabled,
                            isLoading = loadingTrackId == track.videoId,
                            isDownloading = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed,
                            isDlComplete = dlProgress?.isComplete == true || AudioDownloadManager.isDownloaded(track.title, track.artist),
                            dlProgress = dlProgress?.progress ?: 0f,
                            isPlaying = isPlaying,
                            onPlay = { playTrack(track, searchResults) },
                            onDownload = {
                                AudioDownloadManager.downloadSong(context, scope, track.videoId, track.title, track.artist, track.thumbUrl)
                            }
                        )
                    }
                }
            }
        }
    }

    // Floating Random Pick Dice FAB (Matching Screenshot_20261121_105558.jpg)
    // Positioned in the bottom-right corner, directly above mini-player
    val miniPlayerActive = playerState.item != null
    val diceOffsetBottom = bottomPadding + (if (miniPlayerActive) 70.dp else 10.dp)

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = diceOffsetBottom)
    ) {
        val diceBg = if (palette.isLight) {
            Brush.linearGradient(listOf(Color(0xF0FFFFFF), Color(0xD9E2E8F0)))
        } else {
            Brush.linearGradient(listOf(Color(0xD91E293B), Color(0xB30F172A)))
        }
        val diceBorder = if (palette.isLight) Color(0x603B82F6) else Color(0x6060A5FA)
        val diceIconTint = if (palette.isLight) Color(0xFF1D4ED8) else Color(0xFF93C5FD)

        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(diceBg)
                .border(1.5.dp, diceBorder, CircleShape)
                .clickable {
                    val hits = FeedCacheManager.cachedTopHits.value.ifEmpty { FeedCacheManager.defaultCuratedHits() }
                    if (hits.isNotEmpty()) {
                        val randomPick = hits.random()
                        playTrack(randomPick, hits)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dice_random),
                contentDescription = "Random Pick / Surprise Me",
                tint = diceIconTint,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

    // Language Selector Modal BottomSheet
    if (showLanguageModal) {
        ModalBottomSheet(
            onDismissRequest = { showLanguageModal = false },
            containerColor = if (palette.isLight) Color(0xF8F8FBFF) else if (glassEnabled) Color(0xF0121A28) else palette.surface,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    "Preferred Music Languages",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    "Select the languages you want to appear in your trending feed.",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                availableLanguages.forEach { lang ->
                    val isChecked = lang in selectedLanguages
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val updated = if (isChecked) {
                                    if (selectedLanguages.size > 1) selectedLanguages - lang else selectedLanguages
                                } else {
                                    selectedLanguages + lang
                                }
                                selectedLanguages = updated
                                Store.prefs(context).edit().putStringSet("online_languages_v1", updated).apply()
                                loadCategoryTracks(activeCategory)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            lang,
                            fontSize = 15.sp,
                            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = palette.accent,
                                checkmarkColor = palette.onAccent
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        showLanguageModal = false
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, color = palette.onAccent)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showJamDialog) {
        JamSyncDialog(
            service = service,
            onDismiss = { showJamDialog = false }
        )
    }

    if (showGoogleAuthModal) {
        GoogleAuthDialog(
            onDismiss = { showGoogleAuthModal = false },
            onAuthSuccess = {
                googleSignedIn = true
                showGoogleAuthModal = false
                loadCategoryTracks(activeCategory)
            }
        )
    }

    if (showGoogleSignOutConfirm) {
        LiquidGlassAlertDialog(
            onDismissRequest = { showGoogleSignOutConfirm = false },
            title = { Text("Google Account", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You are currently signed in with Google. Signing out will switch YouTube Music back to high-speed guest mode.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    YtMusicClient.setGoogleAuthCookies(context, null)
                    googleSignedIn = false
                    showGoogleSignOutConfirm = false
                    Toast.makeText(context, "Signed out of Google", Toast.LENGTH_SHORT).show()
                    loadCategoryTracks(activeCategory)
                }) {
                    Text("Sign Out", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoogleSignOutConfirm = false }) {
                    Text("Cancel", color = palette.textPrimary)
                }
            },
        )
    }
}

@Composable
private fun OnlineTrackRow(
    track: YtTrack,
    palette: AppPalette,
    glassEnabled: Boolean,
    isLoading: Boolean,
    isDownloading: Boolean = false,
    isDlComplete: Boolean = false,
    dlProgress: Float = 0f,
    isPlaying: Boolean = false,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
) {
    val rowBg = when {
        glassEnabled && palette.isLight -> Color.White.copy(alpha = 0.72f)
        glassEnabled -> Color.White.copy(alpha = 0.05f)
        else -> palette.surface
    }
    val rowBorder = when {
        glassEnabled && palette.isLight -> Color.White.copy(alpha = 0.92f)
        glassEnabled -> Color.White.copy(alpha = 0.10f)
        else -> palette.cardBorder
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowBg)
            .border(0.8.dp, rowBorder, RoundedCornerShape(16.dp))
            .clickable { onPlay() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = rememberRemoteBitmap(track.thumbUrl)
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (palette.isLight) Color(0xFFE0F2FE) else Color(0xFF222B38)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = palette.accent, modifier = Modifier.size(24.dp))
            }
            if (isLoading) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isPlaying) palette.accent else palette.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.artist,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.durationText.isNotBlank()) {
                    Text(" • ${track.durationText}", color = palette.textSecondary.copy(alpha = 0.7f), fontSize = 11.5.sp)
                }
            }
        }

        // Circular Liquid Glass Play Action Button
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isPlaying -> palette.accent
                        palette.isLight -> Color.White.copy(alpha = 0.90f)
                        else -> Color.White.copy(alpha = 0.08f)
                    }
                )
                .border(
                    width = 0.9.dp,
                    color = if (isPlaying) palette.accent else if (palette.isLight) palette.accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.18f),
                    shape = CircleShape
                )
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = palette.accent,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else if (isPlaying) {
                PlayingAudioWaveBars(
                    modifier = Modifier.size(18.dp),
                    barCount = 4,
                    color = palette.onAccent,
                    isAnimating = true
                )
            } else {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play song",
                    tint = if (palette.isLight) palette.accent else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun PlayingAudioWaveBars(
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    color: Color = Color.White,
    isAnimating: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveBars")
    val animValues = List(barCount) { index ->
        val duration = remember(index) { 550 + (index * 130) }
        val minH = 0.25f
        val maxH = when (index % 4) {
            0 -> 0.95f
            1 -> 0.60f
            2 -> 1.00f
            else -> 0.75f
        }
        if (isAnimating) {
            infiniteTransition.animateFloat(
                initialValue = minH,
                targetValue = maxH,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )
        } else {
            remember { mutableFloatStateOf(minH) }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        animValues.forEach { anim ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(anim.value)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
