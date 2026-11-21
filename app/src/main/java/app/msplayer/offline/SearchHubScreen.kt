package app.msplayer.offline

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchCategoryTile(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val gradientColors: List<Color>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchHubScreen(
    activity: MainActivity,
    service: PlaybackService?,
    palette: AppPalette,
    glassEnabled: Boolean,
    bottomPadding: Dp,
    onNavigateToTab: (String) -> Unit,
    onOpenBrowser: (url: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<YtTrack>>(emptyList()) }
    var isLoadingResults by remember { mutableStateOf(false) }
    var editingUrlTile by remember { mutableStateOf<SearchCategoryTile?>(null) }
    var editingUrlText by remember { mutableStateOf("") }

    // Preloaded feeds from FeedCacheManager
    val cachedTopHits by FeedCacheManager.cachedTopHits.collectAsState()
    val cachedPlaylists by FeedCacheManager.cachedTrendingPlaylists.collectAsState()

    // 2x3 Grid definition (matching Screenshot_20261121_095620.jpg)
    val categoryTiles = remember {
        listOf(
            SearchCategoryTile("music", "Music", Icons.Rounded.Headphones, listOf(Color(0xFFE11D48), Color(0xFFF43F5E))),
            SearchCategoryTile("videos", "Videos", Icons.Rounded.PlayCircle, listOf(Color(0xFF059669), Color(0xFF10B981))),
            SearchCategoryTile("anime", "Anime", Icons.Rounded.Tv, listOf(Color(0xFF2563EB), Color(0xFF3B82F6))),
            SearchCategoryTile("tamilcartoons", "Tamilcartoons", Icons.Rounded.Animation, listOf(Color(0xFF7C3AED), Color(0xFF8B5CF6))),
            SearchCategoryTile("playlist", "Playlist", Icons.Rounded.QueueMusic, listOf(Color(0xFF4F46E5), Color(0xFF6366F1))),
            SearchCategoryTile("made_for_you", "Made for you", Icons.Rounded.AutoAwesome, listOf(Color(0xFFD946EF), Color(0xFFA855F7))),
        )
    }

    // Discover cards
    val discoverPicks = remember {
        listOf(
            Triple("#tamil dance", "Energy Booster", listOf(Color(0xFFFF5722), Color(0xFFFF9800))),
            Triple("#tamil pop", "Modern Grooves", listOf(Color(0xFFE91E63), Color(0xFFFF4081))),
            Triple("#feel good", "Breezy Hits", listOf(Color(0xFF009688), Color(0xFF4DB6AC))),
            Triple("#trending", "Viral 2026", listOf(Color(0xFF3F51B5), Color(0xFF7986CB))),
            Triple("#lo-fi chill", "Midnight Beats", listOf(Color(0xFF673AB7), Color(0xFF9575CD))),
        )
    }

    LaunchedEffect(searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.length >= 2) {
            delay(150L)
            val list = YtMusicClient.getSearchSuggestions(trimmed)
            searchSuggestions = list
            showSuggestions = list.isNotEmpty()
        } else {
            searchSuggestions = emptyList()
            showSuggestions = false
            if (trimmed.isEmpty()) {
                searchResults = emptyList()
                isSearching = false
            }
        }
    }

    fun executeSearch(q: String) {
        val query = q.trim()
        if (query.isBlank()) return
        focusManager.clearFocus()
        showSuggestions = false
        isSearching = true
        isLoadingResults = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    val yt = YtMusicClient.searchSongs(query)
                    if (yt.isNotEmpty()) yt else {
                        JioSaavnClient.searchSongs(query).map { s ->
                            YtTrack(
                                videoId = "saavn_${s.id}",
                                title = s.title,
                                artist = s.artist,
                                thumbUrl = s.thumbUrl,
                                directStreamUrl = s.streamUrl
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            }
            searchResults = results
            isLoadingResults = false
        }
    }

    fun handleCategoryClick(tile: SearchCategoryTile) {
        when (tile.id) {
            "music" -> onNavigateToTab("Audio")
            "videos" -> {
                onOpenBrowser("https://m.youtube.com", "YouTube")
            }
            "anime" -> {
                val animeUrl = Store.prefs(context).getString("mica_anime_url", "https://myanimes.in/") ?: "https://myanimes.in/"
                onOpenBrowser(animeUrl, "MyAnimes")
            }
            "tamilcartoons" -> {
                val cartoonUrl = Store.prefs(context).getString("mica_tamilcartoons_url", "https://tamilcartoonsrare.in/") ?: "https://tamilcartoonsrare.in/"
                onOpenBrowser(cartoonUrl, "Tamil Cartoons Rare")
            }
            "playlist" -> onNavigateToTab("Library")
            "made_for_you" -> onNavigateToTab("Audio")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Search Bar: "What do you want to listen or watch?"
            item(key = "search_dock_bar") {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                if (palette.isLight) Color.White else Color(0xFF1E2633)
                            )
                            .border(
                                1.dp,
                                if (palette.isLight) Color(0xFFE2E8F0) else Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(28.dp)
                            ),
                        placeholder = {
                            Text(
                                "What do you want to listen or watch?",
                                color = palette.textSecondary,
                                fontSize = 14.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, "Search", tint = palette.accent, modifier = Modifier.size(22.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearching = false
                                    searchResults = emptyList()
                                }) {
                                    Icon(Icons.Rounded.Close, "Clear", tint = palette.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { executeSearch(searchQuery) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary
                        ),
                    )

                    // Autocomplete Suggestions Dropdown
                    AnimatedVisibility(visible = showSuggestions && searchSuggestions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (palette.isLight) Color.White else Color(0xFF161E2D))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                                .padding(vertical = 4.dp)
                        ) {
                            searchSuggestions.take(6).forEach { sug ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            searchQuery = sug
                                            executeSearch(sug)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Search, null, tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                                    Text(
                                        sug,
                                        color = palette.textPrimary,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!isSearching) {
                // Section: Browse all / 2x3 Grid (matching Screenshot_20261121_095620.jpg)
                item(key = "browse_all_header") {
                    Text(
                        "Browse all",
                        color = palette.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                    )
                }

                // 2x3 Grid with 6 colorful cards
                items(categoryTiles.chunked(2), key = { it.first().id }) { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pair.forEach { tile ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(98.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Brush.linearGradient(tile.gradientColors))
                                    .combinedClickable(
                                        onClick = { handleCategoryClick(tile) },
                                        onLongClick = {
                                            if (tile.id == "anime" || tile.id == "tamilcartoons") {
                                                val def = if (tile.id == "anime") "https://myanimes.in/" else "https://tamilcartoonsrare.in/"
                                                editingUrlTile = tile
                                                editingUrlText = Store.prefs(context).getString("mica_${tile.id}_url", def) ?: def
                                            }
                                        }
                                    )
                                    .padding(14.dp)
                            ) {
                                Text(
                                    tile.title,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopStart)
                                )

                                Icon(
                                    tile.icon,
                                    contentDescription = tile.title,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .size(38.dp)
                                        .align(Alignment.BottomEnd)
                                )
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // Section: Discover something new (matching Screenshot_20261121_095620.jpg)
                item(key = "discover_header") {
                    Text(
                        "Discover something new",
                        color = palette.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                item(key = "discover_carousel") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 8.dp)
                    ) {
                        items(discoverPicks, key = { it.first }) { pick ->
                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Brush.verticalGradient(pick.third))
                                    .clickable {
                                        searchQuery = pick.first.removePrefix("#")
                                        executeSearch(searchQuery)
                                    }
                                    .padding(14.dp)
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        pick.first,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Column {
                                        Text(
                                            pick.second,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color.White),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, "Play", tint = pick.third.first(), modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Active Search Results View
                if (isLoadingResults) {
                    item(key = "loading_results") {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = palette.accent)
                        }
                    }
                } else if (searchResults.isEmpty()) {
                    item(key = "empty_results") {
                        Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                            Text("No results found for \"$searchQuery\"", color = palette.textSecondary, fontSize = 14.sp)
                        }
                    }
                } else {
                    item(key = "results_header") {
                        Text(
                            "Top Results for \"$searchQuery\"",
                            color = palette.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(searchResults, key = { it.videoId }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    scope.launch {
                                        val resolved = AudioStreamResolver.resolveBestStream(
                                            track.title, track.artist, track.videoId, track.thumbUrl, track.directStreamUrl
                                        )
                                        if (resolved != null && service != null) {
                                            val item = MediaItem(
                                                uri = resolved.streamUrl,
                                                title = track.title,
                                                artist = track.artist,
                                                audio = true,
                                                thumbUri = resolved.hdThumbUrl.ifBlank { track.thumbUrl },
                                                folder = "Search Results",
                                                streamUserAgent = resolved.userAgent
                                            )
                                            service.open(listOf(item), item, autoPlay = true)
                                            activity.showPlayer = true
                                        } else {
                                            Toast.makeText(context, "Could not stream audio track", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val thumbBitmap = rememberRemoteBitmap(track.thumbUrl)
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumbBitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = thumbBitmap.asImageBitmap(),
                                        contentDescription = track.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Rounded.Headphones, null, tint = Color.White.copy(alpha = 0.5f))
                                }
                            }

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
                                Text(
                                    track.artist,
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(Icons.Rounded.PlayArrow, "Play", tint = palette.accent, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }

    if (editingUrlTile != null) {
        val targetTile = editingUrlTile!!
        val defaultUrl = if (targetTile.id == "anime") "https://myanimes.in/" else "https://tamilcartoonsrare.in/"
        AlertDialog(
            onDismissRequest = { editingUrlTile = null },
            title = { Text("Edit ${targetTile.title} URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter website URL to stream ${targetTile.title} directly into MICA:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = editingUrlText,
                        onValueChange = { editingUrlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(defaultUrl) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = editingUrlText.trim().ifBlank { defaultUrl }
                    Store.prefs(context).edit().putString("mica_${targetTile.id}_url", clean).apply()
                    Toast.makeText(context, "${targetTile.title} URL updated", Toast.LENGTH_SHORT).show()
                    editingUrlTile = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        Store.prefs(context).edit().putString("mica_${targetTile.id}_url", defaultUrl).apply()
                        Toast.makeText(context, "Reset to default URL", Toast.LENGTH_SHORT).show()
                        editingUrlTile = null
                    }) {
                        Text("Reset Default")
                    }
                    TextButton(onClick = { editingUrlTile = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}
