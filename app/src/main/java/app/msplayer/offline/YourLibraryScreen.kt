package app.msplayer.offline

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun YourLibraryScreen(
    activity: MainActivity,
    service: PlaybackService?,
    palette: AppPalette,
    glassEnabled: Boolean,
    bottomPadding: Dp,
    onOpenPlaylist: (String) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onNavigateToOnline: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeFilter by rememberSaveable { mutableStateOf("All") }
    var selectedSection by rememberSaveable { mutableStateOf<String?>(null) } // "Liked", "Downloaded", "Cached", "Top50"
    val filters = listOf("All", "Playlists", "Songs", "Downloaded", "Albums", "Artists")

    // Tapping Library on the bottom dock resets nested sub-screens back to the main Library page
    LaunchedEffect(activity.libraryResetTrigger) {
        if (activity.libraryResetTrigger > 0) {
            selectedSection = null
            activeFilter = "All"
        }
    }

    var playlists by remember { mutableStateOf<List<String>>(emptyList()) }
    var likedTracks by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var downloadedTracks by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var top50Tracks by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var localAudios by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var showImportSpotifyDialog by remember { mutableStateOf(false) }
    var importSpotifyUrl by remember { mutableStateOf("") }
    var isImportingSpotify by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showJamDialog by remember { mutableStateOf(false) }

    // Load library content
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = Store.db(activity).dao()
            val p = dao.playlists().filter { it != "Favorites" }
            val favEntries = dao.entries("Favorites").filter { it.title.isNotBlank() && it.title != "Opened files" }
            val allMedia = activity.videos + activity.audios
            val localOnlyAudios = activity.audios.filter { song ->
                !song.folder.contains("MSPlayer", ignoreCase = true) &&
                !song.folder.contains("Mica", ignoreCase = true) &&
                !song.uri.contains("MSPlayer", ignoreCase = true) &&
                !song.uri.contains("Mica", ignoreCase = true) &&
                !song.uri.startsWith(AudioDownloadManager.getDownloadDir().absolutePath)
            }
            fun enrich(base: MediaItem): MediaItem {
                val meta = TrackArtworkStore.findMeta(activity, base.uri, base.title) ?: return base
                val bestThumb = meta.localCoverPath.takeIf { it.isNotBlank() && File(it).exists() }?.let { "file://$it" }
                    ?: base.thumbUri?.takeIf { it.isNotBlank() }
                    ?: meta.thumbUrl.takeIf { it.isNotBlank() }
                val bestArtist = base.artist.takeIf { it.isNotBlank() && !it.equals("MS Player", true) }
                    ?: meta.artist.takeIf { it.isNotBlank() }
                    ?: base.artist
                return base.copy(artist = bestArtist, thumbUri = bestThumb)
            }

            val favItems = favEntries.mapNotNull { entry ->
                val base = allMedia.find { it.uri == entry.uri } ?: MediaItem(entry.uri, entry.title, audio = true)
                enrich(base)
            }

            // Downloaded songs in /storage/emulated/0/Music/MSPlayer/
            val dlDir = AudioDownloadManager.getDownloadDir()
            val dlFiles = dlDir.listFiles { file -> file.isFile && (file.name.endsWith(".m4a") || file.name.endsWith(".mp3")) } ?: emptyArray()
            val dlItems = dlFiles.map { file ->
                val base = MediaItem(
                    uri = Uri.fromFile(file).toString(),
                    title = file.nameWithoutExtension.substringBefore(" - "),
                    artist = file.nameWithoutExtension.substringAfter(" - ", "MS Player"),
                    folder = "MSPlayer",
                    size = file.length(),
                    audio = true,
                )
                enrich(base)
            }

            // Top 50 from history
            val hist = Store.db(activity).dao().history()
            val topItems = hist.take(50).mapNotNull { h ->
                val base = allMedia.find { it.uri == h.uri } ?: MediaItem(h.uri, h.title, audio = true)
                enrich(base)
            }

            withContext(Dispatchers.Main) {
                playlists = p
                likedTracks = favItems
                downloadedTracks = dlItems
                top50Tracks = topItems
                localAudios = localOnlyAudios
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
    ) {
        // Filter Chips Row (Matching Screenshot_20261117_061007.jpg)
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters) { filter ->
                val isSelected = (selectedSection == null && activeFilter == filter) || (selectedSection == filter)
                val chipSrc = remember { MutableInteractionSource() }
                val chipBg = when {
                    isSelected -> palette.accent
                    glassEnabled && palette.isLight -> Color.White.copy(alpha = 0.72f)
                    glassEnabled -> Color.White.copy(alpha = 0.08f)
                    else -> palette.surface
                }
                val chipBorder = when {
                    isSelected -> palette.accent
                    glassEnabled && palette.isLight -> Color.White.copy(alpha = 0.92f)
                    glassEnabled -> Color.White.copy(alpha = 0.16f)
                    else -> palette.cardBorder
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(chipBg)
                        .border(0.8.dp, chipBorder, RoundedCornerShape(16.dp))
                        .clickable(interactionSource = chipSrc, indication = ripple()) {
                            activeFilter = filter
                            selectedSection = if (filter in listOf("Downloaded", "Playlists", "Songs")) filter else null
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        filter,
                        color = if (isSelected) palette.onAccent else palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 4 Quick Navigation Tiles (Matching Screenshot_20261117_055403.jpg and 061007.jpg)
            if (activeFilter == "All" && selectedSection == null) {
                item(key = "quick_tiles") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 1. Liked Songs (Purple gradient card)
                            LibraryTile(
                                title = "Liked Songs",
                                subtitle = "${likedTracks.size} tracks",
                                icon = Icons.Rounded.Favorite,
                                gradient = Brush.linearGradient(listOf(Color(0xFF5B21B6), Color(0xFF7C3AED))),
                                modifier = Modifier.weight(1f),
                                onClick = { selectedSection = "Liked" }
                            )

                            // 2. Downloaded (Dark glass card with download arrow)
                            LibraryTile(
                                title = "Downloaded",
                                subtitle = "${downloadedTracks.size} tracks",
                                icon = Icons.Rounded.DownloadDone,
                                gradient = Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF334155))),
                                modifier = Modifier.weight(1f),
                                onClick = { selectedSection = "Downloaded" }
                            )
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 3. Trending Hits (Vibrant Orange-Coral Flame Card)
                            LibraryTile(
                                title = "Trending Hits",
                                subtitle = "Viral charts 🔥",
                                icon = Icons.Rounded.Whatshot,
                                gradient = Brush.linearGradient(listOf(Color(0xFFEA580C), Color(0xFFF97316))),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onNavigateToOnline()
                                }
                            )

                            // 4. My Top 50 (Trending arrow card)
                            LibraryTile(
                                title = "My Top 50",
                                subtitle = "Most played",
                                icon = Icons.Rounded.TrendingUp,
                                gradient = Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF14B8A6))),
                                modifier = Modifier.weight(1f),
                                onClick = { selectedSection = "Top50" }
                            )
                        }
                    }
                }
            }

            // Display Tracks depending on Selected Section or Filter
            when (selectedSection) {
                "Songs", "LocalAudio" -> {
                    item { SectionHeader("All Device Songs (${localAudios.size})", palette) { selectedSection = null } }
                    if (localAudios.isEmpty()) {
                        item { EmptyNotice("No local audio files found on device.", palette) }
                    } else {
                        items(localAudios, key = { it.uri }) { song ->
                            MediaRow(song, palette, glassEnabled) { onOpenMedia(song) }
                        }
                    }
                }
                "Liked" -> {
                    item { SectionHeader("Liked Songs (${likedTracks.size})", palette) { selectedSection = null } }
                    if (likedTracks.isEmpty()) {
                        item { EmptyNotice("No liked songs yet. Tap ♡ on any song to add it here.", palette) }
                    } else {
                        items(likedTracks, key = { it.uri }) { song ->
                            MediaRow(song, palette, glassEnabled) { onOpenMedia(song) }
                        }
                    }
                }
                "Downloaded" -> {
                    item { SectionHeader("Downloaded Offline Media (${downloadedTracks.size})", palette) { selectedSection = null } }
                    if (downloadedTracks.isEmpty()) {
                        item { EmptyNotice("No downloaded tracks found in Music/MSPlayer.", palette) }
                    } else {
                        items(downloadedTracks, key = { it.uri }) { song ->
                            MediaRow(song, palette, glassEnabled) { onOpenMedia(song) }
                        }
                    }
                }
                "Top50" -> {
                    item { SectionHeader("My Top 50 Songs", palette) { selectedSection = null } }
                    if (top50Tracks.isEmpty()) {
                        item { EmptyNotice("No listening history yet.", palette) }
                    } else {
                        items(top50Tracks, key = { it.uri }) { song ->
                            MediaRow(song, palette, glassEnabled) { onOpenMedia(song) }
                        }
                    }
                }
                "Cached" -> {
                    item { SectionHeader("Cached Audio Streams", palette) { selectedSection = null } }
                    item { EmptyNotice("Streams are automatically cached for instant playback.", palette) }
                }
                else -> {
                    // All / Playlists View (Matching Spotify Screenshot_20261117_061007.jpg)
                    if (activeFilter in listOf("All", "Playlists")) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Your Playlists",
                                    color = palette.textPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(palette.accent.copy(alpha = 0.15f))
                                        .clickable {
                                            importSpotifyUrl = ""
                                            importError = null
                                            showImportSpotifyDialog = true
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Add, null, tint = palette.accent, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Import Spotify", color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        item(key = "quick_library_tiles") {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Emerald Mica Jam Tile
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF10B981))))
                                        .clickable { showJamDialog = true }
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Icon(Icons.Rounded.SpeakerGroup, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("Mica Jam", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                                        Text("Listen together worldwide", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                // Deep Cobalt Local Audio Tile
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF2563EB))))
                                        .clickable { selectedSection = "LocalAudio" }
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Icon(Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("Local Audio", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                                        Text("${localAudios.size} device songs", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }

                        // Liked Songs row pinned to top of playlists
                        item(key = "pinned_liked_songs") {
                            PlaylistRow(
                                title = "Liked Songs",
                                subtitle = "Pinned • ${likedTracks.size} songs",
                                icon = Icons.Rounded.Favorite,
                                isLikedPin = true,
                                palette = palette,
                                glassEnabled = glassEnabled,
                                onClick = { selectedSection = "Liked" }
                            )
                        }

                        items(playlists, key = { it }) { name ->
                            PlaylistRow(
                                title = name,
                                subtitle = "Playlist",
                                icon = Icons.Rounded.QueueMusic,
                                isLikedPin = false,
                                palette = palette,
                                glassEnabled = glassEnabled,
                                onClick = { onOpenPlaylist(name) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportSpotifyDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImportingSpotify) showImportSpotifyDialog = false },
            title = { Text("Import Spotify", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Paste a Spotify playlist or album link to import it into your library.",
                        color = palette.textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importSpotifyUrl,
                        onValueChange = { importSpotifyUrl = it; importError = null },
                        placeholder = { Text("https://open.spotify.com/playlist/...", fontSize = 12.5.sp, color = palette.textSecondary.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.textSecondary.copy(alpha = 0.3f),
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (importError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(importError ?: "", color = Color(0xFFE53935), fontSize = 12.sp)
                    }
                    if (isImportingSpotify) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = palette.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching playlist tracks...", color = palette.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = importSpotifyUrl.trim()
                        if (url.isBlank()) {
                            importError = "Please enter a valid Spotify link"
                            return@TextButton
                        }
                        isImportingSpotify = true
                        importError = null
                        scope.launch(Dispatchers.IO) {
                            val result = SpotifyClient.importUrl(url)
                            if (result == null || result.tracks.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    isImportingSpotify = false
                                    importError = "Could not load Spotify playlist. Check the link."
                                }
                                return@launch
                            }
                            val dao = Store.db(context).dao()
                            val now = System.currentTimeMillis()
                            if (result.thumbUrl.isNotBlank()) {
                                Store.prefs(context).edit().putString("playlist_cover_${result.title}", result.thumbUrl).apply()
                                TrackArtworkStore.downloadAndCacheCover(context, "playlist://${result.title}", result.title, "Spotify Playlist", result.thumbUrl)
                            }
                            result.tracks.forEach { tr ->
                                val trId = tr.trackId
                                val lazyUri = if (trId.isNotBlank()) "resolve://spotify/${trId}::${tr.title}::${tr.artist}" else "resolve://spotify/${tr.title}::${tr.artist}"
                                val specificThumb = if (trId.isNotBlank()) "spotify_track://$trId" else tr.thumbUrl
                                dao.add(PlaylistEntry().apply {
                                    playlist = result.title
                                    uri = lazyUri
                                    this.title = "${tr.title} • ${tr.artist}"
                                    added = now
                                })
                                if (specificThumb.isNotBlank()) {
                                    TrackArtworkStore.saveMeta(context, lazyUri, tr.title, tr.artist, specificThumb)
                                }
                            }
                            // Asynchronously prefetch authentic oEmbed track artwork in the background
                            scope.launch(Dispatchers.IO) {
                                result.tracks.forEach { tr ->
                                    if (tr.trackId.isNotBlank()) {
                                        val resolvedUrl = SpotifyClient.resolveTrackThumbnail(tr.trackId)
                                        if (!resolvedUrl.isNullOrBlank()) {
                                            val uriStr = "resolve://spotify/${tr.trackId}::${tr.title}::${tr.artist}"
                                            TrackArtworkStore.saveMeta(context, uriStr, tr.title, tr.artist, resolvedUrl)
                                            TrackArtworkStore.downloadAndCacheCover(context, uriStr, tr.title, tr.artist, resolvedUrl, forceOverwrite = true)
                                        }
                                    }
                                }
                                Library.bumpThumbnailRevision()
                            }
                            val updatedPlaylists = dao.playlists().filter { it != "Favorites" }
                            withContext(Dispatchers.Main) {
                                playlists = updatedPlaylists
                                isImportingSpotify = false
                                showImportSpotifyDialog = false
                                android.widget.Toast.makeText(context, "Imported ${result.tracks.size} tracks into \"${result.title}\"", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isImportingSpotify
                ) {
                    Text("Import", color = palette.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isImportingSpotify) {
                    TextButton(onClick = { showImportSpotifyDialog = false }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                }
            },
            containerColor = palette.surface
        )
    }

    if (showJamDialog) {
        JamSyncDialog(service = service, onDismiss = { showJamDialog = false })
    }
}

@Composable
fun LibraryTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: Brush,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
            .clickable { onClick() }
            .padding(14.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Icon(
            icon,
            null,
            tint = Color.White.copy(alpha = 0.28f),
            modifier = Modifier.size(54.dp).align(Alignment.TopEnd)
        )
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
    }
}

@Composable
fun SectionHeader(title: String, palette: AppPalette, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.ArrowBack, "Back", tint = palette.textPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PlaylistRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isLikedPin: Boolean,
    palette: AppPalette,
    glassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var coverBitmap by remember(title, isLikedPin) {
        mutableStateOf(if (isLikedPin) null else OnlineImageCache.get("playlist_art::$title".lowercase()))
    }
    LaunchedEffect(title, isLikedPin) {
        if (!isLikedPin && coverBitmap == null) {
            val savedCover = Store.prefs(context).getString("playlist_cover_$title", null)
            val bmp = TrackArtworkStore.resolveAndCacheCover(
                context = context,
                uri = "playlist://$title",
                title = title,
                artist = "Playlist",
                existingThumbUri = savedCover
            )
            if (bmp != null) {
                OnlineImageCache.put("playlist_art::$title".lowercase(), bmp)
                coverBitmap = bmp
            }
        }
    }
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
            .clip(RoundedCornerShape(14.dp))
            .background(rowBg)
            .border(0.8.dp, rowBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isLikedPin) Brush.linearGradient(listOf(Color(0xFF5B21B6), Color(0xFF7C3AED)))
                    else Brush.linearGradient(listOf(Color(0xFF0284C7), Color(0xFF38BDF8)))
                ),
            contentAlignment = Alignment.Center
        ) {
            val bmp = coverBitmap
            if (!isLikedPin && bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = palette.textSecondary, fontSize = 12.5.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = palette.textSecondary.copy(alpha = 0.6f))
    }
}

@Composable
fun MediaRow(
    song: MediaItem,
    palette: AppPalette,
    glassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var thumbBitmap by remember(song.uri, song.thumbUri) {
        mutableStateOf(OnlineImageCache.get("libart::${song.uri}::${song.title}".lowercase()))
    }
    LaunchedEffect(song.uri, song.thumbUri) {
        if (thumbBitmap == null) {
            thumbBitmap = TrackArtworkStore.resolveAndCacheCover(
                context = context,
                uri = song.uri,
                title = song.title,
                artist = song.artist,
                existingThumbUri = song.thumbUri,
            )
        }
    }
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
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .border(0.8.dp, rowBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (palette.isLight) Color(0xFFE0F2FE) else Color(0xFF222B38)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = palette.accent, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(song.artist.ifBlank { song.folder }, color = palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = palette.accent, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun EmptyNotice(text: String, palette: AppPalette) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = palette.textSecondary, fontSize = 13.5.sp)
    }
}
