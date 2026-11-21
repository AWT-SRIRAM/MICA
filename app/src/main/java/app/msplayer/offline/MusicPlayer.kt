@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)
package app.msplayer.offline

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.*
import kotlin.math.roundToInt

fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        output.write(buffer, 0, n)
        remaining -= n
    }
    return output.toByteArray()
}

object LocalLyrics {
    data class Line(val time: Long?, val text: String)

    fun parse(text: String): List<Line> {
        val pattern = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
        return text.lineSequence().flatMap { line ->
            val matches = pattern.findAll(line).toList()
            if (matches.isEmpty()) sequenceOf(Line(null, line))
            else matches.asSequence().map { m ->
                Line(
                    m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + m.groupValues[3].padEnd(3, '0').take(3).toLong(),
                    line.replace(pattern, "").trim()
                )
            }
        }.filter { it.text.isNotBlank() && !it.text.matches(Regex("\\[(ar|ti|al|by|offset):.*]")) }.toList().let { rows ->
            if (rows.any { it.time != null }) rows.filter { it.time != null }.sortedBy { it.time } else rows
        }
    }

    fun embedded(context: android.content.Context, uri: String): String? {
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val header = input.readBounded(10)
                if (header.size < 10 || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return@use null
                val version = header[3].toInt()
                if (version !in 3..4 || header[5].toInt() and 0xC0 != 0) return@use null
                fun sync(bytes: ByteArray, start: Int): Int = (0..3).fold(0) { v, i -> (v shl 7) or (bytes[start + i].toInt() and 127) }
                val length = sync(header, 6)
                if (length !in 1..2_097_152) return@use null
                val data = input.readBounded(length)
                var at = 0
                while (at + 10 <= data.size) {
                    val tag = String(data, at, 4, Charsets.ISO_8859_1)
                    val size = if (version == 4) sync(data, at + 4) else (0..3).fold(0) { v, i -> (v shl 8) or (data[at + 4 + i].toInt() and 255) }
                    if (size <= 0 || at + 10L + size > data.size) break
                    if (tag == "USLT" && size > 5 && data[at + 8].toInt() == 0 && data[at + 9].toInt() == 0) {
                        val payload = data.copyOfRange(at + 10, at + 10 + size)
                        val encoding = payload[0].toInt()
                        val charset = when (encoding) {
                            1 -> Charsets.UTF_16
                            2 -> Charsets.UTF_16BE
                            3 -> Charsets.UTF_8
                            else -> Charsets.ISO_8859_1
                        }
                        val body = String(payload, 4, payload.size - 4, charset)
                        return@use body.substringAfter('\u0000', "").takeIf { it.isNotBlank() }
                    }
                    at += 10 + size
                }
                null
            }
        }.getOrNull()
    }
}

