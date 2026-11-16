package app.msplayer.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object OnlineImageCache {
    private val memoryCache = LruCache<String, Bitmap>(60)

    fun get(url: String): Bitmap? = memoryCache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        memoryCache.put(url, bitmap)
    }
}

@Composable
fun rememberRemoteBitmap(url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf(OnlineImageCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                val loaded = runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (loaded != null) {
                    OnlineImageCache.put(url, loaded)
                    withContext(Dispatchers.Main) {
                        bitmap = loaded
                    }
                }
            }
        }
    }
    return bitmap
}

@Composable
fun OnlineMusicScreen(
    activity: MainActivity,
    service: PlaybackService?,
    palette: AppPalette,
    glassEnabled: Boolean,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<YtTrack>>(emptyList()) }
    var trendingTracks by remember { mutableStateOf<List<YtTrack>>(emptyList()) }
    var resolvingTrackId by remember { mutableStateOf<String?>(null) }
    var activeCategory by rememberSaveable { mutableStateOf("Top Hits") }

    val categories = listOf("Top Hits", "Trending", "Pop", "Rock", "Lo-Fi", "Acoustic", "Bollywood", "EDM", "Hip Hop")

    fun performSearch(q: String) {
        if (q.isBlank()) return
        focusManager.clearFocus()
        isSearching = true
        scope.launch {
            val results = YtMusicClient.searchSongs(q)
            searchResults = results
            isSearching = false
        }
    }

    // Load initial trending tracks
    LaunchedEffect(Unit) {
        if (trendingTracks.isEmpty()) {
            isSearching = true
            val initial = YtMusicClient.getTrending()
            trendingTracks = initial
            if (searchResults.isEmpty()) {
                searchResults = initial
            }
            isSearching = false
        }
    }

    fun playTrack(track: YtTrack) {
        if (resolvingTrackId != null) return
        resolvingTrackId = track.videoId
        scope.launch {
            val streamUrl = YtMusicClient.resolveStreamUrl(track.videoId)
            resolvingTrackId = null
            if (streamUrl != null && service != null) {
                val mediaItem = MediaItem(
                    uri = streamUrl,
                    title = track.title,
                    artist = track.artist,
                    audio = true,
                    thumbUri = track.thumbUrl,
                )
                service.open(listOf(mediaItem), mediaItem, autoPlay = true)
                activity.showPlayer = true
            } else {
                Toast.makeText(context, "Could not stream audio track", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
    ) {
        // Top Search Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = {
                    Text(
                        "Search YouTube Music...",
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = palette.accent)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchResults = trendingTracks
                        }) {
                            Icon(Icons.Default.Close, null, tint = palette.textSecondary)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(searchQuery) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = if (glassEnabled) Color.White.copy(alpha = 0.15f) else palette.cardBorder,
                    focusedContainerColor = if (glassEnabled) Color.White.copy(alpha = 0.06f) else palette.surface,
                    unfocusedContainerColor = if (glassEnabled) Color.White.copy(alpha = 0.04f) else palette.surface,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { performSearch(searchQuery) },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.accent)
                    .size(46.dp)
            ) {
                Icon(Icons.Default.ArrowForward, "Search", tint = Color.Black)
            }
        }

        // Category Chips
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { cat ->
                val isSelected = activeCategory == cat
                val chipSrc = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) palette.accent else if (glassEnabled) Color.White.copy(alpha = 0.08f) else palette.surface
                        )
                        .clickable(interactionSource = chipSrc, indication = ripple()) {
                            activeCategory = cat
                            searchQuery = cat
                            performSearch(cat)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        cat,
                        color = if (isSelected) Color.Black else palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Connecting to YouTube Music...",
                        color = palette.textSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else if (searchResults.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicOff,
                        null,
                        tint = palette.textSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No songs found",
                        color = palette.textSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Try another search keyword or category",
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // If trending exists and not searching, show horizontal featured spotlight
                if (trendingTracks.isNotEmpty() && searchQuery.isEmpty()) {
                    item {
                        Text(
                            "Trending Spotlight",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(trendingTracks.take(8), key = { "spotlight_${it.videoId}" }) { t ->
                                SpotlightCard(
                                    track = t,
                                    palette = palette,
                                    isResolving = resolvingTrackId == t.videoId,
                                    onClick = { playTrack(t) }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Top Results",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                items(searchResults, key = { it.videoId }) { track ->
                    OnlineTrackRow(
                        track = track,
                        palette = palette,
                        glassEnabled = glassEnabled,
                        isResolving = resolvingTrackId == track.videoId,
                        onClick = { playTrack(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotlightCard(
    track: YtTrack,
    palette: AppPalette,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = rememberRemoteBitmap(track.thumbUrl)
    val src = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141A24))
            .clickable(interactionSource = src, indication = ripple(), onClick = onClick)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Color.DarkGray)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Headphones, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                    }
                }
                if (isResolving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    track.title,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OnlineTrackRow(
    track: YtTrack,
    palette: AppPalette,
    glassEnabled: Boolean,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = rememberRemoteBitmap(track.thumbUrl)
    val src = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (glassEnabled) Color.White.copy(alpha = 0.05f) else palette.surface
            )
            .clickable(interactionSource = src, indication = ripple(), onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.DarkGray)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MusicNote,
                        null,
                        tint = palette.accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (isResolving) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title and Artist
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = palette.textPrimary,
                fontSize = 14.sp,
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
                    overflow = TextOverflow.Ellipsis
                )
                if (track.durationText.isNotBlank()) {
                    Text(
                        " • ${track.durationText}",
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // Play Button
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.12f))
        ) {
            Icon(
                if (isResolving) Icons.Default.HourglassTop else Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = palette.accent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
