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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> }
        activity.lifecycle.addObserver(observer)
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity.lifecycle.removeObserver(observer); service.endTemporarySpeed() }
    }

    LaunchedEffect(item.uri) {
        withContext(Dispatchers.IO) {
            val isLocal = item.uri.startsWith("content://") || item.uri.startsWith("file://")
            var cover: android.graphics.Bitmap? = null
            var t: String? = null
            var a: String? = null

            if (isLocal) {
                val retriever = MediaMetadataRetriever()
                try {
                    activity.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { fd ->
                        retriever.setDataSource(fd.fileDescriptor)
                        t = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        cover = retriever.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { bytes ->
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 720).coerceAtLeast(1) }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {} finally { retriever.release() }
            } else {
                // Online stream
                t = item.title
                a = item.artist.ifBlank { "YouTube Music" }
                val thumb = item.thumbUri
                if (!thumb.isNullOrBlank()) {
                    cover = runCatching {
                        val conn = java.net.URL(thumb).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("Connection", "keep-alive")
                        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
            }

            val extractedMood = extractAdaptiveMoodColors(cover)
            val resolvedTitle = t?.takeIf { it.isNotBlank() } ?: item.title
            val resolvedArtist = a?.takeIf { it.isNotBlank() } ?: (item.artist.takeIf { it.isNotBlank() } ?: "Unknown artist")
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

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(playerBackgroundBrush)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Top Header Bar: Down chevron | Playing from Playlist caption + name | 3-dots (Opens Information)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 10.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (dragY.value > 100.dp.toPx()) activity.exitPlayer()
                                    else dragY.animateTo(0f, MotionSpec.PressSpring)
                                }
                            },
                            onDragCancel = { scope.launch { dragY.animateTo(0f, MotionSpec.PressSpring) } },
                            onVerticalDrag = { _, dragAmount ->
                                if (dragAmount > 0f || dragY.value > 0f) {
                                    scope.launch { dragY.snapTo((dragY.value + dragAmount).coerceAtLeast(0f)) }
                                }
                            },
                        )
                    },
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
                    Text(
                        if (item.uri.startsWith("content://") || item.uri.startsWith("file://")) "LOCAL TRACKS" else "GOLD SONGS",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 3-Dots More Menu (Directly opens Information dialog as requested)
                IconButton(onClick = { panel = "Information" }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        "Track Information",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            val dragX = remember(item.uri) { Animatable(0f) }

            // 2. Large Centered Square Album Artwork Card with Spotify-style horizontal swipe
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
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Spotify Heart Favorite Button (Favorite / FavoriteBorder)
                IconButton(
                    onClick = {
                        favourite = !favourite
                        val liked = favourite
                        scope.launch(Dispatchers.IO) {
                            val dao = Store.db(activity).dao()
                            if (liked) {
                                dao.add(PlaylistEntry().apply {
                                    playlist = "Favorites"
                                    uri = item.uri
                                    this.title = title.ifBlank { item.title }
                                    added = System.currentTimeMillis()
                                })
                                val bmp = artwork
                                if (bmp != null) {
                                    TrackArtworkStore.saveBitmap(activity, item.uri, title.ifBlank { item.title }, artist, bmp, item.thumbUri ?: "")
                                } else {
                                    TrackArtworkStore.saveMeta(activity, item.uri, title.ifBlank { item.title }, artist, item.thumbUri ?: "")
                                }
                            } else {
                                dao.remove("Favorites", item.uri)
                            }
                        }
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

                // White Circular Thumb Dot (size = 12.dp)
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
                                .offset(x = 6.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
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

            // 5. Playback Controls Row: Shuffle | Prev | BIG CIRCULAR WHITE PLAY/PAUSE (64dp) | Next | Timer
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle (Green when active)
                IconButton(onClick = { service.toggleShuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        "Shuffle",
                        tint = if (shuffle) Color(0xFF1DB954) else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Skip Previous
                IconButton(onClick = { service.previous() }) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // BIG CIRCULAR WHITE PLAY/PAUSE BUTTON (64dp, Black icon)
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { service.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Skip Next
                IconButton(onClick = { service.next() }) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        "Next",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Sleep Timer (Green when active)
                IconButton(onClick = { panel = "Sleep" }) {
                    Icon(
                        Icons.Rounded.Bedtime,
                        "Sleep Timer",
                        tint = if (sleepMode != "Off") Color(0xFF1DB954) else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 6. Bottom Utilities Row: Left [Cast + Lyrics] | Right [Download + Share]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left group: Cast & Lyrics
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = { panel = "Cast" }) {
                        Icon(
                            Icons.Rounded.Cast,
                            "Cast & Output Devices",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Lyrics icon on the LEFT
                    IconButton(onClick = { panel = "Lyrics" }) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            "Lyrics",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Right group: Download & Share
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val activeDownloads by AudioDownloadManager.activeDownloads.collectAsState()
                    val isDlDone = AudioDownloadManager.isDownloaded(title, artist)
                    val dlProgress = activeDownloads[item.sourceVideoId] ?: activeDownloads.values.find { it.title == title }
                    val isDlActive = dlProgress != null && !dlProgress.isComplete && !dlProgress.isFailed

                    IconButton(onClick = {
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
                    }) {
                        if (isDlActive) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                if (isDlDone) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                "Download",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Share icon
                    IconButton(onClick = {
                        val shareLink = if (item.uri.startsWith("http")) {
                            if (item.uri.contains("googlevideo") || item.uri.contains("youtube")) {
                                "https://music.youtube.com/search?q=" + java.net.URLEncoder.encode("$title $artist", "UTF-8")
                            } else {
                                item.uri
                            }
                        } else {
                            "https://music.youtube.com/search?q=" + java.net.URLEncoder.encode("$title $artist", "UTF-8")
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "$title - $artist")
                            putExtra(Intent.EXTRA_TEXT, "$title - $artist\nListen here: $shareLink")
                        }
                        activity.startActivity(Intent.createChooser(send, "Share Song"))
                    }) {
                        Icon(
                            Icons.Rounded.Share,
                            "Share",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 7. Bottom Peeking Card: Playing Queue (Oval pill, matching dock bar)
            val currentIndex = queue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
            val nextSong = if (queue.isNotEmpty() && currentIndex in 0 until queue.lastIndex) queue[currentIndex + 1] else null
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
                        Icons.AutoMirrored.Rounded.FormatListBulleted,
                        null,
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
                // 1. Open Local Audio File
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable {
                            panel = null
                            activity.openFile.launch(arrayOf("audio/*"))
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Open Local Audio File", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Play MP3, FLAC, WAV, M4A from storage", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                    }
                }

                // 2. Track Details & Codec Info
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
                        Text("Audio Equalizer", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("10-band EQ, bass boost, spatializer presets", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
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