@Composable
fun MusicPlayerScreen(
    activity: MainActivity,
    service: PlaybackService,
    state: PlayerState,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val item = state.item ?: return
    val context = LocalContext.current
    val timeline by service.timeline.collectAsState()
    val queue by service.queueItems.collectAsState()
    val autoQueueActive by service.autoQueueActive.collectAsState()
    val autoQueueLoading by service.autoQueueLoading.collectAsState()
    val autoQueueSeedTitle by service.autoQueueSeedTitle.collectAsState()
    val shuffle by service.shuffleEnabled.collectAsState()
    val repeat by service.repeatMode.collectAsState()
    val ab by service.abPoints.collectAsState()
    val sleepRemaining by service.sleepTimerRemainingMs.collectAsState()
    val sleepMode by service.sleepTimerMode.collectAsState()
    val activeDownloads by AudioDownloadManager.activeDownloads.collectAsState()

    var panel by remember { mutableStateOf<String?>(null) }
    var title by remember(item.uri) { mutableStateOf(item.title) }
    var artist by remember(item.uri) { mutableStateOf(item.artist.ifBlank { "Unknown Artist" }) }
    var artwork by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var mood by remember(item.uri) { mutableStateOf(AdaptiveMoodColors()) }
    var lyrics by remember(item.uri) { mutableStateOf<List<LocalLyrics.Line>>(emptyList()) }
    var lyricsLoading by remember(item.uri) { mutableStateOf(true) }
    var favourite by remember(item.uri) { mutableStateOf(false) }
    val likedFromService by service.isCurrentTrackLiked.collectAsState()
    LaunchedEffect(likedFromService) { favourite = likedFromService }
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> }
        activity.lifecycle.addObserver(observer)
        val origOrientation = activity.requestedOrientation
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity.lifecycle.removeObserver(observer)
            service.endTemporarySpeed()
            activity.requestedOrientation = origOrientation
        }
    }

    LaunchedEffect(item.uri) {
        withContext(Dispatchers.IO) {
            val isLocal = item.uri.startsWith("content://") || item.uri.startsWith("file://") || item.uri.startsWith("/")
            var cover: android.graphics.Bitmap? = null
            var t: String? = null
            var a: String? = null

            if (isLocal) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (item.uri.startsWith("/") || item.uri.startsWith("file://")) {
                        val path = if (item.uri.startsWith("file://")) Uri.parse(item.uri).path else item.uri
                        if (!path.isNullOrBlank()) retriever.setDataSource(path)
                    } else {
                        activity.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { fd ->
                            retriever.setDataSource(fd.fileDescriptor)
                        }
                    }
                    t = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    cover = retriever.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { bytes ->
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 720).coerceAtLeast(1) }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {} finally { retriever.release() }
            } else {
                // Online stream or lazy resolve track
                t = item.title
                a = item.artist.ifBlank { "YouTube Music" }
                val meta = TrackArtworkStore.findMeta(activity, item.uri, item.title)
                val thumb = item.thumbUri?.takeIf { it.isNotBlank() } ?: meta?.thumbUrl
                val localCover = meta?.localCoverPath?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                if (localCover != null) {
                    cover = runCatching { android.graphics.BitmapFactory.decodeFile(localCover) }.getOrNull()
                }
                if (cover == null && !thumb.isNullOrBlank()) {
                    cover = runCatching {
                        val conn = java.net.URL(thumb).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("Connection", "keep-alive")
                        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
            }

            val resolvedTitle = t?.takeIf { it.isNotBlank() } ?: item.title
            val resolvedArtist = a?.takeIf { it.isNotBlank() } ?: (item.artist.takeIf { it.isNotBlank() } ?: "Unknown artist")

            // Auto-resolve & cache HD artwork if embeddedPicture or direct thumb was missing (e.g. downloaded offline tracks)
            if (cover == null) {
                cover = TrackArtworkStore.resolveAndCacheCover(
                    context = activity,
                    uri = item.uri,
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    existingThumbUri = item.thumbUri,
                )
            }

            val extractedMood = extractAdaptiveMoodColors(cover)
            // Persist track artwork & artist metadata so Liked Songs & My Top 50 always show HD thumbnails
            val coverBmp = cover
            if (coverBmp != null) {
                TrackArtworkStore.saveBitmap(activity, item.uri, resolvedTitle, resolvedArtist, coverBmp, item.thumbUri ?: "")
            } else if (!item.thumbUri.isNullOrBlank()) {
                TrackArtworkStore.saveMeta(activity, item.uri, resolvedTitle, resolvedArtist, item.thumbUri)
            }
            withContext(Dispatchers.Main) {
                title = resolvedTitle
                artist = resolvedArtist
                artwork = cover
                mood = extractedMood
            }

            val stored = Store.prefs(activity).getString("lyrics_uri_${item.uri.hashCode()}", null)
            var words = stored?.let { location -> runCatching { activity.contentResolver.openInputStream(Uri.parse(location))?.use { String(it.readBounded(1024 * 1024), Charsets.UTF_8) } }.getOrNull() }
                ?: if (isLocal) LocalLyrics.embedded(activity, item.uri) else null

            if (words.isNullOrBlank() && (!isLocal || item.artist.isNotBlank())) {
                words = YtMusicClient.fetchLyrics(item.title, item.artist)
            }

            val liked = Store.db(activity).dao().entries("Favorites").any { it.uri == item.uri }
            withContext(Dispatchers.Main) {
                favourite = liked
                if (words != null) lyrics = LocalLyrics.parse(words)
                lyricsLoading = false
            }
        }
    }

    BackHandler { if (panel != null) panel = null else activity.exitPlayer() }

    val glassEnabled by rememberGlassEnabled()
    val musicBackdrop = rememberLayerBackdrop()
    val dynamicPrimary by animateColorAsState(mood.primary, MotionSpec.ColorSpring, label = "moodP")
    val dynamicSecondary by animateColorAsState(mood.secondary, MotionSpec.ColorSpring, label = "moodS")

    val playerBackgroundBrush = remember(dynamicPrimary) {
        val topTone = androidx.compose.ui.graphics.lerp(Color(0xFF0E121A), dynamicPrimary.copy(alpha = 1f), 0.45f)
        val midTone = androidx.compose.ui.graphics.lerp(Color(0xFF0A0D13), dynamicPrimary.copy(alpha = 1f), 0.16f)
        Brush.verticalGradient(
            listOf(
                topTone,
                midTone,
                Color(0xFF0A0D13),
                Color(0xFF05070A),
            )
        )
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 100.dp.toPx() }
    val minFlingPx = with(density) { 15.dp.toPx() }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta > 0 && scrollState.value == 0) {
                    scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
                    return Offset(0f, delta)
                } else if (delta < 0 && dragY.value > 0f) {
                    val newDrag = (dragY.value + delta).coerceAtLeast(0f)
                    val consumed = dragY.value - newDrag
                    scope.launch { dragY.snapTo(newDrag) }
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta > 0 && scrollState.value == 0) {
                    scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragY.value > dismissThresholdPx || (available.y > 900f && dragY.value > minFlingPx)) {
                    activity.exitPlayer()
                    return available
                } else if (dragY.value > 0f) {
                    dragY.animateTo(0f, MotionSpec.PressSpring)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragY.value > dismissThresholdPx || (available.y > 900f && dragY.value > minFlingPx)) {
                    activity.exitPlayer()
                } else if (dragY.value > 0f) {
                    dragY.animateTo(0f, MotionSpec.PressSpring)
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(playerBackgroundBrush)
            .nestedScroll(nestedScrollConnection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > dismissThresholdPx) activity.exitPlayer()
                            else dragY.animateTo(0f, MotionSpec.PressSpring)
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f, MotionSpec.PressSpring) } },
                    onVerticalDrag = { _, dragAmount ->
                        if ((dragAmount > 0f && scrollState.value == 0) || dragY.value > 0f) {
                            scope.launch { dragY.snapTo((dragY.value + dragAmount).coerceAtLeast(0f)) }
                        }
                    },
                )
            }
    ) {
        // Soft Luminous Ambient Bloom behind artwork
        Box(
            Modifier
                .size(380.dp)
                .align(Alignment.TopCenter)
                .offset(y = 100.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            dynamicSecondary.copy(alpha = 0.35f),
                            dynamicPrimary.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    ),
                    CircleShape
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Top Header Bar: Down chevron | Playing from Playlist caption + name | 3-dots (Opens Information)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { activity.exitPlayer() }) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        "Close",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Text(
                        "Playing from Playlist",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(1.dp))
                    val headerTitle = when {
                        item.folder.isNotBlank() && item.folder != "Storage" && !item.folder.equals("Playlists", ignoreCase = true) -> item.folder.uppercase()
                        item.uri.startsWith("content://") || item.uri.startsWith("file://") -> "LOCAL TRACKS"
                        else -> "MICA MUSIC"
                    }
                    Text(
                        headerTitle,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 3-Dots More Menu (Opens Track Options: Audio Effects, Equalizer, Details, Sleep Timer)
                IconButton(onClick = { panel = "Options" }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        "Options & Audio Effects",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            val dragX = remember(item.uri) { Animatable(0f) }
            val currentQueue by service.queueItems.collectAsState()
            val currentIndex by service.queueIndex.collectAsState()
            val nextTrack = currentQueue.getOrNull(currentIndex + 1)
            val prevTrack = currentQueue.getOrNull(currentIndex - 1)

            if (state.error != null) {
                // Frosted Glass Error Diagnostics Card (No plain black/white screen)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(vertical = 10.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xE01E1B26))
                        .border(1.2.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CloudOff, null, tint = Color(0xFFEF4444), modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Playback Interrupted",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.error ?: "Unable to stream this audio track.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Retry Button
                            Button(
                                onClick = {
                                    service.retryCurrent()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            // Next Track Button
                            OutlinedButton(
                                onClick = { service.next() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Next", color = Color.White, fontSize = 13.sp)
                            }
                            // Play Offline Button
                            OutlinedButton(
                                onClick = { activity.exitPlayer() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Offline", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                // 2. Large Centered Square Album Artwork Card with Spotify-style horizontal swipe & peek preview
                val peekTrack = if (dragX.value < 0) nextTrack else prevTrack
                val peekLabel = if (dragX.value < 0) "Up Next" else "Previous"
                val peekArtwork = rememberRemoteBitmap(peekTrack?.thumbUri)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(vertical = 10.dp)
                        .pointerInput(item.uri) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        val currentX = dragX.value
                                        if (currentX < -70.dp.toPx()) {
                                            service.next()
                                        } else if (currentX > 70.dp.toPx()) {
                                            service.previous()
                                        }
                                        dragX.animateTo(0f, MotionSpec.PressSpring)
                                    }
                                },
                                onDragCancel = { scope.launch { dragX.animateTo(0f, MotionSpec.PressSpring) } },
                                onHorizontalDrag = { _, dragAmount ->
                                    scope.launch { dragX.snapTo(dragX.value + dragAmount) }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val artShape = RoundedCornerShape(20.dp)

                    // Layer 1: Peeking Preview Layer behind active card
                    if (kotlin.math.abs(dragX.value) > 4f && peekTrack != null) {
                        val peekProgress = (kotlin.math.abs(dragX.value) / 180f).coerceIn(0f, 1f)
                        val peekScale = 0.88f + (peekProgress * 0.12f)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = peekScale
                                    scaleY = peekScale
                                    alpha = (peekProgress * 1.2f).coerceIn(0.2f, 1f)
                                }
                                .clip(artShape)
                                .background(Color(0xFF111827))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), artShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (peekArtwork != null) {
                                Image(peekArtwork.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(80.dp))
                            }
                            // Frosted pill badge
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 16.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .border(0.8.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "$peekLabel: ${peekTrack.title}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Layer 2: Active Artwork Card
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = dragX.value
                                rotationZ = (dragX.value / 25f).coerceIn(-8f, 8f)
                            }
                            .shadow(
                                elevation = 28.dp,
                                shape = artShape,
                                spotColor = dynamicPrimary.copy(alpha = 0.75f),
                                ambientColor = Color.Black
                            )
                            .clip(artShape)
                            .background(Color(0xFF161F2E))
                            .border(1.2.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f))), artShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (artwork != null) {
                            Image(
                                artwork!!.asImageBitmap(),
                                "Album Cover",
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.MusicNote,
                                null,
                                tint = Color.White.copy(alpha = 0.40f),
                                modifier = Modifier.size(90.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 3. Track Info Row: Bold Title & Artist + Bitrate Badge on Left | Green Circular Checkmark on Right
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            artist,
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val mediaInfo = remember(item.uri, state.playing) { runCatching { service.currentMediaDetailedInfo() }.getOrNull() }
                        val audioCodec = mediaInfo?.streams?.firstOrNull { it.type == "Audio" }?.codec.orEmpty().lowercase()
                        val isFlac = !item.uri.contains("googlevideo") && !item.uri.contains("saavn") &&
                            (item.uri.endsWith(".flac", ignoreCase = true) || item.uri.contains("flac", ignoreCase = true) || item.uri.contains("motherearth", ignoreCase = true) || audioCodec.contains("flac"))
                        val isAlac = !item.uri.contains("googlevideo") && !item.uri.contains("saavn") &&
                            (item.uri.endsWith(".alac", ignoreCase = true) || (item.uri.endsWith(".m4a", ignoreCase = true) && item.title.contains("ALAC", ignoreCase = true)) || audioCodec.contains("alac"))
                        val badgeText = when {
                            isFlac -> "LOSSLESS FLAC"
                            isAlac -> "LOSSLESS ALAC"
                            else -> null
                        }
                        if (badgeText != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                badgeText,
                                color = Color(0xFFFFD54F),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(Color(0xFFFFD54F).copy(alpha = 0.18f))
                                    .border(0.5.dp, Color(0xFFFFD54F).copy(alpha = 0.40f), RoundedCornerShape(100.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Spotify Heart Favorite Button (Favorite / FavoriteBorder)
                IconButton(
                    onClick = {
                        service.toggleCurrentTrackLike()
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (favourite) "Favorited" else "Favorite",
                        tint = if (favourite) Color(0xFF1DB954) else Color.White.copy(alpha = 0.80f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 4. Smooth Spotify Seek Bar with Circular Dot Thumb (Zero vertical gesture conflict)
            var targetSeek by remember(item.uri) { mutableStateOf<Long?>(null) }
            val currentPos = targetSeek ?: timeline.position
            val totalDur = timeline.duration.coerceAtLeast(1L)
            val progressFraction = (currentPos.toFloat() / totalDur.toFloat()).coerceIn(0f, 1f)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // Subtle grey background line
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                )

                // Active pure white played line
                Box(
                    Modifier
                        .fillMaxWidth(progressFraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White)
                )

                // White Circular Thumb Dot with clean accent border (Static, zero blinking)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(Modifier.fillMaxWidth(progressFraction)) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(1.5.dp, Accent, CircleShape)
                            )
                        }
                    }
                }

                // Interactive slider with 400ms seek-latch to prevent jump-back
                Slider(
                    value = currentPos.toFloat(),
                    onValueChange = { targetSeek = it.toLong() },
                    onValueChangeFinished = {
                        val seekTo = targetSeek
                        if (seekTo != null) {
                            service.seek(seekTo)
                            scope.launch {
                                delay(400)
                                if (targetSeek == seekTo) targetSeek = null
                            }
                        }
                    },
                    valueRange = 0f..totalDur.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Transparent,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().alpha(0f)
                )
            }

            // Timestamps Row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    durationText(currentPos),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    durationText(totalDur),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(10.dp))

            // 5. Playback Controls Row: Shuffle/Loop Toggle | Prev | BIG CIRCULAR WHITE PLAY/PAUSE (64dp) | Next | Sleep Timer
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Button: Shuffle by default, single tap toggles to Loop
                IconButton(onClick = {
                    if (shuffle) {
                        service.toggleShuffle()
                        service.toggleRepeat()
                    } else if (repeat != "off") {
                        service.toggleRepeat()
                        service.toggleShuffle()
                    } else {
                        service.toggleShuffle()
                    }
                }) {
                    val isLoopActive = repeat != "off"
                    val icon = if (isLoopActive) (if (repeat == "one") Icons.Default.RepeatOne else Icons.Default.Repeat) else Icons.Default.Shuffle
                    val tint = if (shuffle || isLoopActive) Color(0xFF1DB954) else Color.White.copy(alpha = 0.85f)
                    Icon(
                        icon,
                        contentDescription = "Shuffle / Loop",
                        tint = tint,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Skip Previous (Standard crisp default icon)
                IconButton(onClick = { service.previous() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // BIG CIRCULAR WHITE PLAY/PAUSE BUTTON (64dp, Black icon)
                var optimisticPlaying by remember(state.playing) { mutableStateOf(state.playing) }
                val playPauseSrc = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .iosLiquidPress(playPauseSrc, scaleDown = 0.90f)
                        .clickable(
                            interactionSource = playPauseSrc,
                            indication = ripple(color = Color.Black.copy(alpha = 0.2f))
                        ) {
                            optimisticPlaying = !optimisticPlaying
                            service.toggle()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (optimisticPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (optimisticPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Skip Next (Standard crisp default icon)
                IconButton(onClick = { service.next() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Right Button: Sleep Timer Crescent Moon (Screenshot_20261119_132518.jpg)
                IconButton(onClick = { panel = "Sleep" }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer_moon),
                        contentDescription = "Sleep Timer",
                        tint = if (sleepMode != "Off") Color(0xFF1DB954) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 6. Bottom Utilities Row: Cast | Lyrics | Jam | Download | Share (SpaceEvenly, Zero Overflow)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cast
                IconButton(
                    onClick = { panel = "Cast" },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cast_flaticon),
                        contentDescription = "Cast & Output Devices",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Karaoke Lyrics
                val hasLyrics = lyrics.isNotEmpty()
                IconButton(
                    onClick = { panel = "Lyrics" },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics_flaticon),
                        contentDescription = "Lyrics",
                        tint = if (panel == "Lyrics" || hasLyrics) Accent else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Mica Jam
                val jamState by JamSyncController.sessionState.collectAsState()
                val isJamActive = jamState.role != JamRole.NONE && jamState.isConnected
                IconButton(
                    onClick = { panel = "Jam" },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.SpeakerGroup,
                            "Mica Jam — Listen Together",
                            tint = if (isJamActive) Color(0xFF10B981) else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                        if (isJamActive) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .align(Alignment.TopEnd)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                        }
                    }
                }

                // Download
                val activeDownloads by AudioDownloadManager.activeDownloads.collectAsState()
                val isDlDone = AudioDownloadManager.isDownloaded(title, artist)
                val dlProgress = activeDownloads[item.sourceVideoId] ?: activeDownloads.values.find { it.title == title }
                val isDlActive = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed
                IconButton(
                    onClick = {
                        if (isDlDone) {
                            activity.toast("Already downloaded to storage")
                        } else if (isDlActive) {
                            activity.toast("Download in progress...")
                        } else if (item.sourceVideoId.isNotBlank()) {
                            AudioDownloadManager.downloadSong(
                                activity,
                                scope,
                                item.sourceVideoId,
                                title,
                                artist,
                                item.thumbUri ?: ""
                            )
                        } else if (item.uri.startsWith("http")) {
                            scope.launch {
                                val ytMatch = runCatching { YtMusicClient.searchSongs("$title $artist").firstOrNull() }.getOrNull()
                                if (ytMatch != null) {
                                    AudioDownloadManager.downloadSong(
                                        activity,
                                        scope,
                                        ytMatch.videoId,
                                        ytMatch.title,
                                        ytMatch.artist,
                                        ytMatch.thumbUrl
                                    )
                                } else {
                                    activity.toast("Cannot download stream directly")
                                }
                            }
                        } else {
                            activity.toast("Local file already on device")
                        }
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    if (isDlActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_download_flaticon),
                            contentDescription = "Download",
                            tint = if (isDlDone) Color(0xFF10B981) else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Share
                IconButton(
                    onClick = {
                        val shareLink = if (item.uri.startsWith("http")) {
                            if (item.uri.contains("googlevideo") || item.uri.contains("youtube")) {
                                "https://music.youtube.com/search?q=" + java.net.URLEncoder.encode("$title $artist", "UTF-8")
                            } else {
                                item.uri
                            }
                        } else {
                            "Track: $title by $artist (Playing in MICA)"
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Listening to $title on MICA")
                            putExtra(Intent.EXTRA_TEXT, "Now playing: $title - $artist\n$shareLink")
                        }
                        activity.startActivity(Intent.createChooser(shareIntent, "Share Track via"))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share_flaticon),
                        contentDescription = "Share",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 7. Bottom Peeking Card: Playing Queue (Oval pill, matching dock bar)
            val qIndex = queue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
            val nextSong = if (queue.isNotEmpty() && qIndex in 0 until queue.lastIndex) queue[qIndex + 1] else null
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF232524))
                    .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(32.dp))
                    .clickable { panel = "Queue" }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (queue.isNotEmpty()) "Queue • ${queue.size} songs" else "Queue",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (nextSong != null) "Next: ${nextSong.title}" else "End of queue",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_queue_flaticon),
                        contentDescription = "Queue",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // Panels Overlay (Queue, Effects, Lyrics, Speed, Options)
    when (panel) {
        "Effects" -> PlayerSidePanel("Audio effects", { panel = null }) { AudioEffectsControls(service) }
        "Queue" -> PlayerSidePanel("Playing Queue", { panel = null }, scrollable = false) {
            if (autoQueueActive || autoQueueLoading) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(dynamicPrimary.copy(alpha = 0.16f))
                        .border(0.8.dp, dynamicPrimary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (autoQueueLoading) {
                        CircularProgressIndicator(color = dynamicPrimary, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = dynamicPrimary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        if (autoQueueLoading) "Finding similar songs for your vibe..."
                        else "Auto-Queue Active • Similar to ${autoQueueSeedTitle ?: title}",
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (queue.isEmpty()) {
                Text("Queue is empty", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(queue.size) { idx ->
                        val track = queue[idx]
                        val currentTrack = track.uri == item.uri ||
                            (item.sourceVideoId.isNotBlank() && track.sourceVideoId == item.sourceVideoId)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (currentTrack) dynamicPrimary.copy(alpha = 0.20f) else Color.Transparent)
                                .clickable { service.playQueueItem(track); panel = null }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (currentTrack) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = if (currentTrack) dynamicPrimary else Color.White.copy(alpha = 0.65f),
                                modifier = Modifier.size(18.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = if (currentTrack) dynamicPrimary else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (currentTrack) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (track.artist.isNotBlank()) {
                                    Text(
                                        track.artist,
                                        color = Color.White.copy(alpha = 0.58f),
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (track.duration > 0L) {
                                Text(durationText(track.duration), color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        "Sleep" -> PlayerSidePanel("Sleep Timer", { panel = null }) {
            SleepTimerPanelContent(service, onDismiss = { panel = null })
        }
        "Jam" -> {
            JamSyncDialog(service = service, onDismiss = { panel = null })
        }
        "Speed" -> PlayerSpeedBar(
            rate = state.rate,
            onRateChange = { service.rate(it) },
            onDismiss = { panel = null },
        )
        "Lyrics" -> PlayerSidePanel("Lyrics", { panel = null }, scrollable = false) {
            if (lyricsLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Fetching synchronized lyrics...", color = Color.White.copy(alpha = 0.75f), fontSize = 13.5.sp)
                }
            } else if (lyrics.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("No synchronized lyrics available for this track", color = Muted, fontSize = 13.5.sp, textAlign = TextAlign.Center)
                }
            } else {
                val current = lyrics.indexOfLast { it.time != null && it.time <= timeline.position }
                val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(current) { if (current >= 0) scroll.animateScrollToItem(current) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 180.dp, max = 420.dp), state = scroll) {
                    items(lyrics.size) { i ->
                        val line = lyrics[i]
                        val isLineActive = i == current
                        Text(
                            line.text,
                            color = if (isLineActive) Accent else Color.White.copy(alpha = 0.72f),
                            fontSize = if (isLineActive) 17.5.sp else 14.5.sp,
                            fontWeight = if (isLineActive) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { line.time?.let { service.seek(it) } }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
        "Options" -> PlayerSidePanel("Track Options", { panel = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 1. Track Details & Codec Info
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            panel = "Information"
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Info, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Track Details & Codec Info", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Bitrate, sample rate, channels, codec format", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                }

                // 3. Audio Equalizer
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            panel = "Effects"
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Audio Equalizer & Sound Effects", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("10-band EQ, bass boost, spatializer presets", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                }

                // 4. Sleep Timer
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            panel = "Sleep"
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.NightlightRound, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Sleep Timer", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Set music stop timer with soft audio fade-out", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                }

                // 5. MICA JAM — Listen Together
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            panel = "Jam"
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Groups, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("MICA JAM — Listen Together", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Sync music with friends in real-time over Hotspot / LAN", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                }
            }
        }
        "Information" -> PlayerSidePanel("Information", { panel = null }) {
            val info = remember(item.uri, state.duration, state.audioTracks.size) { service.currentMediaDetailedInfo() }
            val bitrateVal = when {
                (info?.overallBitrateKbps ?: 0) > 0 -> "${info?.overallBitrateKbps} kbps"
                item.uri.contains("_320.mp4") || item.uri.contains("saavn") -> "320 kbps (High Fidelity)"
                item.uri.contains("googlevideo") -> "160 kbps Opus (Studio Audio)"
                else -> "320 kbps High Fidelity"
            }
            if (artist.isNotBlank() && artist != "Unknown Artist") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.06f))), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1DB954).copy(alpha = 0.20f))
                                .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.40f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Person, null, tint = Color(0xFF1DB954), modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Artist", color = Color.White.copy(alpha = 0.70f), fontSize = 11.5.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(artist, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            // Audiophile Fidelity Header Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.06f))), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = 0.20f))
                            .border(1.dp, Accent.copy(alpha = 0.40f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Accent, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Audio Bitrate & Fidelity", color = Color.White.copy(alpha = 0.70f), fontSize = 11.5.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(bitrateVal, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (info != null) {
                MediaInformationContent(info = info, inPlayer = true)
            } else {
                Text("Media information unavailable", color = Muted, fontSize = 13.sp)
            }
        }
        "Cast" -> PlayerSidePanel("Cast & Output Devices", { panel = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 1. Chromecast / Wireless Display
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            runCatching {
                                context.startActivity(Intent("android.settings.CAST_SETTINGS"))
                            }.onFailure {
                                Toast.makeText(context, "Wireless Display / Cast settings unavailable", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Cast, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Chromecast & Wireless Display", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Stream music to Smart TV, Google Nest, or wireless speakers", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f))
                }

                // 2. Bluetooth Audio
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                            }.onFailure {
                                Toast.makeText(context, "Bluetooth settings unavailable", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Bluetooth, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bluetooth Audio Devices", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Connect headphones, earbuds, or Bluetooth speakers", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f))
                }

                // 3. Audio Equalizer & Sound Effects
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            panel = "Effects"
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Equalizer & Sound Effects", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("10-band EQ, bass boost, spatializer presets", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f))
                }

                // 4. MICA JAM — Listen Together
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            panel = "Jam"
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Groups, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MICA JAM — Listen Together", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Sync playback with friends over Hotspot / LAN", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

fun formatSleepCountdown(remainingMs: Long?, mode: String): String {
    if (mode == "EndOfTrack") return "End of Track"
    val ms = remainingMs ?: return ""
    val minutes = (ms / 60000).toInt()
    val seconds = ((ms % 60000) / 1000).toInt()
    return "${minutes}m ${seconds}s"
}

@Composable
fun SleepTimerPanelContent(service: PlaybackService, onDismiss: () -> Unit) {
    val sleepRemaining by service.sleepTimerRemainingMs.collectAsState()
    val sleepMode by service.sleepTimerMode.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Text("Stop audio playback automatically after:", color = Muted, fontSize = 13.5.sp)
        Spacer(Modifier.height(12.dp))
        listOf(15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour").forEach { (mins, label) ->
            Button(
                onClick = { service.setSleepTimer(mins); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2634))
            ) {
                Text(label, color = Color.White)
            }
        }
        Button(
            onClick = { service.setSleepTimer(null, endOfTrack = true); onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2634))
        ) {
            Text("End of current track", color = Color.White)
        }
        if (sleepMode != "Off") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { service.cancelSleepTimer(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFFF5252))
            ) {
                Text("Turn off sleep timer", color = Color(0xFFFF5252))
            }
        }
    }
}
