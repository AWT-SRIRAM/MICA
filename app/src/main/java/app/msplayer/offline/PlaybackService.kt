package app.msplayer.offline

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle as CompatMediaStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

data class PlaybackTimeline(val position: Long = 0, val duration: Long = 0, val seekable: Boolean = false, val seeking: Boolean = false)

data class SubtitleOverlayLines(val primary: String? = null, val secondary: String? = null)

sealed class AutoSubStatus {
    object Idle : AutoSubStatus()
    data class Processing(val stage: String, val percent: Int) : AutoSubStatus()
    data class Success(val file: java.io.File?, val count: Int, val provider: String = "", val note: String? = null) : AutoSubStatus()
    data class Error(val message: String) : AutoSubStatus()
}

data class PlayerState(
    val item: MediaItem? = null,
    val closed: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val plainSubtitle: Boolean = true,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val seekable: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val rate: Float = 1f,
    val decoder: String = "Auto",
    val audioTracks: List<Pair<Int, String>> = emptyList(),
    val subtitles: List<Pair<Int, String>> = emptyList(),
    val audioTrack: Int = -1,
    val subtitleTrack: Int = -1,
    val selectedSubtitleTracks: Set<Int> = emptySet(),
    val audioDelay: Long = 0,
    val subtitleDelay: Long = 0,
    val videoCodec: String = "",
    val hasPrimaryHwSubtitle: Boolean = false,
)

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        if (keyEvent?.action != KeyEvent.ACTION_DOWN) return
        val cmd = when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "toggle"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
            KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
            else -> return
        }
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java).setAction(cmd))
        }
    }
}

class PlaybackService : MediaBrowserServiceCompat() {
    inner class LocalBinder : Binder() {
        val service
            get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    val state = MutableStateFlow(PlayerState())
    val timeline = MutableStateFlow(PlaybackTimeline())
    val activeSubtitleText = MutableStateFlow<String?>(null)
    val dualSubtitleLines = MutableStateFlow(SubtitleOverlayLines())
    val aspectMode = MutableStateFlow(PlaybackRules.AspectOption.BEST_FIT)
    var userPausedAudio: Boolean = false
    val activeDisplayFps = MutableStateFlow(60)
    val chapters = MutableStateFlow<List<ContainerDemuxer.ChapterMarker>>(emptyList())
    val subtitleStyleRevision = MutableStateFlow(0)
    val subtitleCuesRevision = MutableStateFlow(0)
    val subtitleDemuxing = MutableStateFlow(false)
    val autoSubStatus = MutableStateFlow<AutoSubStatus>(AutoSubStatus.Idle)
    val requiresAllFilesPermission = MutableStateFlow(false)
    private var parsedCues: List<PlaybackRules.SubtitleCue> = emptyList()
    private val selectedSubTracks = LinkedHashSet<Int>()
    private var userModifiedSubtitles = false
    private var initialSubRestored = false
    private var autoSubCues: List<PlaybackRules.SubtitleCue> = emptyList()
    private var embeddedCuesByTrack: Map<Int, List<PlaybackRules.SubtitleCue>> = emptyMap()
    private var embeddedTracks: Map<Int, ContainerDemuxer.EmbeddedTrack> = emptyMap()
    private var internalSpuIdsOrdered: List<Int> = emptyList()
    private data class ExternalAddRequest(
        val path: String,
        val label: String,
        val cues: List<PlaybackRules.SubtitleCue>,
        val selectExtra: Boolean,
        val knownIdsBeforeAdd: Set<Int> = emptySet(),
    )
    private val pendingExternalAdds = ArrayDeque<ExternalAddRequest>()
    private val externalSpuPathsById = LinkedHashMap<Int, String>()
    private val externalCuesBySpuId = LinkedHashMap<Int, List<PlaybackRules.SubtitleCue>>()
    private val externalLabelsBySpuId = LinkedHashMap<Int, String>()
    private var released = false
    private var controlsVisible = true
    private var subtitleControlsTop: Float? = null
    val subtitleControlsTopPx = MutableStateFlow(0f)
    fun subtitleControlsTop(top: Float) {
        if (subtitleControlsTop == top) return
        subtitleControlsTop = top
        subtitleControlsTopPx.value = top
        updateSubtitlePlacement(controlsVisible)
    }
    private val audioEffects = AudioEffects()
    val effectSettings = MutableStateFlow(AudioEffectSettings())
    private val diagnostics = PlaybackDiagnostics()

    fun effects(value: AudioEffectSettings) {
        if (released) return
        val previous = effectSettings.value
        val needVlcRebuild = ::vlc.isInitialized &&
            previous.spatializerOptions() != value.spatializerOptions()
        effectSettings.value = value
        value.save(this)
        if (needVlcRebuild && state.value.item != null) {
            rebuildVlcKeepingPosition(value)
        } else {
            audioEffects.apply(player, value)
        }
    }

    private fun rebuildVlcKeepingPosition(settings: AudioEffectSettings) {
        endTemporarySpeed()
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        if (prior.item == null) return
        opening?.cancel()
        pendingStyleRestore = prior
        pendingStyleTracks = prior
        player.setEventListener(null)
        player.stop()
        if (videoAttached) player.detachViews()
        activeDescriptor?.close()
        activeDescriptor = null
        player.release()
        vlc.release()
        vlc = createVlc(settings)
        player = MediaPlayer(vlc).apply { setUseOrientationFromBounds(true) }
        audioEffects.apply(player, settings)
        applyVolume()
        bindPlayerEvents()
        val useTexture = Store.prefs(this).getBoolean("use_texture_view", false)
        videoLayout?.takeIf { !surfaceSuspended }?.let { player.attachViews(it, null, useTexture, true) }
        openCurrent(prior.position, restore = false, keepSubtitleChoice = true)
    }

    fun diagnosticsEnabled(enabled: Boolean) {
        Store.prefs(this).edit().putBoolean("diagnostics", enabled).apply()
        diagnostics.enabled = enabled
    }

    fun exportDiagnostics(): java.io.File = diagnostics.export(this, state.value, effectSettings.value)

    fun updateSubtitlePlacement(visible: Boolean) {
        controlsVisible = visible
        val layout = videoLayout ?: return
        layout.post {
            val surface = layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles) ?: return@post
            val style = SubtitleStyle.load(this)
            val location = IntArray(2)
            surface.getLocationInWindow(location)
            val controlsLift = if (visible) subtitleControlsTop?.let { top ->
                ((location[1] - surface.translationY + surface.height - top + 8 * resources.displayMetrics.density) / surface.height.coerceAtLeast(1)).coerceIn(0f,1f)
            } ?: .28f else 0f
            val fraction = if (state.value.plainSubtitle) {
                (if (style.legacyMargin == null) style.bottomMargin / 100f else 0f) + controlsLift
            } else 0f
            // Never allow positive translationY to shift subtitles downwards off the screen/bezel
            val targetTranslationY = -(surface.height * fraction).coerceAtLeast(0f)
            surface.translationY = targetTranslationY
            surface.scaleX = 1f
            surface.scaleY = 1f
        }
    }

    lateinit var vlc: LibVLC
        private set

    lateinit var player: MediaPlayer
        private set

    private lateinit var session: MediaSessionCompat
    val mediaSessionToken: MediaSessionCompat.Token
        get() = session.sessionToken

    private lateinit var audioManager: AudioManager
    private lateinit var focus: AudioFocusRequest
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("MSPlayer", "Unhandled PlaybackService coroutine exception", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + coroutineExceptionHandler)
    private var queue = emptyList<MediaItem>()
    val queueItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val shuffleEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow("off")
    val volumeLevel = MutableStateFlow(100)
    val volumeKeyEvent = MutableStateFlow<Pair<Long, Int>?>(null)
    private var boostedVolume = 100
    private var ducked = false
    val sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerMode = MutableStateFlow("Off")
    private var sleepEndAtElapsed: Long? = null
    private var sleepStopOnEndOfTrack = false
    private var sleepFadeActive = false
    private var temporaryBaseRate: Float? = null
    private var lastTempSpeedWriteAt = 0L
    private var repeatA: Long? = null
    private var repeatB: Long? = null
    val abPoints = MutableStateFlow<Pair<Long?, Long?>>(null to null)

    fun volumePercent(): Int {
        if (boostedVolume > 100) return boostedVolume
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }
    fun setVolumePercent(value: Int): Int {
        if (released) return 0
        sleepFadeActive = false
        val target = value.coerceIn(0, 200)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, if (target >= 100) max else kotlin.math.round(target * max / 100f).toInt(), 0)
        boostedVolume = target.coerceAtLeast(100)
        applyVolume()
        if (player.volume < boostedVolume && !ducked) { boostedVolume = 100; applyVolume() }
        return volumePercent().also { volumeLevel.value = it }
    }
    private fun effectiveVolume(): Int = if (ducked) boostedVolume / 4 else boostedVolume
    private fun applyVolume() { if (!released && !sleepFadeActive) player.volume = effectiveVolume() }
    fun cancelSleepTimer() {
        sleepEndAtElapsed = null
        sleepStopOnEndOfTrack = false
        sleepFadeActive = false
        sleepTimerRemainingMs.value = null
        sleepTimerMode.value = "Off"
        if (::player.isInitialized && !released) player.volume = effectiveVolume()
    }
    fun setSleepTimer(minutes: Int?, endOfTrack: Boolean = false) {
        if (endOfTrack) {
            sleepEndAtElapsed = null
            sleepStopOnEndOfTrack = true
            sleepFadeActive = false
            sleepTimerRemainingMs.value = null
            sleepTimerMode.value = "End of track"
            if (::player.isInitialized && !released) player.volume = effectiveVolume()
        } else if (minutes != null && minutes > 0) {
            val totalMs = minutes * 60_000L
            sleepStopOnEndOfTrack = false
            sleepFadeActive = false
            sleepEndAtElapsed = SystemClock.elapsedRealtime() + totalMs
            sleepTimerRemainingMs.value = totalMs
            sleepTimerMode.value = "${minutes}m"
            if (::player.isInitialized && !released) player.volume = effectiveVolume()
        } else {
            cancelSleepTimer()
        }
    }
    fun seekToChapter(chapterIndex: Int): Boolean {
        val marker = chapters.value.getOrNull(chapterIndex) ?: return false
        return seek(marker.startMs, fast = false)
    }
    fun nextChapter(): Boolean {
        val list = chapters.value
        if (list.isEmpty()) return false
        val pos = timeline.value.position
        val next = list.firstOrNull { it.startMs > pos + 1500L } ?: return false
        return seek(next.startMs, fast = false)
    }
    fun previousChapter(): Boolean {
        val list = chapters.value
        if (list.isEmpty()) return false
        val pos = timeline.value.position
        val prev = list.lastOrNull { it.startMs < pos - 3000L } ?: list.first()
        return seek(prev.startMs, fast = false)
    }
    fun volumeKey(up: Boolean) {
        val current = volumePercent()
        val updated = if (current > 100 || (up && current >= 100)) {
            setVolumePercent((current + if (up) 5 else -5).coerceIn(0, 200))
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            volumePercent().also { volumeLevel.value = it }
        }
        volumeKeyEvent.value = SystemClock.elapsedRealtime() to updated
    }
    private val routeCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out android.media.AudioDeviceInfo>) { resetBoost() }
        override fun onAudioDevicesRemoved(devices: Array<out android.media.AudioDeviceInfo>) { resetBoost() }
    }
    private fun resetBoost() { boostedVolume = 100; sleepFadeActive = false; if (::player.isInitialized && !released) { player.volume = effectiveVolume(); volumeLevel.value = volumePercent() } }
    fun beginTemporarySpeed(): Boolean {
        if (released || !player.isPlaying) return false
        if (temporaryBaseRate == null) temporaryBaseRate = player.rate
        lastTempSpeedWriteAt = 0L
        return true
    }
    fun temporarySpeed(value: Float): Float {
        if (temporaryBaseRate == null || released) return state.value.rate
        val target = value.coerceIn(.25f, 4f)
        val delta = kotlin.math.abs(player.rate - target)
        val now = SystemClock.elapsedRealtime()
        if (delta >= 0.04f && (now - lastTempSpeedWriteAt >= 45L || delta >= 0.24f)) {
            player.rate = target
            lastTempSpeedWriteAt = now
        }
        if (kotlin.math.abs(state.value.rate - target) > 0.005f) {
            state.value = state.value.copy(rate = target)
        }
        return state.value.rate
    }
    fun endTemporarySpeed() {
        val base = temporaryBaseRate ?: return
        temporaryBaseRate = null
        lastTempSpeedWriteAt = 0L
        if (!released && kotlin.math.abs(player.rate - base) > 0.01f) {
            player.rate = base
            state.value = state.value.copy(rate = player.rate)
        }
    }
    fun setRepeat(value: String) {
        repeatMode.value = value
        Store.prefs(this).edit().putString("repeat", value).apply()
    }
    fun toggleRepeat() {
        val next = when (repeatMode.value) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        setRepeat(next)
        updateSession(forceState = true)
        updateNotification()
    }
    private val playedShuffleHistory = mutableSetOf<Int>()
    fun toggleShuffle() {
        shuffleEnabled.value = !shuffleEnabled.value
        playedShuffleHistory.clear()
        playedShuffleHistory.add(index)
        session.setShuffleMode(if (shuffleEnabled.value) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        updateSession(forceState = true)
        updateDynamicIsland()
        updateNotification()
    }
    val autoQueueActive = MutableStateFlow(false)
    val autoQueueLoading = MutableStateFlow(false)
    val autoQueueSeedTitle = MutableStateFlow<String?>(null)
    private var autoQueueJob: Job? = null
    private var preResolveJob: Job? = null
    private var lastAutoQueueSeedKey: String? = null

    private fun isLazyOnlineUri(uri: String): Boolean =
        uri.startsWith("yt_") || uri.startsWith("saavn_") || uri.startsWith("resolve://")

    private fun extractVideoId(item: MediaItem): String =
        item.sourceVideoId.ifBlank {
            when {
                item.uri.startsWith("yt_") -> item.uri.removePrefix("yt_")
                item.uri.startsWith("resolve://yt/") -> item.uri.removePrefix("resolve://yt/")
                item.uri.startsWith("saavn_") -> item.uri
                else -> ""
            }
        }

    companion object {
        @Volatile var isServiceRunning = false

        fun normalizeSongTitle(title: String): String {
            if (title.isBlank()) return ""
            var t = title.lowercase()
            // 1. Remove bracketed / parenthetical expressions
            t = t.replace(Regex("""\s*[\(\[][^()\[\]]*[\)\]]"""), " ")
            // 2. Remove common video/audio suffixes
            val suffixes = listOf(
                "- official video", "- official music video", "- official audio",
                "- video song", "- full song", "- lyric video", "- lyrical video",
                "- audio song", "- hd video", "- 4k", "- from "
            )
            for (s in suffixes) {
                val idx = t.indexOf(s)
                if (idx != -1) t = t.substring(0, idx)
            }
            // 3. Remove non-alphanumeric chars
            t = t.replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
            return t
        }

        fun normalizeArtistName(artist: String): String {
            if (artist.isBlank()) return ""
            var a = artist.lowercase()
            val delimiters = listOf(",", "&", "feat.", "ft.", "feat", "ft", "|", "/")
            for (d in delimiters) {
                val idx = a.indexOf(d)
                if (idx != -1) a = a.substring(0, idx)
            }
            return a.replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        }

        fun isDuplicateTrack(titleA: String, artistA: String, titleB: String, artistB: String): Boolean {
            val normA = normalizeSongTitle(titleA)
            val normB = normalizeSongTitle(titleB)
            if (normA.isNotBlank() && normA == normB) return true
            if (normA.length >= 4 && normB.length >= 4) {
                if (normA.contains(normB) || normB.contains(normA)) {
                    val artA = normalizeArtistName(artistA)
                    val artB = normalizeArtistName(artistB)
                    if (artA.isBlank() || artB.isBlank() || artA == artB || artA.contains(artB) || artB.contains(artA)) {
                        return true
                    }
                }
            }
            return false
        }
    }

    fun appendToQueue(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val currentSeed = queue.getOrNull(index)
        val existingNormalized = queue.map { normalizeSongTitle(it.title) to normalizeArtistName(it.artist) }.toMutableList()
        val existingUris = queue.map { it.uri }.toMutableSet()
        val existingVids = queue.mapNotNull { it.sourceVideoId.takeIf { v -> v.isNotBlank() } }.toMutableSet()

        val toAdd = mutableListOf<MediaItem>()
        for (candidate in items) {
            val normTitle = normalizeSongTitle(candidate.title)
            val normArtist = normalizeArtistName(candidate.artist)
            val vid = candidate.sourceVideoId

            if (currentSeed != null && isDuplicateTrack(currentSeed.title, currentSeed.artist, candidate.title, candidate.artist)) {
                continue
            }
            if (candidate.uri in existingUris || (vid.isNotBlank() && vid in existingVids)) {
                continue
            }
            val isDup = existingNormalized.any { (exTitle, exArtist) ->
                isDuplicateTrack(exTitle, exArtist, normTitle, normArtist)
            }
            if (!isDup) {
                existingNormalized.add(normTitle to normArtist)
                existingUris.add(candidate.uri)
                if (vid.isNotBlank()) existingVids.add(vid)
                toAdd.add(candidate)
            }
        }
        if (toAdd.isNotEmpty()) {
            queue = queue + toAdd
            queueItems.value = queue
            autoQueueActive.value = true
            preResolveNextTrack()
        }
    }

    fun triggerAutoQueueIfNeeded(seed: MediaItem) {
        if (!seed.audio) return
        val isOnline = PlaybackRules.isStreamUrl(seed.uri) || isLazyOnlineUri(seed.uri) || seed.sourceVideoId.isNotBlank()
        if (!isOnline) return
        val remaining = (queue.size - 1 - index).coerceAtLeast(0)
        if (remaining >= 3) return
        val seedKey = "${seed.title.lowercase().trim()}::${seed.artist.lowercase().trim()}::${extractVideoId(seed)}"
        if (lastAutoQueueSeedKey == seedKey && autoQueueLoading.value) return
        lastAutoQueueSeedKey = seedKey
        autoQueueJob?.cancel()
        autoQueueLoading.value = true
        autoQueueSeedTitle.value = seed.title
        autoQueueJob = scope.launch(Dispatchers.IO) {
            try {
                val vid = extractVideoId(seed)
                val similarTracks = if (vid.startsWith("saavn_")) {
                    val jioRecs = JioSaavnClient.fetchSimilarSongs(vid, seed.title, seed.artist)
                    if (jioRecs.size >= 5) jioRecs
                    else (jioRecs + YtMusicClient.fetchUpNextRadio("", seed.title, seed.artist))
                        .distinctBy { it.title.lowercase().trim() }
                } else {
                    val ytRecs = YtMusicClient.fetchUpNextRadio(vid, seed.title, seed.artist)
                    if (ytRecs.size >= 5) ytRecs
                    else (ytRecs + JioSaavnClient.fetchSimilarSongs("", seed.title, seed.artist))
                        .distinctBy { it.title.lowercase().trim() }
                }

                val mediaItems = similarTracks.mapNotNull { t ->
                    if (isDuplicateTrack(seed.title, seed.artist, t.title, t.artist)) return@mapNotNull null
                    val lazyUri = t.directStreamUrl.takeIf { it.startsWith("http") }
                        ?: if (t.videoId.startsWith("saavn_")) t.videoId else "yt_${t.videoId}"
                    MediaItem(
                        uri = lazyUri,
                        title = t.title,
                        artist = t.artist,
                        audio = true,
                        thumbUri = t.thumbUrl,
                        sourceVideoId = t.videoId,
                    )
                }
                withContext(Dispatchers.Main) {
                    if (mediaItems.isNotEmpty()) {
                        appendToQueue(mediaItems)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    autoQueueLoading.value = false
                }
            }
        }
    }

    private fun preResolveNextTrack() {
        val nextIdx = index + 1
        val nextItem = queue.getOrNull(nextIdx) ?: return
        if (!nextItem.audio || !isLazyOnlineUri(nextItem.uri)) return
        preResolveJob?.cancel()
        preResolveJob = scope.launch(Dispatchers.IO) {
            try {
                val vid = extractVideoId(nextItem)
                val resolved = AudioStreamResolver.resolveBestStream(
                    title = nextItem.title,
                    artist = nextItem.artist,
                    ytVideoId = vid,
                    rawThumbUrl = nextItem.thumbUri ?: "",
                    directStreamUrl = "",
                    context = this@PlaybackService,
                )
                if (resolved != null && resolved.streamUrl.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        val currentAtIdx = queue.getOrNull(nextIdx)
                        if (currentAtIdx != null && currentAtIdx.uri == nextItem.uri) {
                            val updated = currentAtIdx.copy(
                                uri = resolved.streamUrl,
                                thumbUri = resolved.hdThumbUrl.ifBlank { currentAtIdx.thumbUri ?: "" },
                                streamUserAgent = resolved.userAgent,
                            )
                            val mutable = queue.toMutableList()
                            mutable[nextIdx] = updated
                            queue = mutable
                            queueItems.value = queue
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun playQueueItem(item: MediaItem) {
        val next = queue.indexOfFirst {
            it.uri == item.uri || (item.sourceVideoId.isNotBlank() && it.sourceVideoId == item.sourceVideoId)
        }
        if (next >= 0) { savePosition(); index = next; retried = false; openCurrent(null) }
    }
    fun markAB() {
        val time = player.time.coerceAtLeast(0)
        when {
            repeatA == null -> repeatA = time
            repeatB == null && time > repeatA!! + 500 -> repeatB = time
            else -> { repeatA = null; repeatB = null }
        }
        abPoints.value = repeatA to repeatB
    }
    val queueIndex = MutableStateFlow(0)
    private var index = 0
        set(value) {
            field = value
            queueIndex.value = value
        }
    private var retried = false
    var finished = false
    private var decoderMode = "Auto"
    private var metadataDimensions: Pair<Int, Int>? = null
    private var resumeAnchor: Long? = null
    private var seekTarget: Long? = null
    private var seekStarted = 0L
    private var seekOrigin = 0L
    private var seekCorrection: Job? = null
    private var dimensionProbe: Job? = null
    private var trackPlayStartTime = 0L

    private fun clearSeek() { seekTarget = null; seekStarted = 0L; seekCorrection?.cancel(); seekCorrection = null }
    private fun nativePosition(): Long {
        val time = player.time.coerceAtLeast(0)
        val target = seekTarget
        if (target != null) {
            val elapsed = SystemClock.elapsedRealtime() - seekStarted
            val settledByTime = elapsed > 150 && (kotlin.math.abs(time - seekOrigin) > 500 || kotlin.math.abs(time - target) <= 1500)
            val maxWaitMs = if (player.isPlaying) 1800L else 4000L
            if (settledByTime || elapsed > maxWaitMs) {
                diagnostics.event("seek-settled target=$target actual=$time elapsedMs=$elapsed")
                clearSeek()
            } else return target
        }
        return time.let { if (it > 0) { resumeAnchor = null; it } else resumeAnchor ?: it }
    }
    private var focusHeld = false
    private var sessionMediaKey: String? = null
    private var sessionTitle: String? = null
    private var sessionArtist: String? = null
    private var sessionDuration = -1L
    private var sessionArtKey: String? = null
    private var sessionArtIdentity: String = "none"
    private var sessionArtworkBitmap: Bitmap? = null
    private var sessionArtworkColor: Int = android.graphics.Color.rgb(12, 24, 42)
    private var artworkJob: Job? = null
    private var notifSyncJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var lastIslandTickAt = 0L
    private var currentTrackLiked = false
    val isCurrentTrackLiked = MutableStateFlow(false)
    private var likedTrackUri: String? = null
    private var appInForeground = true
    private lateinit var dynamicIsland: DynamicIslandController
    private var sessionStatus = -1
    private var sessionRate = -1f
    private var sessionSentAt = 0L
    private var sessionPosition = -1L
    private var resumeOnFocus = false
    private var ticker: Job? = null
    private var opening: Job? = null
    private var demuxJob: Job? = null
    private var currentContainerInfo = ContainerDemuxer.ContainerInfo()
    private var activeDescriptor: android.os.ParcelFileDescriptor? = null
    private var videoAttached = false
    private var requestedPause = false
    private var pendingPlayback = false
    private var videoLayout: org.videolan.libvlc.util.VLCVideoLayout? = null
    private var pendingStyleRestore: PlayerState? = null
    private var pendingStyleTracks: PlayerState? = null
    private val externalSubtitles = mutableListOf<String>()

    private var surfaceSuspended = false
    private var surfaceHolderCallback: android.view.SurfaceHolder.Callback? = null
    private var pendingSurfaceView: android.view.SurfaceView? = null

    private fun clearSurfaceCallback() {
        val cb = surfaceHolderCallback
        val sv = pendingSurfaceView
        if (cb != null && sv != null) {
            runCatching { sv.holder?.removeCallback(cb) }
        }
        surfaceHolderCallback = null
        pendingSurfaceView = null
    }

    /** The app left the screen: release the video surface while audio keeps playing. */
    fun suspendVideoSurface() {
        if (released || !videoAttached || surfaceSuspended) return
        clearSurfaceCallback()
        surfaceSuspended = true
        player.detachViews()
    }

    /** The app is visible again: wait for a valid Surface if needed and attach cleanly so video never stays black. */
    fun resumeVideoSurface() {
        if (released || !surfaceSuspended) return
        surfaceSuspended = false
        clearSurfaceCallback()
        val layout = videoLayout ?: return
        val videoSurface = layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_video)

        fun performAttachAndRedraw() {
            if (released || surfaceSuspended) return
            val useTexture = Store.prefs(this@PlaybackService).getBoolean("use_texture_view", false)
            layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles)?.apply {
                setZOrderMediaOverlay(true)
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            }
            player.attachViews(layout, null, useTexture, true)
            videoAttached = true
            layout.post {
                if (!released && !surfaceSuspended) {
                    setAspectMode(aspectMode.value, persistFolder = false)
                    updateSubtitlePlacement(controlsVisible)
                    val resumePos = nativePosition()
                    if (player.length > 0 && player.isSeekable && !player.isPlaying) {
                        player.setTime(resumePos, false)
                    }
                }
            }
            scope.launch {
                delay(240)
                if (!released && !surfaceSuspended && videoAttached && state.value.item?.audio == false) {
                    if (player.length > 0 && player.isSeekable && !player.isPlaying) {
                        player.setTime(nativePosition(), false)
                    }
                    setAspectMode(aspectMode.value, persistFolder = false)
                    updateSubtitlePlacement(controlsVisible)
                }
            }
        }

        val holder = videoSurface?.holder
        val cb = object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                layout.post { performAttachAndRedraw() }
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    runCatching {
                        player.vlcVout.setWindowSize(width, height)
                        enforceCenterGravity(layout)
                    }
                }
            }
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
        }
        pendingSurfaceView = videoSurface
        surfaceHolderCallback = cb
        holder?.addCallback(cb)
        if (holder?.surface?.isValid == true) {
            performAttachAndRedraw()
        }
    }

    fun enforceCenterGravity(layout: org.videolan.libvlc.util.VLCVideoLayout?) {
        val l = layout ?: return
        try {
            l.foregroundGravity = android.view.Gravity.CENTER
            val frame = l.findViewById<android.widget.FrameLayout>(org.videolan.R.id.player_surface_frame)
            frame?.let { f ->
                val lp = f.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    if (lp.gravity != android.view.Gravity.CENTER) {
                        lp.gravity = android.view.Gravity.CENTER
                        f.layoutParams = lp
                    }
                } else {
                    f.layoutParams = android.widget.FrameLayout.LayoutParams(
                        f.layoutParams?.width ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        f.layoutParams?.height ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER,
                    )
                }
            }
            val videoSurface = l.findViewById<android.view.View>(org.videolan.R.id.surface_video)
                ?: l.findViewById<android.view.View>(org.videolan.R.id.texture_video)
            videoSurface?.let { v ->
                val lp = v.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    if (lp.gravity != android.view.Gravity.CENTER) {
                        lp.gravity = android.view.Gravity.CENTER
                        v.layoutParams = lp
                    }
                } else {
                    v.layoutParams = android.widget.FrameLayout.LayoutParams(
                        v.layoutParams?.width ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        v.layoutParams?.height ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER,
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun applyHighRefreshPresentation(layout: org.videolan.libvlc.util.VLCVideoLayout?) {
        val l = layout ?: return
        val videoSurface = l.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_video)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && videoSurface != null) {
            runCatching {
                val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
                val maxRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val d = runCatching { display }.getOrNull() ?: @Suppress("DEPRECATION") wm?.defaultDisplay
                    d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120.0f
                } else {
                    120.0f
                }
                val targetFps = if (maxRate >= 118.0f) maxRate else 60.0f
                activeDisplayFps.value = targetFps.toInt()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    videoSurface.holder.surface?.setFrameRate(
                        targetFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        android.view.Surface.CHANGE_FRAME_RATE_ALWAYS,
                    )
                } else {
                    videoSurface.holder.surface?.setFrameRate(
                        targetFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    )
                }
            }
        }
    }

    fun refreshVideoLayoutAfterPip() {
        val layout = videoLayout ?: return
        if (released || surfaceSuspended) return
        val doRefresh = Runnable {
            if (!released && !surfaceSuspended) {
                player.setUseOrientationFromBounds(true)
                if (layout.width > 0 && layout.height > 0) {
                    runCatching { player.vlcVout.setWindowSize(layout.width, layout.height) }
                }
                runCatching { player.updateVideoSurfaces() }
                enforceCenterGravity(layout)
                setAspectMode(aspectMode.value, persistFolder = false)
                updateSubtitlePlacement(controlsVisible)
                layout.requestLayout()
            }
        }
        layout.post(doRefresh)
        layout.postDelayed(doRefresh, 60L)
        layout.postDelayed(doRefresh, 150L)
        layout.postDelayed(doRefresh, 300L)
        layout.postDelayed(doRefresh, 600L)
    }

    fun attachVideo(layout: org.videolan.libvlc.util.VLCVideoLayout) {
        if (released) return
        videoLayout = layout
        surfaceSuspended = false
        player.setUseOrientationFromBounds(true)
        val useTexture = Store.prefs(this).getBoolean("use_texture_view", false)
        player.attachViews(layout, null, useTexture, true)
        enforceCenterGravity(layout)
        applyHighRefreshPresentation(layout)
        layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles)?.apply {
            setZOrderMediaOverlay(true)
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePlacement(controlsVisible) }
        }
        videoAttached = true
        layout.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (right - left > 0 && bottom - top > 0) {
                    player.setUseOrientationFromBounds(true)
                    runCatching { player.vlcVout.setWindowSize(right - left, bottom - top) }
                    runCatching { player.updateVideoSurfaces() }
                }
            }
            enforceCenterGravity(layout)
            updateSubtitlePlacement(controlsVisible)
        }
        setAspectMode(aspectMode.value, persistFolder = false)
        updateSubtitlePlacement(controlsVisible)
        refreshVideoLayoutAfterPip()
        if (pendingPlayback) {
            pendingPlayback = false
            if (requestFocus()) player.play()
        }
    }

    fun detachVideo(layout: org.videolan.libvlc.util.VLCVideoLayout? = null) {
        if (layout != null && videoLayout != null && videoLayout !== layout) return
        clearSurfaceCallback()
        videoAttached = false
        surfaceSuspended = false
        Library.videoPlaybackActive.value = false
        videoLayout = null
        if (!released) player.detachViews()
    }

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pause()
            }
        }
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (released) return@OnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocus = false
                    pause()
                    focusHeld = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocus = player.isPlaying
                    pause(false)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; endTemporarySpeed(); applyVolume() }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    ducked = false
                    applyVolume()
                    if (resumeOnFocus) {
                        resumeOnFocus = false
                        play()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        val nm = getSystemService(NotificationManager::class.java)
        val playbackChannel = NotificationChannel("playback", "Media Playback", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(playbackChannel)
        // Always clear any legacy second capsule notification so only one clean media card shows
        nm.cancel(11)
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { nm.deleteNotificationChannel("live_island") }
        }
        dynamicIsland = DynamicIslandController(
            this,
            object : DynamicIslandController.Callbacks {
                override fun onPlayPause() = toggle()
                override fun onNext() = next()
                override fun onPrevious() = previous()
                override fun onSeekTo(positionMs: Long) { seek(positionMs) }
                override fun onToggleLike() = toggleCurrentTrackLike()
                override fun onToggleShuffle() {
                    toggleShuffle()
                    updateSession(forceState = true)
                    updateDynamicIsland()
                }
                override fun onOpenApp() {
                    runCatching {
                        startActivity(
                            Intent(this@PlaybackService, MainActivity::class.java)
                                .putExtra("show_player", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                }
            },
        )
        val initialEffects = AudioEffectSettings.load(this)
        effectSettings.value = initialEffects
        vlc = createVlc(initialEffects)
        player = MediaPlayer(vlc).apply { setUseOrientationFromBounds(true) }
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(routeCallback, Handler(Looper.getMainLooper()))
        repeatMode.value = Store.prefs(this).getString("repeat", "off") ?: "off"
        userPausedAudio = Store.prefs(this).getBoolean("user_paused_audio", false)
        val mediaAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAudioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        val mbrComponent = ComponentName(this, MediaButtonReceiver::class.java)
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            99,
            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mbrComponent),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSessionCompat(this, "Mica One", mbrComponent, mediaButtonPendingIntent)
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        setSessionToken(session.sessionToken)
        val openActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra("show_player", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session.setSessionActivity(openActivityIntent)
        session.setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()

                override fun onPause() = pause()

                override fun onStop() = stopPlayback()

                override fun onSeekTo(pos: Long) { seek(pos) }

                override fun onSkipToNext() = next()

                override fun onSkipToPrevious() = previous()

                override fun onSetShuffleMode(shuffleMode: Int) {
                    val enabled = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
                    shuffleEnabled.value = enabled
                    session.setShuffleMode(if (enabled) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
                    updateSession(forceState = true)
                    updateDynamicIsland()
                    updateNotification()
                }

                override fun onSetRepeatMode(newRepeatMode: Int) {
                    val modeStr = when (newRepeatMode) {
                        PlaybackStateCompat.REPEAT_MODE_ONE -> "one"
                        PlaybackStateCompat.REPEAT_MODE_ALL -> "all"
                        else -> "off"
                    }
                    repeatMode.value = modeStr
                    session.setRepeatMode(newRepeatMode)
                    updateSession(forceState = true)
                    updateDynamicIsland()
                    updateNotification()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        "toggle_like" -> toggleCurrentTrackLike()
                        "toggle_shuffle", "toggle_repeat" -> {
                            val nextRepeat = !shuffleEnabled.value
                            toggleShuffle()
                            if (nextRepeat) {
                                setRepeat("all")
                            } else {
                                setRepeat("off")
                            }
                            updateSession(forceState = true)
                            scheduleMediaNotificationSync()
                        }
                    }
                }
            }
        )
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED,
            )
        else
            @Suppress("DEPRECATION")
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        bindPlayerEvents()
        audioEffects.apply(player, initialEffects)
        diagnostics.enabled = Store.prefs(this).getBoolean("diagnostics", false)
        ticker =
            scope.launch {
                var lastTimelineAt = 0L
                var lastDiagAt = 0L
                var lastSaveAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    val hasSubOverlay = (selectedSubTracks.isNotEmpty() || parsedCues.isNotEmpty() || legacyFolderCues.isNotEmpty())
                    delay(if (hasSubOverlay && player.isPlaying) 16L else 48L)
                    val now = SystemClock.elapsedRealtime()
                    val sleepEndAt = sleepEndAtElapsed
                    if (sleepEndAt != null) {
                        val rem = sleepEndAt - now
                        if (rem <= 0L) {
                            cancelSleepTimer()
                            if (player.isPlaying) pause()
                        } else {
                            if (rem <= 5000L && player.isPlaying) {
                                sleepFadeActive = true
                                player.volume = kotlin.math.round(effectiveVolume() * (rem / 5000f)).toInt().coerceAtLeast(0)
                            }
                            if (now - lastTimelineAt >= 450L) {
                                sleepTimerRemainingMs.value = rem
                            }
                        }
                    }
                    if (state.value.item != null && !finished) {
                        val posNow = nativePosition()
                        // A-B repeat is checked on every tick, not the slower timeline update.
                        if (player.isPlaying) PlaybackRules.abLoopTarget(posNow, repeatA, repeatB)?.let { seek(it, false) }
                        if (hasSubOverlay) {
                            updateSubtitleTextAt(posNow)
                        } else if (activeSubtitleText.value != null || dualSubtitleLines.value.primary != null || dualSubtitleLines.value.secondary != null) {
                            activeSubtitleText.value = null
                            dualSubtitleLines.value = SubtitleOverlayLines()
                        }
                        if (now - lastTimelineAt >= 480L) {
                            lastTimelineAt = now
                            if (state.value.videoWidth == 0 && state.value.item?.audio == false) {
                                player.currentVideoTrack?.let { video ->
                                    val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
                                    state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                                }
                            }
                            state.value =
                                state.value.copy(
                                    position = posNow,
                                    duration = player.length.coerceAtLeast(0),
                                    seekable = player.isSeekable,
                                )
                            timeline.value = PlaybackTimeline(state.value.position, state.value.duration, state.value.seekable, seekTarget != null)
                            Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
                            updateSession()
                            if (!appInForeground && state.value.item?.audio == true && now - lastIslandTickAt >= 500L) {
                                lastIslandTickAt = now
                                updateDynamicIsland()
                            }
                        }
                        if (now - lastDiagAt >= 1000L) {
                            lastDiagAt = now
                            diagnostics.sample(player, state.value)
                        }
                        if (now - lastSaveAt >= 10000L) {
                            lastSaveAt = now
                            savePosition()
                        }
                    }
                }
            }
    }

    private fun createVlc(eff: AudioEffectSettings = effectSettings.value): LibVLC = LibVLC(
        this,
        arrayListOf(
            "--no-video-title-show",
            "--audio-time-stretch",
            "--aout=android_audiotrack",
            "--file-caching=2000",
            "--network-caching=2000",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--codec=mediacodec_ndk,mediacodec_jni,all",
            "--avcodec-threads=0",
            "--avcodec-fast",
            // Native surfaces keep text subtitles independently positionable.
            // GLES remains available for projections the Android output cannot handle.
            "--vout=android-display,android-opaque,gles2",
            "--avcodec-skiploopfilter=0",
        ).apply {
            addAll(SubtitleStyle.load(this@PlaybackService).options())
            addAll(eff.spatializerOptions())
            if (BuildConfig.DEBUG && Store.prefs(this@PlaybackService).getBoolean("native_verbose", false)) add("--verbose=2")
        },
    )

    private fun bindPlayerEvents() {
        player.setEventListener { event ->
            if (released) return@setEventListener
            if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.Buffering)
                diagnostics.event("event=${event.type} buffering=${event.buffering} mode=$decoderMode")
            if (
                event.type in
                    listOf(
                        MediaPlayer.Event.Playing,
                        MediaPlayer.Event.Paused,
                        MediaPlayer.Event.EncounteredError,
                        MediaPlayer.Event.Opening,
                    )
            )
                android.util.Log.i("MSPlayer", "Playback event ${event.type}")
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    state.value = state.value.copy(buffering = true, error = null)
                    updateSession(forceMetadata = true, forceState = true)
                    updateNotification()
                    scheduleMediaNotificationSync()
                }
                MediaPlayer.Event.LengthChanged -> {
                    val len = event.lengthChanged.coerceAtLeast(0L)
                    if (len > 0L && len != state.value.duration) {
                        state.value = state.value.copy(duration = len)
                        timeline.value = timeline.value.copy(duration = len)
                        updateSession(forceMetadata = true)
                        updateNotification()
                    }
                }
                MediaPlayer.Event.Playing -> {
                    bufferingWatchdog?.cancel()
                    Library.videoPlaybackActive.value = state.value.item?.audio == false
                    val realLen = player.length.takeIf { it > 0L } ?: state.value.duration
                    state.value = state.value.copy(playing = true, buffering = false, error = null, duration = realLen)
                    refreshTracks()
                    player.currentVideoTrack?.let { video ->
                        val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
                        state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                    }
                    setAspectMode(aspectMode.value, persistFolder = false)
                    updateSubtitlePlacement(controlsVisible)
                    pendingStyleRestore?.let { prior ->
                        pendingStyleRestore = null
                        val savedExternals = externalSubtitles.toList()
                        externalSubtitles.clear()
                        savedExternals.forEach { path -> addSubtitle(path, selectExtra = false) }
                        refreshTracks(false)
                        player.rate = prior.rate
                        player.setAudioDelay(prior.audioDelay * 1000)
                        player.setSpuDelay(prior.subtitleDelay * 1000)
                        state.value = state.value.copy(rate = prior.rate, audioDelay = prior.audioDelay, subtitleDelay = prior.subtitleDelay)
                        if (!prior.playing) pause(false)
                    }
                    updateSession(forceMetadata = true, forceState = true)
                    updateNotification()
                    scheduleMediaNotificationSync()
                }
                MediaPlayer.Event.Paused -> {
                    Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
                    state.value = state.value.copy(playing = false, buffering = false)
                    updateSession(forceState = true)
                    updateNotification()
                }
                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted ->
                    scope.launch {
                        delay(150)
                        refreshTracks(false)
                    }
                MediaPlayer.Event.Vout ->
                    if (event.voutCount > 0 && !surfaceSuspended) {
                        videoLayout?.post {
                            if (!released && !surfaceSuspended) {
                                player.setUseOrientationFromBounds(true)
                                videoLayout?.let { vl ->
                                    if (vl.width > 0 && vl.height > 0) {
                                        runCatching { player.vlcVout.setWindowSize(vl.width, vl.height) }
                                    }
                                }
                                runCatching { player.updateVideoSurfaces() }
                                enforceCenterGravity(videoLayout)
                                setAspectMode(aspectMode.value, persistFolder = false)
                                updateSubtitlePlacement(controlsVisible)
                            }
                        }
                        videoLayout?.postDelayed({
                            if (!released && !surfaceSuspended) {
                                player.setUseOrientationFromBounds(true)
                                videoLayout?.let { vl ->
                                    if (vl.width > 0 && vl.height > 0) {
                                        runCatching { player.vlcVout.setWindowSize(vl.width, vl.height) }
                                    }
                                }
                                runCatching { player.updateVideoSurfaces() }
                                enforceCenterGravity(videoLayout)
                                setAspectMode(aspectMode.value, persistFolder = false)
                                updateSubtitlePlacement(controlsVisible)
                            }
                        }, 150L)
                    }
                MediaPlayer.Event.Buffering ->
                    if (!requestedPause) state.value = state.value.copy(buffering = event.buffering < 100f)
                MediaPlayer.Event.TimeChanged ->
                    if (!finished && (selectedSubTracks.isNotEmpty() || parsedCues.isNotEmpty() || legacyFolderCues.isNotEmpty())) {
                        updateSubtitleTextAt(nativePosition())
                    }
                MediaPlayer.Event.EndReached -> {
                    val elapsedPlaybackMs = SystemClock.elapsedRealtime() - trackPlayStartTime
                    val isImmediateFailure = elapsedPlaybackMs in 1..1800L && state.value.position <= 2500L
                    if (isImmediateFailure) {
                        android.util.Log.w("MSPlayer", "EndReached within ${elapsedPlaybackMs}ms - stream load failure!")
                        val currentItem = state.value.item
                        val isStream = currentItem != null && (PlaybackRules.isStreamUrl(currentItem.uri) || isLazyOnlineUri(currentItem.uri))
                        if (isStream && !retried) {
                            retried = true
                            handleOnlineAudioFallback(currentItem)
                            return@setEventListener
                        }
                        // Stop playback gracefully to prevent infinite looping at video end
                        state.value = state.value.copy(playing = false, buffering = false)
                        timeline.value = PlaybackTimeline(0L, state.value.duration, state.value.seekable)
                        updateNotification()
                        return@setEventListener
                    }
                    Library.videoPlaybackActive.value = false
                    finished = true
                    savePosition(true)
                    endTemporarySpeed()
                    if (sleepStopOnEndOfTrack) {
                        cancelSleepTimer()
                        state.value =
                            state.value.copy(
                                playing = false,
                                position = state.value.duration,
                            )
                        timeline.value = PlaybackTimeline(state.value.duration, state.value.duration, state.value.seekable)
                        updateNotification()
                        abandonFocus()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        return@setEventListener
                    }
                    if (shuffleEnabled.value && queue.size > 1 && repeatMode.value != "one") { next(); return@setEventListener }
                    when (Store.prefs(this).getString("repeat", "off")) {
                        "one" -> {
                            val prior = state.value.copy(playing = true)
                            pendingStyleRestore = prior
                            pendingStyleTracks = prior
                            openCurrent(0, restore = false, keepSubtitleChoice = true)
                        }
                        "all" -> {
                            index = (index + 1) % queue.size.coerceAtLeast(1)
                            openCurrent(0)
                        }
                        else ->
                            if (index + 1 < queue.size) {
                                index++
                                openCurrent(0)
                            } else {
                                state.value =
                                    state.value.copy(
                                        playing = false,
                                        position = state.value.duration,
                                    )
                                timeline.value = PlaybackTimeline(state.value.duration, state.value.duration, state.value.seekable)
                                updateNotification()
                                abandonFocus()
                                stopForeground(STOP_FOREGROUND_DETACH)
                            }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    val currentItem = state.value.item
                    val isOnlineAudio = currentItem?.audio == true && (PlaybackRules.isStreamUrl(currentItem.uri) || isLazyOnlineUri(currentItem.uri))
                    if (isOnlineAudio && !retried) {
                        handleOnlineAudioFallback(currentItem)
                        return@setEventListener
                    }
                    val is4kOrLarge = state.value.videoWidth >= 3840 || state.value.videoHeight >= 2160 || (state.value.item?.size ?: 0L) > 2L * 1024 * 1024 * 1024
                    if (!retried && decoderMode != "Software" && !is4kOrLarge && !isOnlineAudio) {
                        retried = true
                        decoderMode = "Software"
                        openCurrent(state.value.position, false)
                    } else if (isOnlineAudio && index + 1 < queue.size) {
                        next()
                    } else {
                        decoderMode = Store.prefs(this).getString("decoder", "Auto") ?: "Auto"
                        Library.videoPlaybackActive.value = false
                        clearSeek()
                        val itemUri = state.value.item?.uri ?: ""
                        state.value =
                            state.value.copy(
                                error = when {
                                    PlaybackRules.isStreamUrl(itemUri) ->
                                        "Unable to play stream URL. Verify it is a direct media or manifest link (.mp4, .mkv, .m3u8, .mpd, rtsp://)."
                                    PlaybackRules.temporaryFile(state.value.item?.title ?: "") ->
                                        "This temporary download is incomplete or unsupported. Refresh and reopen after the download finishes."
                                    else -> "Unable to play this file. Check file access and format support."
                                },
                                playing = false,
                                buffering = false,
                            )
                        abandonFocus()
                        updateNotification()
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return binder
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot = BrowserRoot("msplayer_root", null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.sendResult(mutableListOf())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play" -> play()
            "pause" -> pause()
            "toggle" -> toggle()
            "next" -> next()
            "previous" -> previous()
            "stop" -> stopPlayback()
            "shuffle", "toggle_shuffle" -> toggleShuffle()
            "repeat", "toggle_repeat" -> toggleRepeat()
            "like", "toggle_like" -> toggleCurrentTrackLike()
            "widget_play_pick" -> {
                val pickIndex = intent.getIntExtra(MicaMusicWidgetProvider.EXTRA_PICK_INDEX, 0)
                val hits = FeedCacheManager.cachedTopHits.value.ifEmpty { FeedCacheManager.defaultCuratedHits() }
                val selectedTrack = hits.getOrNull(pickIndex) ?: hits.firstOrNull()
                if (selectedTrack != null) {
                    scope.launch {
                        val resolved = AudioStreamResolver.resolveBestStream(
                            title = selectedTrack.title,
                            artist = selectedTrack.artist,
                            ytVideoId = selectedTrack.videoId,
                            rawThumbUrl = selectedTrack.thumbUrl,
                            directStreamUrl = selectedTrack.directStreamUrl
                        )
                        if (resolved != null) {
                            val item = MediaItem(
                                uri = resolved.streamUrl,
                                title = selectedTrack.title,
                                artist = selectedTrack.artist,
                                audio = true,
                                thumbUri = resolved.hdThumbUrl.ifBlank { selectedTrack.thumbUrl },
                                sourceVideoId = selectedTrack.videoId,
                                folder = "MICA Quick Pick"
                            )
                            open(listOf(item), item, autoPlay = true)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    fun open(items: List<MediaItem>, selected: MediaItem, autoPlay: Boolean = true) {
        endTemporarySpeed()
        resetBoost()
        savePosition()
        autoQueueJob?.cancel()
        preResolveJob?.cancel()
        autoQueueActive.value = false
        autoQueueLoading.value = false
        autoQueueSeedTitle.value = null
        lastAutoQueueSeedKey = null
        externalSubtitles.clear()
        pendingStyleTracks = null
        val hasSelected = items.any { it.uri == selected.uri }
        queue = if (hasSelected) items else listOf(selected)
        queueItems.value = queue
        index = queue.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0)
        retried = false
        decoderMode = Store.prefs(this).getString("decoder", "Auto") ?: "Auto"
        openCurrent(null, autoPlay = autoPlay)
    }

    private fun openCurrent(position: Long?, restore: Boolean = true, keepSubtitleChoice: Boolean = false, autoPlay: Boolean = true) {
        trackPlayStartTime = SystemClock.elapsedRealtime()
        endTemporarySpeed()
        sleepFadeActive = false
        if (::player.isInitialized && !released) player.volume = effectiveVolume()
        repeatA = null; repeatB = null; abPoints.value = null to null
        val item = queue.getOrNull(index) ?: return
        opening?.cancel()
        dimensionProbe?.cancel()
        demuxJob?.cancel()
        currentContainerInfo = ContainerDemuxer.ContainerInfo()
        clearSeek()
        seekThumbCache.evictAll()
        finished = false
        activeSubtitleText.value = null
        dualSubtitleLines.value = SubtitleOverlayLines()
        chapters.value = emptyList()
        selectedSubTracks.clear()
        userModifiedSubtitles = keepSubtitleChoice
        initialSubRestored = keepSubtitleChoice && pendingStyleTracks?.selectedSubtitleTracks.isNullOrEmpty()
        autoSubCues = emptyList()
        parsedCues = emptyList()
        activeCues = emptyList()
        embeddedCuesByTrack = emptyMap()
        embeddedTracks = emptyMap()
        internalSpuIdsOrdered = emptyList()
        pendingExternalAdds.clear()
        externalSpuPathsById.clear()
        externalCuesBySpuId.clear()
        externalLabelsBySpuId.clear()
        requestedPause = false
        Library.videoPlaybackActive.value = item.audio.not()
        val isOnlineAudioItem = item.audio && (PlaybackRules.isStreamUrl(item.uri) || isLazyOnlineUri(item.uri))
        // Auto-detect embedded MKV/MP4 subtitle cues, saved AI subtitles, and sidecar files (skip for remote online audio)
        if (!isOnlineAudioItem) {
        subtitleDemuxing.value = true
        subtitleCuesRevision.value++
        demuxJob = scope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    val uri = Uri.parse(item.uri)
                    val localFile = resolveLocalFile(uri)
                    val sniffed = if (localFile != null) Library.sniffFile(localFile) else Library.sniff(this@PlaybackService, uri)
                    val extLower = PlaybackRules.containerExtensionHint(localFile?.name ?: item.title, sniffed)

                    if (extLower in listOf("mkv", "webm", "mp4", "m4v", "mov")) {
                        val onBatch: (Map<Int, ContainerDemuxer.EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit = { batchTracks, batchCues ->
                            scope.launch(Dispatchers.Main) {
                                if (state.value.item?.uri == item.uri) {
                                    embeddedTracks = batchTracks
                                    embeddedCuesByTrack = batchCues
                                    subtitleCuesRevision.value++
                                    refreshTracks(restore = false)
                                    if (selectedSubTracks.isNotEmpty()) {
                                        syncSubtitleTracks(persistName = false)
                                    } else {
                                        maybeAutoSelectPrimarySubtitle()
                                    }
                                }
                            }
                        }
                        val demux = ContainerDemuxer.parseContainer(
                            context = this@PlaybackService,
                            uri = uri,
                            localFile = localFile,
                            extHint = extLower,
                            onProgressBatch = onBatch,
                            isCancelled = { !isActive },
                        )
                        if (!isActive) return@runCatching
                        withContext(Dispatchers.Main) {
                            if (state.value.item?.uri == item.uri) {
                                currentContainerInfo = demux.containerInfo
                                embeddedTracks = demux.tracks
                                embeddedCuesByTrack = demux.cuesByTrack
                                if (demux.chapters.isNotEmpty()) {
                                    chapters.value = demux.chapters
                                }
                                subtitleCuesRevision.value++
                                refreshTracks(restore = false)
                                if (selectedSubTracks.isNotEmpty()) {
                                    syncSubtitleTracks(persistName = false)
                                } else {
                                    maybeAutoSelectPrimarySubtitle()
                                }
                            }
                        }
                    }

                    // Discover saved AI subtitles or matching sidecar SRT/ASS/VTT in folder
                    val savedCustomSub = Store.prefs(this@PlaybackService).getString("custom_sub_${item.uri}", null)
                    val baseName = localFile?.nameWithoutExtension ?: item.title.substringBeforeLast('.')
                    val sanitizedBase = baseName.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
                    val baseCandidates = listOf(baseName, sanitizedBase).filter { it.isNotBlank() }.distinct()
                    val aiSrtCandidates = buildList {
                        savedCustomSub?.let { add(java.io.File(it)) }
                        for (name in baseCandidates) {
                            localFile?.parentFile?.let { add(java.io.File(it, "$name.srt")) }
                            add(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "$name.srt"))
                            add(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "MSPlayer/$name.srt"))
                        }
                    }
                    val matchingAiSrt = aiSrtCandidates.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
                    if (matchingAiSrt != null) {
                        val parsedAi = runCatching { PlaybackRules.parseSubtitleCues(readSubtitleText(matchingAiSrt)) }.getOrDefault(emptyList())
                        if (parsedAi.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (state.value.item?.uri == item.uri) {
                                    autoSubCues = parsedAi.sortedBy { it.startMs }
                                    subtitleCuesRevision.value++
                                    // Auto-select saved AI / exact-stem subtitles on initial open, preserving user uncheck on decoder/style reload
                                    if (!userModifiedSubtitles && pendingStyleTracks == null) {
                                        selectedSubTracks.clear()
                                        selectedSubTracks.add(9999)
                                        initialSubRestored = true
                                    }
                                    refreshTracks(false)
                                    if (9999 in selectedSubTracks) {
                                        syncSubtitleTracks("AI Subtitles", persistName = false)
                                    }
                                }
                            }
                        }
                    }

                    // Fetch and load remote stream subtitles if provided
                    if (!item.subtitleUri.isNullOrBlank()) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val conn = (java.net.URL(item.subtitleUri).openConnection() as java.net.HttpURLConnection).apply {
                                    connectTimeout = 4000
                                    readTimeout = 4000
                                    setRequestProperty("User-Agent", "Mozilla/5.0")
                                }
                                if (conn.responseCode in 200..299) {
                                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                                    val cues = PlaybackRules.parseSubtitleCues(body)
                                    if (cues.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            if (state.value.item?.uri == item.uri) {
                                                loadAutoSubCues(cues, userTriggered = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Expose other external sidecar subtitle files in video folder and Subs/Subtitles subfolders
                    val parentDir = localFile?.parentFile
                    val isPrivateAppFilesDir = localFile?.absolutePath?.startsWith(filesDir.absolutePath) == true
                    if (parentDir != null && parentDir.isDirectory && !isPrivateAppFilesDir) {
                        val aiPath = matchingAiSrt?.absolutePath
                        val scanDirs = buildList {
                            add(parentDir to true) // primary folder requires matching base prefix
                            listOf("Subs", "subs", "Subtitles", "subtitles").forEach { subName ->
                                val d = java.io.File(parentDir, subName)
                                if (d.exists() && d.isDirectory) add(d to false)
                            }
                            baseCandidates.forEach { base ->
                                val d = java.io.File(parentDir, base)
                                if (d.exists() && d.isDirectory) add(d to false)
                            }
                        }
                        val sidecars = scanDirs.flatMap { (dir, requirePrefix) ->
                            dir.listFiles()
                                ?.filter { f ->
                                    val stem = f.nameWithoutExtension
                                    f.isFile &&
                                        f.absolutePath != aiPath &&
                                        f.extension.lowercase() in listOf("srt", "vtt", "ass", "ssa") &&
                                        (!requirePrefix || baseCandidates.any { base ->
                                            stem.equals(base, ignoreCase = true) ||
                                                stem.startsWith("$base.", ignoreCase = true) ||
                                                stem.startsWith("$base-", ignoreCase = true) ||
                                                stem.startsWith("$base ", ignoreCase = true) ||
                                                stem.startsWith("${base}_", ignoreCase = true)
                                        })
                                }.orEmpty()
                        }
                            .distinctBy { it.absolutePath }
                            .sortedBy { it.name.lowercase() }
                            .take(12)
                        if (sidecars.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (state.value.item?.uri == item.uri) {
                                    sidecars.forEach { sidecar ->
                                        addSubtitle(sidecar.absolutePath, selectExtra = false)
                                    }
                                }
                            }
                        }
                    }

                    // Restore previously imported OpenSubtitles / picked subtitle files for this item
                    val savedImports = Store.prefs(this@PlaybackService)
                        .getStringSet("imported_subs_${item.uri}", emptySet())
                        .orEmpty()
                        .map { java.io.File(it) }
                        .filter { it.exists() && it.isFile && it.length() > 0 && it.absolutePath != matchingAiSrt?.absolutePath }
                    if (savedImports.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (state.value.item?.uri == item.uri) {
                                savedImports.forEach { imp ->
                                    addSubtitle(imp.absolutePath, selectExtra = false)
                                }
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (state.value.item?.uri == item.uri) {
                        subtitleDemuxing.value = false
                        subtitleCuesRevision.value++
                    }
                }
            }
        }
        } else {
            subtitleDemuxing.value = false
        }
        opening =
            scope.launch {
                var activeItem = item
                val currentIdx = index
                if (activeItem.audio && isLazyOnlineUri(activeItem.uri)) {
                    state.value = PlayerState(item = activeItem, buffering = true, decoder = decoderMode)
                    timeline.value = PlaybackTimeline(0L, activeItem.duration, false)
                    loadNotificationArtwork(activeItem)
                    updateSession(forceMetadata = true, forceState = true)
                    foreground()
                    scheduleMediaNotificationSync()
                    var searchTitle = activeItem.title
                    var searchArtist = activeItem.artist
                    if (activeItem.uri.startsWith("resolve://spotify/")) {
                        val payload = activeItem.uri.removePrefix("resolve://spotify/")
                        val parts = payload.split("::")
                        if (parts.size >= 3) {
                            searchTitle = parts[1]
                            searchArtist = parts[2]
                        } else {
                            if (parts.isNotEmpty() && parts[0].isNotBlank()) searchTitle = parts[0]
                            if (parts.size > 1 && parts[1].isNotBlank()) searchArtist = parts[1]
                        }
                    } else if (activeItem.uri.startsWith("resolve://audio/")) {
                        val payload = activeItem.uri.removePrefix("resolve://audio/")
                        val parts = payload.split("::")
                        if (parts.isNotEmpty() && parts[0].isNotBlank()) searchTitle = parts[0]
                        if (parts.size > 1 && parts[1].isNotBlank()) searchArtist = parts[1]
                    } else if (searchTitle.contains(" • ")) {
                        val parts = searchTitle.split(" • ")
                        if (parts.isNotEmpty() && parts[0].isNotBlank()) searchTitle = parts[0]
                        if (parts.size > 1 && searchArtist.isBlank()) searchArtist = parts[1]
                    }

                    val resolved = AudioStreamResolver.resolveBestStream(
                        title = searchTitle,
                        artist = searchArtist,
                        ytVideoId = extractVideoId(activeItem),
                        rawThumbUrl = activeItem.thumbUri ?: "",
                        directStreamUrl = "",
                        context = this@PlaybackService,
                    )
                    if (resolved == null || resolved.streamUrl.isBlank()) {
                        if (currentIdx + 1 < queue.size) {
                            index = currentIdx + 1
                            retried = false
                            openCurrent(null, autoPlay = autoPlay)
                        } else {
                            state.value = PlayerState(item = activeItem, error = "Unable to stream this track right now.")
                            stopForeground(STOP_FOREGROUND_DETACH)
                        }
                        return@launch
                    }
                    activeItem = activeItem.copy(
                        title = searchTitle,
                        artist = searchArtist.ifBlank { activeItem.artist },
                        uri = resolved.streamUrl,
                        thumbUri = resolved.hdThumbUrl.ifBlank { activeItem.thumbUri ?: "" },
                        streamUserAgent = resolved.userAgent,
                    )
                    val mutable = queue.toMutableList()
                    if (currentIdx in mutable.indices) {
                        mutable[currentIdx] = activeItem
                        queue = mutable
                        queueItems.value = queue
                    }
                }
                val saved =
                    if (restore && Store.prefs(this@PlaybackService).getBoolean("resume", true) && !activeItem.audio)
                        withContext(Dispatchers.IO) {
                            Store.db(this@PlaybackService).dao().saved(activeItem.uri)
                        }
                    else null
                try {
                    metadataDimensions = null
                    clearSeek()
                    dimensionProbe?.cancel()
                    player.stop()
                    activeDescriptor?.close()
                    activeDescriptor = null
                    // Parcel file descriptors preserve provider permissions and support local
                    // seekable content.
                    val uri = Uri.parse(activeItem.uri)
                    val directFile = if (PlaybackRules.isStreamUrl(activeItem.uri)) null else {
                        resolveLocalFile(uri, activeItem)
                            ?: Library.resolvePathFile(this@PlaybackService, uri, activeItem)
                            ?: Library.findPhysicalMediaFile(activeItem)
                    }
                    val media = if (directFile != null && directFile.canRead()) {
                        Media(vlc, directFile.absolutePath)
                    } else if (uri.scheme == "content") {
                        try {
                            activeDescriptor =
                                contentResolver.openFileDescriptor(uri, "r")
                                    ?: throw IllegalStateException("File unavailable")
                            Media(vlc, activeDescriptor!!.fileDescriptor)
                        } catch (sec: SecurityException) {
                            if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                                requiresAllFilesPermission.value = true
                            }
                            throw sec
                        }
                    } else Media(vlc, uri)
                    media.setHWDecoderEnabled(decoderMode != "Software", decoderMode == "Hardware")
                    if (PlaybackRules.isStreamUrl(activeItem.uri)) {
                        media.addOption(":network-caching=400")
                        media.addOption(":live-caching=400")
                        media.addOption(":clock-jitter=0")
                        media.addOption(":clock-synchro=0")
                        media.addOption(":http-reconnect=1")
                        media.addOption(":http-forward-cookies")
                        val isYtStream = activeItem.uri.contains("googlevideo.com") || activeItem.uri.contains("youtube.com")
                        val streamUa = activeItem.streamUserAgent.ifBlank {
                            if (isYtStream) {
                                "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
                            } else {
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            }
                        }
                        media.addOption(":http-user-agent=$streamUa")
                    } else {
                        media.addOption(":file-caching=250")
                    }
                    val start =
                        position
                            ?: saved?.let {
                                PlaybackRules.resume(it.position, activeItem.duration, it.watched)
                            }
                            ?: 0
                    if (start > 0) media.addOption(":start-time=${start / 1000.0}")
                    resumeAnchor = start.takeIf { it > 0 }
                    val folderPrefs = if (!activeItem.audio && !PlaybackRules.isStreamUrl(activeItem.uri) && Store.folderMemoryEnabled(this@PlaybackService) && activeItem.folder.isNotBlank()) {
                        Store.folderMemory(this@PlaybackService, activeItem.folder)
                    } else null
                    val initialRate = if (!activeItem.audio && !PlaybackRules.isStreamUrl(activeItem.uri) && restore && pendingStyleRestore == null && folderPrefs?.rate != null && folderPrefs.rate in 0.25f..4f) {
                        folderPrefs.rate
                    } else 1f
                    if (restore && pendingStyleRestore == null && !keepSubtitleChoice) {
                        aspectMode.value = folderPrefs?.aspectName
                            ?.let { runCatching { PlaybackRules.AspectOption.valueOf(it) }.getOrNull() }
                            ?: PlaybackRules.AspectOption.BEST_FIT
                    }
                    state.value =
                        PlayerState(
                            item = activeItem,
                            videoWidth = metadataDimensions?.first ?: 0,
                            videoHeight = metadataDimensions?.second ?: 0,
                            buffering = true,
                            rate = initialRate,
                            decoder = decoderMode,
                            position = start,
                            duration = activeItem.duration,
                        )
                    timeline.value = PlaybackTimeline(start, activeItem.duration, false)
                    loadNotificationArtwork(activeItem)
                    player.media = media
                    player.rate = initialRate
                    media.release()
                    updateSession(forceMetadata = true, forceState = true)
                    foreground()
                    scheduleMediaNotificationSync()
                    // Immediately record played media to History database
                    scope.launch(Dispatchers.IO) {
                        val historyTitle = if (activeItem.artist.isNotBlank()) "${activeItem.title} • ${activeItem.artist}" else activeItem.title
                        Store.db(this@PlaybackService).dao().save(SavedMedia().apply {
                            this.uri = activeItem.uri
                            this.title = historyTitle
                            this.position = start
                            this.updated = System.currentTimeMillis()
                            this.watched = false
                        })
                    }
                    bufferingWatchdog?.cancel()
                    if (activeItem.audio && (PlaybackRules.isStreamUrl(activeItem.uri) || isLazyOnlineUri(activeItem.uri))) {
                        val isLiveFlac = activeItem.sourceVideoId.startsWith("flac_") || activeItem.uri.contains("flac", true) || activeItem.uri.contains("motherearth", true)
                        if (!isLiveFlac) {
                            bufferingWatchdog = scope.launch {
                                delay(3500)
                                if (state.value.buffering && !state.value.playing && !requestedPause) {
                                    android.util.Log.w("MSPlayer", "Buffering timeout >3.5s on online audio, triggering fallback from ${activeItem.uri}")
                                    handleOnlineAudioFallback(activeItem)
                                }
                            }
                        }
                    }
                    // Trigger Auto-Queue Radio & background next-track pre-resolution for online audio
                    if (activeItem.audio && PlaybackRules.isStreamUrl(activeItem.uri)) {
                        triggerAutoQueueIfNeeded(activeItem)
                        preResolveNextTrack()
                    }
                    // Native rotation/SAR is available with the first track event. Avoid
                    // a second demux/metadata reader before high-bitrate playback starts.
                    dimensionProbe = scope.launch {
                        delay(2000)
                        if (!activeItem.audio && state.value.videoWidth == 0) {
                            val dimensions = Library.videoDimensions(this@PlaybackService, activeItem.uri)
                            if (state.value.item?.uri == activeItem.uri && dimensions != null) {
                                metadataDimensions = dimensions
                                state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
                            }
                        }
                    }
                    if (requestFocus()) {
                        if (autoPlay) {
                            if (activeItem.audio || videoAttached) player.play() else pendingPlayback = true
                        } else {
                            requestedPause = true
                            state.value = state.value.copy(playing = false, buffering = false)
                        }
                    } else
                        state.value =
                            state.value.copy(
                                buffering = false,
                                error = "Audio focus is currently unavailable. Tap play to retry.",
                            )
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Library.videoPlaybackActive.value = false
                    val isPendingOrSecurity = e is SecurityException ||
                        e.message?.contains("pending", ignoreCase = true) == true ||
                        e.message?.contains("Only owner", ignoreCase = true) == true ||
                        PlaybackRules.temporaryFile(activeItem.title)
                    if (isPendingOrSecurity && android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                        requiresAllFilesPermission.value = true
                    }
                    val errorMsg = if (requiresAllFilesPermission.value) {
                        "All files access required to play in-progress downloads (.pending)."
                    } else if (PlaybackRules.temporaryFile(activeItem.title)) {
                        "This download is unavailable or still private to its downloader. Open a completed copy or choose an accessible folder."
                    } else {
                        "Cannot open file: ${e.message}"
                    }
                    state.value = PlayerState(item = activeItem, error = errorMsg)
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
    }

    fun retryCurrent(pos: Long? = null) {
        requiresAllFilesPermission.value = false
        openCurrent(pos ?: timeline.value.position, restore = false)
    }

    fun play() {
        if (released) return
        userPausedAudio = false
        Store.prefs(this).edit().putBoolean("user_paused_audio", false).apply()
        sleepFadeActive = false
        player.volume = effectiveVolume()
        requestedPause = false
        pendingStyleRestore = pendingStyleRestore?.copy(playing = true)
        Library.videoPlaybackActive.value = state.value.item?.audio == false
        if (state.value.item == null) {
            if (queue.isNotEmpty()) {
                openCurrent(null, autoPlay = true)
                return
            }
            val hits = FeedCacheManager.cachedTopHits.value.ifEmpty { FeedCacheManager.defaultCuratedHits() }
            val firstHit = hits.firstOrNull()
            if (firstHit != null) {
                scope.launch {
                    val resolved = AudioStreamResolver.resolveBestStream(
                        title = firstHit.title,
                        artist = firstHit.artist,
                        ytVideoId = firstHit.videoId,
                        rawThumbUrl = firstHit.thumbUrl,
                        directStreamUrl = firstHit.directStreamUrl
                    )
                    val sUrl = resolved?.streamUrl ?: firstHit.directStreamUrl
                    if (sUrl.isNotBlank()) {
                        val item = MediaItem(
                            uri = sUrl,
                            title = firstHit.title,
                            artist = firstHit.artist,
                            thumbUri = resolved?.hdThumbUrl?.ifBlank { firstHit.thumbUrl } ?: firstHit.thumbUrl,
                            sourceVideoId = firstHit.videoId,
                            streamUserAgent = resolved?.userAgent.orEmpty(),
                            audio = true,
                            folder = "Top Trending Hits"
                        )
                        open(listOf(item), item, autoPlay = true)
                    }
                }
            }
            return
        }
        if (::dynamicIsland.isInitialized) dynamicIsland.resetDismiss()
        if (finished) {
            openCurrent(0, false)
            return
        }
        foreground()
        if (requestFocus()) player.play()
    }

    fun pause(abandon: Boolean = true) {
        endTemporarySpeed()
        if (released) return
        if (state.value.item?.audio == true) {
            userPausedAudio = true
            Store.prefs(this).edit().putBoolean("user_paused_audio", true).apply()
        }
        Library.videoPlaybackActive.value = videoAttached && state.value.item?.audio == false
        requestedPause = true
        pendingStyleRestore = pendingStyleRestore?.copy(playing = false)
        if (player.isPlaying) player.pause()
        state.value = state.value.copy(buffering = false)
        savePosition()
        if (abandon) abandonFocus()
    }

    fun toggle() {
        if (player.isPlaying) pause() else play()
    }

    fun next() {
        if (shuffleEnabled.value && queue.size > 1) {
            savePosition()
            val unplayed = queue.indices.filter { it !in playedShuffleHistory && it != index }
            val nextIdx = if (unplayed.isNotEmpty()) {
                unplayed.random()
            } else {
                playedShuffleHistory.clear()
                playedShuffleHistory.add(index)
                val fresh = queue.indices.filter { it != index }
                if (fresh.isNotEmpty()) fresh.random() else 0
            }
            playedShuffleHistory.add(nextIdx)
            index = nextIdx
            retried = false
            openCurrent(null)
            return
        }
        if (index + 1 >= queue.size && repeatMode.value == "all" && queue.isNotEmpty()) { savePosition(); index = 0; openCurrent(null); return }
        if (index + 1 < queue.size) {
            savePosition()
            index++
            retried = false
            openCurrent(null)
        }
    }

    fun previous() {
        if (player.time > 3000 && player.isSeekable) seek(0)
        else if (index > 0) {
            savePosition()
            index--
            retried = false
            openCurrent(null)
        }
    }

    fun seek(position: Long, fast: Boolean = Store.prefs(this).getBoolean("fast_seek", true)): Boolean {
        if (released || !player.isSeekable || player.length <= 0) return false
        resumeAnchor = null
        seekCorrection?.cancel()
        val target = PlaybackRules.safeSeek(position, player.length)
        seekOrigin = player.time.coerceAtLeast(0)
        seekTarget = target
        seekStarted = SystemClock.elapsedRealtime()
        // Accurate seeking may decode a whole HEVC GOP before displaying a frame.
        // For audio files, keyframe snapping causes 5-10s jumps, so enforce fast=false.
        val isAudio = state.value.item?.audio == true
        val actualFast = if (isAudio) false else fast
        val result = player.setTime(target, actualFast)
        if (result < 0) { clearSeek(); return false }
        state.value = state.value.copy(position = target)
        timeline.value = PlaybackTimeline(target, player.length, true, true)
        if (parsedCues.isNotEmpty() || selectedSubTracks.isNotEmpty()) {
            updateSubtitleTextAt(target)
        }
        diagnostics.event("seek-request from=$seekOrigin target=$target fast=$actualFast")
        if (actualFast) seekCorrection = scope.launch {
            delay(900)
            if (!released && seekTarget == target) {
                val actual = player.time
                // A long GOP can map a tap to the same keyframe it started from.
                // Correct that one no-movement case once, without a retry loop.
                if (kotlin.math.abs(actual - target) > 2000 && kotlin.math.abs(actual - seekOrigin) < 1500) {
                    diagnostics.event("seek-precise-correction target=$target actual=$actual")
                    player.setTime(target, false)
                }
            }
        }
        updateSession()
        return true
    }

    private val seekThumbCache = object : android.util.LruCache<Long, android.graphics.Bitmap>(16) {}

    fun seekThumbnail(position: Long): android.graphics.Bitmap? {
        if (released) return null
        val item = state.value.item ?: return null
        if (item.audio) return null
        val bucket = (position / 1000L) * 1000L
        seekThumbCache.get(bucket)?.let { return it }
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                val uri = Uri.parse(item.uri)
                var frame: android.graphics.Bitmap? = null
                if (uri.scheme == "content") {
                    contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                        retriever.setDataSource(fd.fileDescriptor)
                        frame = if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                        } else {
                            retriever.getFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    }
                } else if (uri.scheme == "file") {
                    retriever.setDataSource(uri.path)
                    frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                    } else {
                        retriever.getFrameAtTime(position * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }
                if (frame != null) {
                    seekThumbCache.put(bucket, frame)
                }
                frame
            } finally {
                retriever.release()
            }
        } catch (_: Exception) { null }
    }

    fun rate(value: Float) {
        endTemporarySpeed()
        player.rate = value.coerceIn(.25f, 4f)
        state.value = state.value.copy(rate = player.rate)
        state.value.item?.folder?.takeIf { it.isNotBlank() && Store.folderMemoryEnabled(this) }?.let { folder ->
            Store.updateFolderMemory(this, folder, rate = player.rate)
        }
    }

    fun setAspectMode(option: PlaybackRules.AspectOption, persistFolder: Boolean = true) {
        aspectMode.value = option
        if (persistFolder) {
            state.value.item?.folder?.takeIf { it.isNotBlank() && Store.folderMemoryEnabled(this) }?.let { folder ->
                Store.updateFolderMemory(this, folder, aspectName = option.name)
            }
        }
        if (released) return
        player.setScale(0f)
        player.setAspectRatio(null)
        val scaleType = when (option) {
            PlaybackRules.AspectOption.BEST_FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            PlaybackRules.AspectOption.CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            PlaybackRules.AspectOption.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
            PlaybackRules.AspectOption.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            PlaybackRules.AspectOption.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
            PlaybackRules.AspectOption.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
        }
        player.setVideoScale(scaleType)
        enforceCenterGravity(videoLayout)
        when (option) {
            PlaybackRules.AspectOption.RATIO_16_9 -> player.setAspectRatio("16:9")
            PlaybackRules.AspectOption.RATIO_4_3 -> player.setAspectRatio("4:3")
            else -> player.setAspectRatio(null)
        }
    }

    fun decoder(mode: String) {
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        Store.prefs(this).edit().putString("decoder", mode).apply()
        decoderMode = mode
        retried = mode == "Software"
        state.value = state.value.copy(decoder = mode)
        pendingStyleRestore = prior
        pendingStyleTracks = prior
        openCurrent(state.value.position, restore = false, keepSubtitleChoice = true)
    }

    private fun maybeAutoSelectPrimarySubtitle() {
        if (userModifiedSubtitles || initialSubRestored || selectedSubTracks.isNotEmpty() || pendingStyleTracks != null) return
        val item = state.value.item
        val folderPrefSub = if (item != null && item.folder.isNotBlank() && Store.folderMemoryEnabled(this)) {
            Store.folderMemory(this, item.folder)?.subLang
        } else null
        val preferredSub = folderPrefSub ?: Store.prefs(this).getString("subtitle_name", null)
        val target = PlaybackRules.choosePrimarySubtitle(
            tracks = state.value.subtitles,
            preferredName = preferredSub,
            favoriteLanguages = Store.favoriteSubtitleLanguages(this),
            starredTracks = Store.starredSubtitles(this),
        ) ?: state.value.subtitles.firstOrNull { it.first >= 0 } ?: return
        initialSubRestored = true
        selectedSubTracks.clear()
        selectedSubTracks.add(target.first)
        syncSubtitleTracks(target.second, persistName = false)
    }

    fun refreshTracks(restore: Boolean = true) {
        player.currentVideoTrack?.let { video ->
            val dimensions = metadataDimensions ?: PlaybackRules.displayDimensions(video.width, video.height, video.orientation, video.sarNum, video.sarDen)
            state.value = state.value.copy(videoWidth = dimensions.first, videoHeight = dimensions.second)
        }
        val media = player.media
        val nativeTextIds = try {
            media?.let { m ->
                (0 until m.trackCount).mapNotNull { m.getTrack(it) }
                    .filter { it.type == org.videolan.libvlc.interfaces.IMedia.Track.Type.Text }
                    .map { it.id }
                    .toSet()
            }.orEmpty()
        } finally {
            media?.release()
        }

        val spuRawList = player.spuTracks?.map { it.id to it.name }.orEmpty().toMutableList()
        val subTracks = embeddedTracks.values.filter { it.type == 17 }.sortedBy { it.trackNumber }

        // Associate any newly registered external LibVLC slave SPU IDs with queued addSubtitle() requests
        var autoSelectedExternal = false
        for ((id, rawName) in spuRawList) {
            if (id < 0 || id == 9999 || id in externalSpuPathsById) continue
            val matchedReq = pendingExternalAdds.firstOrNull { req ->
                id !in req.knownIdsBeforeAdd ||
                    (nativeTextIds.isNotEmpty() && id !in nativeTextIds) ||
                    (rawName.isNotBlank() && rawName.contains(req.label, ignoreCase = true))
            }
            if (matchedReq != null) {
                pendingExternalAdds.remove(matchedReq)
                externalSpuPathsById[id] = matchedReq.path
                externalLabelsBySpuId[id] = matchedReq.label
                externalCuesBySpuId[id] = matchedReq.cues
                subtitleCuesRevision.value++
                if (matchedReq.selectExtra) {
                    userModifiedSubtitles = true
                    selectedSubTracks.add(id)
                    autoSelectedExternal = true
                }
            }
        }

        internalSpuIdsOrdered = spuRawList.map { it.first }.filter { id ->
            id >= 0 && id != 9999 && id !in externalSpuPathsById &&
                (nativeTextIds.isEmpty() || id in nativeTextIds)
        }

        if (autoSubCues.isNotEmpty() && spuRawList.none { it.first == 9999 }) {
            spuRawList.add(9999 to "AI Subtitles")
        }

        val mappedList = spuRawList.map { (id, name) ->
            when {
                id < 0 -> id to name
                id == 9999 -> id to "AI Subtitles"
                id in externalLabelsBySpuId -> id to externalLabelsBySpuId[id]!!
                else -> {
                    val internalIdx = internalSpuIdsOrdered.indexOf(id)
                    val trackInfo = if (internalIdx in subTracks.indices) subTracks[internalIdx] else null
                    val cleanName = when {
                        trackInfo != null && trackInfo.name.isNotBlank() -> trackInfo.name
                        name.isNotBlank() && !name.startsWith("Track ") && name != "Disable" -> name
                        trackInfo != null -> "Track ${internalIdx + 1} [${trackInfo.language}]"
                        else -> name.ifBlank { "Track $id" }
                    }
                    id to cleanName
                }
            }
        }

        state.value =
            state.value.copy(
                audioTracks = player.audioTracks?.map { it.id to it.name }.orEmpty(),
                subtitles = mappedList,
                audioTrack = player.audioTrack,
                subtitleTrack = selectedSubTracks.firstOrNull() ?: player.spuTrack,
                selectedSubtitleTracks = selectedSubTracks.toSet()
            )
        updateSubtitleKind()

        val prefs = Store.prefs(this)
        val itemFolder = state.value.item?.folder.orEmpty()
        val folderAudio = if (itemFolder.isNotBlank() && Store.folderMemoryEnabled(this)) {
            Store.folderMemory(this, itemFolder)?.audioLang
        } else null
        val preferredAudio = folderAudio ?: prefs.getString("audio_name", null)
        if (restore) {
            PlaybackRules.choosePrimaryAudio(
                tracks = state.value.audioTracks,
                preferredName = preferredAudio,
                favoriteLanguages = Store.favoriteAudioLanguages(this),
            )?.let { chosen ->
                player.setAudioTrack(chosen.first)
            }
            state.value =
                state.value.copy(audioTrack = player.audioTrack, subtitleTrack = selectedSubTracks.firstOrNull() ?: player.spuTrack)
        }
        pendingStyleTracks?.let { prior ->
            if (prior.item?.uri != state.value.item?.uri) pendingStyleTracks = null
            else {
                val audioReady = prior.audioTrack < 0 || state.value.audioTracks.any { it.first == prior.audioTrack }
                val targetSubs = prior.selectedSubtitleTracks
                val targetNames = prior.subtitles.filter { it.first in targetSubs }.map { it.second }.toSet()
                val resolvedSubIds = LinkedHashSet<Int>().apply {
                    for (oldId in targetSubs) {
                        if (state.value.subtitles.any { it.first == oldId }) {
                            add(oldId)
                        } else {
                            val oldName = prior.subtitles.firstOrNull { it.first == oldId }?.second
                            val byName = oldName?.let { n ->
                                state.value.subtitles.firstOrNull { it.first >= 0 && it.second == n }?.first
                            }
                            if (byName != null) add(byName)
                        }
                    }
                }
                val subsReady = targetSubs.all { oldId ->
                    state.value.subtitles.any { it.first == oldId } ||
                        prior.subtitles.firstOrNull { it.first == oldId }?.second in state.value.subtitles.filter { it.first >= 0 }.map { it.second }
                }
                if (audioReady) player.setAudioTrack(prior.audioTrack)
                if (resolvedSubIds.isNotEmpty()) {
                    selectedSubTracks.clear()
                    selectedSubTracks.addAll(resolvedSubIds)
                    syncSubtitleTracks(persistName = false)
                } else if (prior.subtitleTrack < 0 || state.value.subtitles.any { it.first == prior.subtitleTrack }) {
                    if (prior.subtitleTrack >= 0) {
                        selectedSubTracks.clear()
                        selectedSubTracks.add(prior.subtitleTrack)
                    } else if (targetSubs.isEmpty() && targetNames.isEmpty()) {
                        selectedSubTracks.clear()
                    }
                    syncSubtitleTracks(persistName = false)
                }
                state.value = state.value.copy(
                    audioTrack = player.audioTrack,
                    subtitleTrack = selectedSubTracks.firstOrNull() ?: player.spuTrack,
                    selectedSubtitleTracks = selectedSubTracks.toSet()
                )
                if (audioReady && (subsReady || targetSubs.isEmpty())) pendingStyleTracks = null
            }
        }
        if (autoSelectedExternal && pendingStyleTracks == null) {
            syncSubtitleTracks(persistName = false)
        }
        maybeAutoSelectPrimarySubtitle()
        updateSubtitleKind()
    }

    fun rememberImportedSubtitle(mediaUri: String, path: String) {
        val prefs = Store.prefs(this)
        val updated = (prefs.getStringSet("imported_subs_$mediaUri", emptySet()).orEmpty() + path).take(12).toSet()
        prefs.edit().putStringSet("imported_subs_$mediaUri", updated).apply()
    }

    fun currentLocalMediaFile(): java.io.File? =
        state.value.item?.uri?.let { runCatching { resolveLocalFile(Uri.parse(it)) }.getOrNull() }

    fun currentMediaDetailedInfo(): MediaDetailedInfo? {
        val current = state.value.item ?: return null
        val uri = Uri.parse(current.uri)
        val localFile = runCatching { resolveLocalFile(uri) }.getOrNull()
        val media = if (::player.isInitialized && !released) player.media else null
        return try {
            val vlcTracks = media?.let { m -> (0 until m.trackCount).mapNotNull { m.getTrack(it) } }.orEmpty()
            val encodedBy = media?.getMeta(org.videolan.libvlc.interfaces.IMedia.Meta.EncodedBy).orEmpty()
            val subStatus = when {
                autoSubCues.isNotEmpty() -> "AI Subtitles"
                externalSubtitles.isNotEmpty() || externalCuesBySpuId.isNotEmpty() -> "External subtitle"
                vlcTracks.any { it.type == org.videolan.libvlc.interfaces.IMedia.Track.Type.Text } ||
                    embeddedTracks.values.any { it.type == 17 } -> "Embedded subtitle"
                else -> "None"
            }
            val location = localFile?.parentFile?.absolutePath ?: current.folder.ifBlank { "Storage" }
            val ext = PlaybackRules.containerExtensionHint(localFile?.name ?: current.title, null)
            Library.buildDetailedInfo(
                item = current,
                location = location,
                resolvedFile = localFile,
                containerInfo = currentContainerInfo,
                vlcTracks = vlcTracks,
                vlcEncodedBy = encodedBy,
                durationMs = state.value.duration.takeIf { it > 0 } ?: current.duration,
                width = state.value.videoWidth.takeIf { it > 0 } ?: current.width,
                height = state.value.videoHeight.takeIf { it > 0 } ?: current.height,
                extHint = ext,
                subtitleSummary = subStatus,
            )
        } finally {
            media?.release()
        }
    }

    fun audioTrack(id: Int, name: String) {
        pendingStyleTracks = pendingStyleTracks?.copy(audioTrack=id)
        pendingStyleRestore = pendingStyleRestore?.copy(audioTrack=id)
        player.setAudioTrack(id)
        Store.prefs(this).edit().putString("audio_name", name).apply()
        state.value.item?.folder?.takeIf { it.isNotBlank() && Store.folderMemoryEnabled(this) }?.let { folder ->
            Store.updateFolderMemory(this, folder, audioLang = name)
        }
        state.value = state.value.copy(audioTrack = id)
    }

    fun availableVideoTracks(): List<Pair<Int, String>> = runCatching {
        if (!::player.isInitialized || released) emptyList()
        else player.videoTracks?.map { it.id to it.name }?.filter { it.first >= 0 }.orEmpty()
    }.getOrDefault(emptyList())

    fun currentVideoTrackId(): Int = runCatching {
        if (!::player.isInitialized || released) -1 else player.videoTrack
    }.getOrDefault(-1)

    fun setVideoTrack(id: Int) {
        if (!::player.isInitialized || released) return
        player.setVideoTrack(id)
    }

    private fun updateSubtitleKind() {
        val media = player.media
        try {
            val nativeTracks = media?.let { m -> (0 until m.trackCount).mapNotNull { m.getTrack(it) }.filter { it.type == org.videolan.libvlc.interfaces.IMedia.Track.Type.Text } }.orEmpty()
            val track = nativeTracks.firstOrNull { it.id == player.spuTrack }
            val codec = track?.let { PlaybackRules.fourcc(it.fourcc).lowercase() } ?: ""
            val nativeIds = nativeTracks.map { it.id }.toSet()
            val externalIds = player.spuTracks.orEmpty().map { it.id }.filter { it >= 0 && it !in nativeIds }.sorted()
            val externalPath = externalSpuPathsById[player.spuTrack] ?: externalSubtitles.getOrNull(externalIds.indexOf(player.spuTrack))
            val authoredExternal = track == null && externalPath?.substringAfterLast('.')?.lowercase() in listOf("ass", "ssa", "sup", "sub", "idx")
            val vTrack = media?.let { m -> (0 until m.trackCount).mapNotNull { m.getTrack(it) }.filterIsInstance<org.videolan.libvlc.interfaces.IMedia.VideoTrack>().firstOrNull() }
            val vCodec = vTrack?.let { PlaybackRules.fourcc(it.fourcc).uppercase() } ?: ""
            state.value = state.value.copy(
                plainSubtitle = !authoredExternal && codec !in listOf("ssa", "ass", "pgs", "pgss", "bdpg", "spu", "dvbs", "dvd"),
                videoCodec = vCodec.takeIf { it.isNotBlank() } ?: state.value.videoCodec
            )
        } finally { media?.release() }
        updateSubtitlePlacement(controlsVisible)
    }

    private fun resolveEmbeddedTrackForSpuId(tId: Int): ContainerDemuxer.EmbeddedTrack? {
        val subTracks = embeddedTracks.values.filter { it.type == 17 }.sortedBy { it.trackNumber }
        if (subTracks.isEmpty()) return null
        val internalIdx = internalSpuIdsOrdered.indexOf(tId)
        val positional = if (internalIdx in subTracks.indices) subTracks[internalIdx] else null
        if (positional != null) {
            // Confirm cues or match when names align cleanly
            val label = state.value.subtitles.firstOrNull { it.first == tId }?.second.orEmpty()
            if (embeddedCuesByTrack[positional.trackNumber]?.isNotEmpty() == true ||
                label.isBlank() ||
                (positional.name.isNotBlank() && label.contains(positional.name, ignoreCase = true))
            ) {
                return positional
            }
        }
        // Fallback: match by track name or language code in display label
        val displayName = state.value.subtitles.firstOrNull { it.first == tId }?.second.orEmpty()
        if (displayName.isNotBlank()) {
            subTracks.firstOrNull { t ->
                t.name.isNotBlank() && displayName.contains(t.name, ignoreCase = true)
            }?.let { return it }
            subTracks.firstOrNull { t ->
                t.language.isNotBlank() && t.language != "und" &&
                    SubtitleTranslator.matchesLanguage(displayName, t.language)
            }?.let { return it }
        }
        return positional
    }

    private fun cuesForTrackId(tId: Int): List<PlaybackRules.SubtitleCue> {
        if (tId == 9999) return autoSubCues
        externalCuesBySpuId[tId]?.let { if (it.isNotEmpty()) return it }
        val resolved = resolveEmbeddedTrackForSpuId(tId) ?: return emptyList()
        return embeddedCuesByTrack[resolved.trackNumber].orEmpty()
    }

    fun cuesForSubtitleTrack(trackId: Int): List<PlaybackRules.SubtitleCue> = cuesForTrackId(trackId)

    fun subtitlePathForTrack(trackId: Int): String? = externalSpuPathsById[trackId]

    fun translatableSubtitleTracks(): List<Pair<Int, String>> =
        state.value.subtitles.filter { (id, _) -> id >= 0 && cuesForTrackId(id).isNotEmpty() }

    fun syncSubtitleToHeardLineNow(): Long? {
        val cues = activeSubtitleCues()
        if (cues.isEmpty()) return null
        val pos = nativePosition().coerceAtLeast(0L)
        val nearby = cues.filter { it.startMs in (pos - 12000L)..(pos + 4000L) }
        val targetCue = nearby.minByOrNull { kotlin.math.abs(it.startMs - pos) }
            ?: cues.lastOrNull { it.startMs <= pos }
            ?: cues.firstOrNull()
            ?: return null
        val newDelay = (pos - targetCue.startMs).coerceIn(-15000L, 15000L)
        subtitleDelay(newDelay)
        return newDelay
    }

    fun areSubtitlesEnabled(): Boolean = selectedSubTracks.isNotEmpty() || autoSubCues.isNotEmpty()

    fun toggleSubtitlesMaster(): Boolean {
        userModifiedSubtitles = true
        if (areSubtitlesEnabled()) {
            val primary = selectedSubTracks.firstOrNull() ?: -1
            if (primary >= 0) Store.prefs(this).edit().putInt("last_sub_track_${state.value.item?.uri.hashCode()}", primary).apply()
            selectedSubTracks.clear()
            autoSubCues = emptyList()
            syncSubtitleTracks(persistName = false)
            return false
        } else {
            val savedLast = Store.prefs(this).getInt("last_sub_track_${state.value.item?.uri.hashCode()}", -1)
            val available = state.value.subtitles.filter { it.first >= 0 }
            val trackToSelect = if (available.any { it.first == savedLast }) savedLast else available.firstOrNull()?.first ?: -1
            if (trackToSelect >= 0) {
                selectedSubTracks.clear()
                selectedSubTracks.add(trackToSelect)
                syncSubtitleTracks(persistName = false)
                return true
            } else {
                maybeAutoSelectPrimarySubtitle()
                return areSubtitlesEnabled()
            }
        }
    }

    fun toggleSubtitleTrack(id: Int, name: String) {
        userModifiedSubtitles = true
        if (id < 0) {
            selectedSubTracks.clear()
        } else {
            if (id in selectedSubTracks) {
                selectedSubTracks.remove(id)
            } else {
                if (selectedSubTracks.size >= 2) {
                    val first = selectedSubTracks.first()
                    selectedSubTracks.clear()
                    selectedSubTracks.add(first)
                }
                selectedSubTracks.add(id)
            }
        }
        syncSubtitleTracks(name, persistName = id >= 0)
    }

    fun subtitleTrack(id: Int, name: String) {
        userModifiedSubtitles = true
        pendingStyleTracks = pendingStyleTracks?.copy(subtitleTrack = id)
        pendingStyleRestore = pendingStyleRestore?.copy(subtitleTrack = id)
        selectedSubTracks.clear()
        if (id >= 0) selectedSubTracks.add(id)
        syncSubtitleTracks(name, persistName = true)
    }

    private fun isAuthoredOrBitmapTrack(tId: Int): Boolean {
        if (tId == 9999 || tId < 0) return false
        val cues = cuesForTrackId(tId)
        if (cues.isEmpty()) return true
        val ext = externalSpuPathsById[tId]?.substringAfterLast('.')?.lowercase()
        if (ext in listOf("ass", "ssa", "sup", "sub", "idx")) return true
        val subTracks = embeddedTracks.values.filter { it.type == 17 }.sortedBy { it.trackNumber }
        val internalIdx = internalSpuIdsOrdered.indexOf(tId)
        val codec = if (internalIdx in subTracks.indices) subTracks[internalIdx].codecId.lowercase() else ""
        return codec.contains("ass") || codec.contains("ssa") || codec.contains("pgs") ||
            codec.contains("vob") || codec.contains("dvb") || codec.contains("spu")
    }

    private fun resolvePrimaryHwTrack(): Int {
        // If 9999 (AI or Stream Subtitles) is selected or autoSubCues are present, force software StrokeView rendering!
        if (9999 in selectedSubTracks || autoSubCues.isNotEmpty()) return -1

        // Route plain-text SRT/VTT/AI tracks through StrokeView Canvas so fill, border,
        // and shadow stay pixel-locked with zero LibVLC FreeType drift. Keep bitmap
        // PGS/VobSub and authored ASS/SSA tracks on LibVLC native SPU.
        val authoredOrBitmap = selectedSubTracks.firstOrNull { isAuthoredOrBitmapTrack(it) }
        if (authoredOrBitmap != null) return authoredOrBitmap

        // If track has NO software demuxed cues (e.g. streaming Telegram files, network streams),
        // LibVLC native SPU MUST be used so subtitles render on screen!
        val cueLackingTrack = selectedSubTracks.firstOrNull { tId ->
            tId >= 0 && cuesForTrackId(tId).isEmpty()
        }
        if (cueLackingTrack != null) return cueLackingTrack

        return -1
    }

    private fun syncSubtitleTracks(name: String = "", persistName: Boolean = true) {
        val position = nativePosition()
        val primaryHwTrack = resolvePrimaryHwTrack()
        player.setSpuTrack(primaryHwTrack)

        val secondaryTracks = selectedSubTracks.filter { it != primaryHwTrack }
        val secondaryCues = mutableListOf<PlaybackRules.SubtitleCue>()
        for (tId in secondaryTracks) {
            secondaryCues.addAll(cuesForTrackId(tId))
        }
        if (secondaryCues.isEmpty() && selectedSubTracks.isEmpty() && legacyFolderCues.isNotEmpty()) {
            secondaryCues.addAll(legacyFolderCues)
        }

        parsedCues = secondaryCues.sortedBy { it.startMs }
        val jumpTimestamps = mutableListOf<Long>()
        for (tId in selectedSubTracks) {
            cuesForTrackId(tId).forEach { jumpTimestamps.add(it.startMs) }
        }
        activeCues = jumpTimestamps.sorted().distinct()

        updateSubtitleTextAt(position)

        if (persistName && name.isNotBlank()) {
            Store.prefs(this).edit().putString("subtitle_name", name).apply()
            state.value.item?.folder?.takeIf { it.isNotBlank() && Store.folderMemoryEnabled(this) }?.let { folder ->
                Store.updateFolderMemory(this, folder, subLang = name)
            }
        }
        state.value = state.value.copy(
            subtitleTrack = selectedSubTracks.firstOrNull() ?: -1,
            selectedSubtitleTracks = selectedSubTracks.toSet(),
            hasPrimaryHwSubtitle = primaryHwTrack >= 0
        )
        updateSubtitleKind()
    }

    fun updateSubtitleTextAt(position: Long) {
        val primaryHwTrack = resolvePrimaryHwTrack()
        val hasSoftwareStreamCues = 9999 in selectedSubTracks || autoSubCues.isNotEmpty()
        if (primaryHwTrack >= 0 && !hasSoftwareStreamCues) {
            // When LibVLC native SPU is rendering subtitles (e.g. online video streams or bitmap PGS/VobSub),
            // mute the Compose StrokeView text overlay so subtitles never render twice!
            if (activeSubtitleText.value != null || dualSubtitleLines.value.primary != null || dualSubtitleLines.value.secondary != null) {
                activeSubtitleText.value = null
                dualSubtitleLines.value = SubtitleOverlayLines(primary = null, secondary = null)
            }
            return
        }
        val effectivePos = position - state.value.subtitleDelay
        val secondaryTracks = selectedSubTracks.filter { it != primaryHwTrack }
        val primaryLines = mutableListOf<String>()
        val extraLines = mutableListOf<String>()

        secondaryTracks.forEachIndexed { idx, tId ->
            val cues = cuesForTrackId(tId)
            if (cues.isNotEmpty()) {
                val targetList = if (idx == 0) primaryLines else extraLines
                for (line in PlaybackRules.findActiveCueLines(cues, effectivePos)) {
                    if (line !in targetList && (idx == 0 || line !in primaryLines)) targetList.add(line)
                }
            }
        }

        if (secondaryTracks.isEmpty() && selectedSubTracks.isEmpty() && legacyFolderCues.isNotEmpty()) {
            for (line in PlaybackRules.findActiveCueLines(legacyFolderCues, effectivePos)) {
                if (line !in primaryLines) primaryLines.add(line)
            }
        }

        val primaryStr = if (primaryLines.isEmpty()) null else primaryLines.joinToString("\n")
        val secondaryStr = if (extraLines.isEmpty()) null else extraLines.joinToString("\n")
        if (dualSubtitleLines.value.primary != primaryStr || dualSubtitleLines.value.secondary != secondaryStr) {
            dualSubtitleLines.value = SubtitleOverlayLines(primary = primaryStr, secondary = secondaryStr)
        }
        val allLines = primaryLines + extraLines
        val combined = if (allLines.isEmpty()) null else allLines.joinToString("\n")
        if (activeSubtitleText.value != combined) {
            activeSubtitleText.value = combined
        }
    }

    fun activeSubtitleCues(): List<PlaybackRules.SubtitleCue> {
        for (tId in selectedSubTracks) {
            val cues = cuesForTrackId(tId)
            if (cues.isNotEmpty()) return cues
        }
        if (autoSubCues.isNotEmpty()) return autoSubCues
        if (parsedCues.isNotEmpty()) return parsedCues
        if (legacyFolderCues.isNotEmpty()) return legacyFolderCues
        externalCuesBySpuId.values.firstOrNull { it.isNotEmpty() }?.let { return it }
        embeddedCuesByTrack.values.firstOrNull { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    fun exportSubtitleShareUri(context: Context, file: java.io.File): Uri? {
        if (!file.exists()) return null
        val authority = "${context.packageName}.fileprovider"
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        }.getOrElse {
            runCatching {
                val staged = java.io.File(context.cacheDir, file.name)
                file.copyTo(staged, overwrite = true)
                androidx.core.content.FileProvider.getUriForFile(context, authority, staged)
            }.getOrNull()
        }
    }

    fun audioDelay(ms: Long) {
        player.setAudioDelay(ms * 1000)
        state.value = state.value.copy(audioDelay = ms)
    }

    fun subtitleDelay(ms: Long) {
        player.setSpuDelay(ms * 1000)
        state.value = state.value.copy(subtitleDelay = ms)
        updateSubtitleTextAt(nativePosition())
    }

    private var activeCues: List<Long> = emptyList()
    private var legacyFolderCues: List<PlaybackRules.SubtitleCue> = emptyList()

    fun loadCues(text: String) {
        legacyFolderCues = PlaybackRules.parseSubtitleCues(text)
        activeCues = legacyFolderCues.map { it.startMs }
        if (legacyFolderCues.isNotEmpty()) {
            player.spuTrack = -1
        }
        updateSubtitleTextAt(nativePosition())
    }

    fun loadAutoSubCues(cues: List<PlaybackRules.SubtitleCue>, userTriggered: Boolean = true) {
        autoSubCues = cues.sortedBy { it.startMs }
        if (userTriggered) {
            userModifiedSubtitles = true
            selectedSubTracks.add(9999)
        } else if (!userModifiedSubtitles || 9999 in selectedSubTracks) {
            selectedSubTracks.add(9999)
        }
        refreshTracks(false)
        if (9999 in selectedSubTracks) {
            syncSubtitleTracks("AI Subtitles", persistName = false)
        }
    }

    private fun resolveLocalFile(uri: Uri, item: MediaItem? = null): java.io.File? {
        if (uri.scheme == "file") {
            return uri.path?.let { java.io.File(it) }?.takeIf { it.exists() && it.canRead() }
        }
        if (uri.scheme == "content" && uri.authority == "media") {
            runCatching {
                val id = ContentUris.parseId(uri)
                val isAudio = uri.toString().contains("audio")
                val tableUri = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Library.queryCursorWithPending(
                    this,
                    tableUri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns._ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrBlank()) {
                            val file = java.io.File(path)
                            if (file.exists() && file.canRead()) return file
                        }
                    }
                }
            }
            runCatching {
                Library.queryCursorWithPending(
                    this,
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrBlank()) {
                            val file = java.io.File(path)
                            if (file.exists() && file.canRead()) return file
                        }
                    }
                }
            }
        }
        if (item != null) {
            val candidate = Library.findPhysicalMediaFile(item)
            if (candidate != null && candidate.exists() && candidate.canRead()) return candidate
        }
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) return file
        }
        return null
    }

    private fun readSubtitleText(file: java.io.File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val encoding = SubtitleStyle.load(this).encoding
        val charset = if (encoding.isNotBlank() && encoding != "UTF-16") {
            runCatching { java.nio.charset.Charset.forName(encoding) }.getOrDefault(Charsets.UTF_8)
        } else Charsets.UTF_8
        return String(bytes, offset, bytes.size - offset, charset)
    }

    fun startAutoSubtitles(
        context: Context,
        translateToEnglish: Boolean = false,
        spokenLanguageHint: String? = null,
        translateAfterTo: SubtitleTranslator.LanguageSpec? = null,
    ) {
        val item = state.value.item ?: return
        if (autoSubStatus.value is AutoSubStatus.Processing) return

        val useDirectWhisperEnglish = translateToEnglish ||
            (translateAfterTo?.code == "en" && spokenLanguageHint.isNullOrBlank())
        val targetAfter = if (useDirectWhisperEnglish) null else translateAfterTo

        val groqKey = SpeechToTextClient.activeGroqKey(context)
        val deepgramKey = SpeechToTextClient.activeDeepgramKey(context)
        if (useDirectWhisperEnglish && groqKey.isBlank()) {
            autoSubStatus.value = AutoSubStatus.Error("Add AI Primary key for Direct English mode")
            return
        }
        if (groqKey.isBlank() && deepgramKey.isBlank()) {
            autoSubStatus.value = AutoSubStatus.Error("Configure an AI Subtitles API key in Settings or AI Subtitles")
            return
        }

        val validAudioTracks = state.value.audioTracks.filter { it.first >= 0 }
        val englishOrdinal = validAudioTracks.indexOfFirst { (_, label) ->
            val lower = label.lowercase(java.util.Locale.ROOT)
            lower.contains("english") || Regex("""\b(en|eng)\b""").containsMatchIn(lower)
        }
        val selectedOrdinal = validAudioTracks.indexOfFirst { it.first == state.value.audioTrack }
        val preferredOrdinal = if (useDirectWhisperEnglish || !spokenLanguageHint.isNullOrBlank()) {
            selectedOrdinal
        } else if (englishOrdinal >= 0) {
            englishOrdinal
        } else {
            selectedOrdinal
        }

        val targetUri = item.uri
        val targetTitle = item.title
        autoSubStatus.value = AutoSubStatus.Processing("Preparing audio...", 5)
        scope.launch(Dispatchers.IO) {
            val shownPartial = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val uri = Uri.parse(targetUri)
                val result = SpeechToTextClient.extractAndTranscribeCascade(
                    context = context,
                    uri = uri,
                    cacheDir = context.cacheDir,
                    preferredAudioOrdinal = preferredOrdinal,
                    preferEnglishFirst = !useDirectWhisperEnglish && spokenLanguageHint.isNullOrBlank(),
                    groqKey = groqKey,
                    deepgramKey = deepgramKey,
                    translateToEnglish = useDirectWhisperEnglish,
                    languageHint = spokenLanguageHint,
                    fallbackChunkProvider = {
                        val fallbackFile = resolveLocalFile(uri)?.takeIf { it.length() <= SpeechToTextClient.MAX_GROQ_CHUNK_BYTES }
                        fallbackFile?.let { SpeechToTextClient.ExtractedChunk(it, 0L, "audio/m4a") }
                    },
                    isCancelled = { released || state.value.item?.uri != targetUri },
                    onStageProgress = { label, pct ->
                        if (!released && state.value.item?.uri == targetUri) {
                            val scaledPct = if (targetAfter != null) (pct * 0.65f).toInt().coerceIn(5, 65) else pct
                            autoSubStatus.value = AutoSubStatus.Processing(label, scaledPct)
                        }
                    },
                    onPartialCues = { partialCues ->
                        if (targetAfter == null && partialCues.isNotEmpty() && !released && state.value.item?.uri == targetUri) {
                            val firstLive = shownPartial.compareAndSet(false, true)
                            scope.launch(Dispatchers.Main) {
                                if (!released && state.value.item?.uri == targetUri) {
                                    loadAutoSubCues(partialCues, userTriggered = firstLive)
                                }
                            }
                        }
                    }
                )

                if (released || state.value.item?.uri != targetUri) return@launch
                val videoFile = resolveLocalFile(uri)
                val baseCues = SpeechToTextClient.buildSubtitleCues(result.words)

                val finalCues: List<PlaybackRules.SubtitleCue>
                val savedFile: java.io.File?
                val noteLabel: String?
                if (targetAfter != null) {
                    autoSubStatus.value = AutoSubStatus.Processing("Translating to ${targetAfter.label}...", 68)
                    val tr = SubtitleTranslator.translateCues(
                        context = context,
                        cues = baseCues,
                        target = targetAfter,
                        videoTitle = targetTitle,
                        videoFile = videoFile,
                        isCancelled = { released || state.value.item?.uri != targetUri },
                        onProgress = { label, pct ->
                            if (!released && state.value.item?.uri == targetUri) {
                                val scaledPct = 66 + ((pct / 100.0) * 30.0).toInt().coerceIn(0, 30)
                                autoSubStatus.value = AutoSubStatus.Processing(label, scaledPct)
                            }
                        },
                        onPartialCues = { liveCues ->
                            if (liveCues.isNotEmpty() && !released && state.value.item?.uri == targetUri) {
                                val firstLive = shownPartial.compareAndSet(false, true)
                                scope.launch(Dispatchers.Main) {
                                    if (!released && state.value.item?.uri == targetUri) {
                                        loadAutoSubCues(liveCues, userTriggered = firstLive)
                                    }
                                }
                            }
                        },
                    )
                    finalCues = tr.cues
                    savedFile = tr.savedFile
                    noteLabel = "${targetAfter.label} SRT"
                } else {
                    autoSubStatus.value = AutoSubStatus.Processing("Syncing subtitle timestamps...", 94)
                    finalCues = baseCues
                    savedFile = SpeechToTextClient.saveSrtFile(
                        context = context,
                        videoTitle = targetTitle,
                        cues = baseCues,
                        videoFile = videoFile,
                        langSuffix = if (useDirectWhisperEnglish) "en" else spokenLanguageHint,
                    )
                    noteLabel = if (useDirectWhisperEnglish) "Direct English" else null
                }

                if (savedFile != null && savedFile.exists()) {
                    Store.prefs(this@PlaybackService).edit().putString("custom_sub_$targetUri", savedFile.absolutePath).apply()
                }

                withContext(Dispatchers.Main) {
                    if (released || state.value.item?.uri != targetUri) return@withContext
                    autoSubStatus.value = AutoSubStatus.Success(
                        file = savedFile,
                        count = finalCues.size,
                        provider = result.provider,
                        note = noteLabel
                    )
                    loadAutoSubCues(finalCues, userTriggered = !shownPartial.get() || 9999 in selectedSubTracks)
                    android.widget.Toast.makeText(
                        context,
                        "AI Subtitles ready (${finalCues.size} lines)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                if (released || state.value.item?.uri != targetUri) {
                    autoSubStatus.value = AutoSubStatus.Idle
                    return@launch
                }
                val cleanErr = SpeechToTextClient.sanitizeErrorMessage(e.message)
                autoSubStatus.value = AutoSubStatus.Error(cleanErr)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "AI Subtitles error: $cleanErr", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun translateActiveSubtitles(context: Context, target: SubtitleTranslator.TargetLanguage) {
        translateSubtitleTrack(context, target.toLanguageSpec(), sourceTrackId = null, dualMode = false)
    }

    fun translateSubtitleTrack(
        context: Context,
        target: SubtitleTranslator.LanguageSpec,
        sourceTrackId: Int? = null,
        dualMode: Boolean = false,
    ) {
        val item = state.value.item ?: return
        if (autoSubStatus.value is AutoSubStatus.Processing) return
        val sourceCues = if (sourceTrackId != null) {
            cuesForTrackId(sourceTrackId).ifEmpty { activeSubtitleCues() }
        } else {
            activeSubtitleCues()
        }
        if (sourceCues.isEmpty()) {
            autoSubStatus.value = AutoSubStatus.Error("Load or generate subtitles first")
            return
        }
        val targetUri = item.uri
        val targetTitle = item.title
        autoSubStatus.value = AutoSubStatus.Processing("Translating to ${target.label}...", 5)
        scope.launch(Dispatchers.IO) {
            val shownPartial = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val uri = Uri.parse(targetUri)
                val videoFile = resolveLocalFile(uri)
                val res = SubtitleTranslator.translateCues(
                    context = context,
                    cues = sourceCues,
                    target = target,
                    videoTitle = targetTitle,
                    videoFile = videoFile,
                    isCancelled = { released || state.value.item?.uri != targetUri },
                    onProgress = { label, pct ->
                        if (!released && state.value.item?.uri == targetUri) {
                            autoSubStatus.value = AutoSubStatus.Processing(label, pct)
                        }
                    },
                    onPartialCues = { liveCues ->
                        if (liveCues.isNotEmpty() && !released && state.value.item?.uri == targetUri) {
                            val first = shownPartial.compareAndSet(false, true)
                            scope.launch(Dispatchers.Main) {
                                if (!released && state.value.item?.uri == targetUri) {
                                    if (!dualMode && first) {
                                        selectedSubTracks.clear()
                                    } else if (dualMode && sourceTrackId != null) {
                                        selectedSubTracks.add(sourceTrackId)
                                    }
                                    loadAutoSubCues(liveCues, userTriggered = first)
                                }
                            }
                        }
                    },
                )
                if (released || state.value.item?.uri != targetUri) return@launch
                withContext(Dispatchers.Main) {
                    if (released || state.value.item?.uri != targetUri) return@withContext
                    val savedFile = res.savedFile
                    if (savedFile != null && savedFile.exists()) {
                        rememberImportedSubtitle(targetUri, savedFile.absolutePath)
                        if (!dualMode) {
                            selectedSubTracks.clear()
                        } else {
                            selectedSubTracks.remove(9999)
                            if (sourceTrackId != null) selectedSubTracks.add(sourceTrackId)
                        }
                        val existingId = externalSpuPathsById.entries.firstOrNull { it.value == savedFile.absolutePath }?.key
                        if (existingId != null) {
                            externalCuesBySpuId[existingId] = res.cues
                            selectedSubTracks.add(existingId)
                            syncSubtitleTracks(savedFile.nameWithoutExtension, persistName = !dualMode)
                        } else {
                            addSubtitle(savedFile.absolutePath, selectExtra = true)
                        }
                    } else {
                        if (!dualMode) selectedSubTracks.clear()
                        loadAutoSubCues(res.cues, userTriggered = true)
                    }
                    autoSubStatus.value = AutoSubStatus.Success(
                        file = savedFile,
                        count = res.cues.size,
                        provider = res.engineLabel,
                        note = "${target.label} SRT",
                    )
                    android.widget.Toast.makeText(
                        context,
                        "${target.label} subtitles ready (${res.cues.size})",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                if (released || state.value.item?.uri != targetUri) {
                    autoSubStatus.value = AutoSubStatus.Idle
                    return@launch
                }
                val cleanErr = SpeechToTextClient.sanitizeErrorMessage(e.message)
                autoSubStatus.value = AutoSubStatus.Error(cleanErr)
            }
        }
    }

    fun seekCue(forward: Boolean): Boolean {
        val current = timeline.value.position
        val target = if (forward) {
            PlaybackRules.findNextCue(activeCues, current) ?: (current + 5000L)
        } else {
            PlaybackRules.findPrevCue(activeCues, current) ?: (current - 5000L)
        }
        val safe = PlaybackRules.safeSeek(target, timeline.value.duration)
        seek(safe)
        return true
    }

    fun addSubtitle(path: String, selectExtra: Boolean = false): Boolean {
        if (path in externalSubtitles) {
            if (selectExtra) {
                val existingId = externalSpuPathsById.entries.firstOrNull { it.value == path }?.key
                if (existingId != null) {
                    userModifiedSubtitles = true
                    selectedSubTracks.add(existingId)
                    syncSubtitleTracks(persistName = false)
                }
            }
            return true
        }

        val parsed = runCatching {
            val file = java.io.File(path)
            if (file.isFile && file.length() < 5 * 1024 * 1024) {
                PlaybackRules.parseSubtitleCues(readSubtitleText(file))
            } else emptyList()
        }.getOrDefault(emptyList())

        val existingBefore = player.spuTracks?.map { it.id }?.filter { it >= 0 }?.toSet().orEmpty()
        val req = ExternalAddRequest(
            path = path,
            label = java.io.File(path).nameWithoutExtension,
            cues = parsed,
            selectExtra = selectExtra,
            knownIdsBeforeAdd = existingBefore,
        )
        pendingExternalAdds.addLast(req)

        val result = player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, path, false)
        if (!result) {
            pendingExternalAdds.remove(req)
            return false
        }

        externalSubtitles.add(path)
        refreshTracks(false)
        subtitleCuesRevision.value++
        if (selectExtra) syncSubtitleTracks(persistName = false)
        return true
    }

    /** FreeType is configured at LibVLC construction, so reopen the current item in place. */
    fun subtitleStyle(style: SubtitleStyle) {
        val previous = SubtitleStyle.load(this)
        if (previous == style) return
        style.save(this)
        subtitleStyleRevision.value++
        if (previous.options() == style.options() || (resolvePrimaryHwTrack() < 0 && previous.encoding == style.encoding)) {
            updateSubtitlePlacement(controlsVisible)
            return
        }
        endTemporarySpeed()
        val prior = state.value.copy(playing = state.value.playing && !requestedPause)
        if (prior.item == null) return
        opening?.cancel()
        pendingStyleRestore = prior
        pendingStyleTracks = prior
        player.setEventListener(null)
        player.stop()
        if (videoAttached) player.detachViews()
        activeDescriptor?.close()
        activeDescriptor = null
        player.release()
        vlc.release()
        val initialEffects = AudioEffectSettings.load(this)
        effectSettings.value = initialEffects
        vlc = createVlc(initialEffects)
        player = MediaPlayer(vlc).apply { setUseOrientationFromBounds(true) }
        audioEffects.apply(player, effectSettings.value)
        applyVolume()
        bindPlayerEvents()
        val useTexture = Store.prefs(this).getBoolean("use_texture_view", false)
        videoLayout?.takeIf { !surfaceSuspended }?.let { player.attachViews(it, null, useTexture, true) }
        openCurrent(prior.position, restore = false, keepSubtitleChoice = true)
    }

    fun updateSubtitlePosition(bottomPercent: Int) {
        val style = SubtitleStyle.load(this).copy(bottomMargin = bottomPercent.coerceIn(0, 40), legacyMargin = null)
        style.save(this)
        subtitleStyleRevision.value++
        updateSubtitlePlacement(controlsVisible)
    }

    fun updateSubtitleScale(scalePercent: Int) {
        val style = SubtitleStyle.load(this).copy(scale = scalePercent.coerceIn(50, 300))
        style.save(this)
        subtitleStyleRevision.value++
        val layout = videoLayout ?: return
        layout.post {
            val surface = layout.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles) ?: return@post
            val scaleFactor = (scalePercent / 100f).coerceIn(0.5f, 3.0f)
            surface.pivotX = surface.width / 2f
            surface.pivotY = surface.height.toFloat()
            surface.scaleX = scaleFactor
            surface.scaleY = scaleFactor * (style.lineSpacing / 100f).coerceIn(0.8f, 2.0f)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val isAudio = state.value.item?.audio == true
        val isPlaying = state.value.playing
        if (isAudio && isPlaying) {
            // Keep music playing in background foreground service just like Spotify
            updateNotification()
        } else {
            stopPlayback()
        }
        super.onTaskRemoved(rootIntent)
    }

    fun stopPlayback() {
        if (released) return
        saveFinalPosition()
        releasePlayback()
        state.value = PlayerState(closed = false)
        timeline.value = PlaybackTimeline()
        stopSelf()
    }

    private fun saveFinalPosition() {
        val value = state.value
        val item = value.item ?: return
        val done = finished || PlaybackRules.completed(value.position, value.duration)
        Thread {
            Store.db(applicationContext).dao().save(SavedMedia().apply {
                uri = item.uri; title = item.title; watched = done
                position = if (done) 0 else value.position; updated = System.currentTimeMillis()
            })
        }.start()
    }

    private fun releasePlayback() {
        if (released) return
        endTemporarySpeed()
        released = true
        audioManager.unregisterAudioDeviceCallback(routeCallback)
        Library.videoPlaybackActive.value = false
        opening?.cancel()
        pendingStyleRestore = null
        pendingPlayback = false
        clearSeek()
        dimensionProbe?.cancel()
        artworkJob?.cancel()
        notifSyncJob?.cancel()
        bufferingWatchdog?.cancel()
        scope.cancel()
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        vlc.release()
        activeDescriptor?.close()
        activeDescriptor = null
        abandonFocus()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(10)
        nm.cancel(11)
        if (::dynamicIsland.isInitialized) dynamicIsland.hide()
    }

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focus)
        focusHeld = false
    }

    private fun savePosition(watched: Boolean = false) {
        val current = state.value
        val item = current.item ?: return
        val complete =
            watched || finished || PlaybackRules.completed(current.position, current.duration)
        val row =
            SavedMedia().apply {
                uri = item.uri
                title = item.title
                position = if (complete) 0 else current.position
                updated = System.currentTimeMillis()
                this.watched = complete
            }
        scope.launch(Dispatchers.IO) { Store.db(this@PlaybackService).dao().save(row) }
    }

    private fun action(action: String) =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun scaleForNotification(source: Bitmap): Bitmap {
        val maxDim = 256
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        if (w <= maxDim && h <= maxDim) return source
        val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(source, nw, nh, true) }.getOrDefault(source)
    }

    private fun handleOnlineAudioFallback(currentItem: MediaItem) {
        if (retried) return
        val isLiveFlac = currentItem.sourceVideoId.startsWith("flac_") || currentItem.uri.contains("flac", true) || currentItem.uri.contains("motherearth", true)
        if (isLiveFlac) return
        retried = true
        state.value = state.value.copy(buffering = true, error = null)
        val failedIndex = index
        val failedUri = currentItem.uri
        scope.launch(Dispatchers.IO) {
            val fallback = AudioStreamResolver.resolveAlternateStream(
                title = currentItem.title,
                artist = currentItem.artist,
                ytVideoId = extractVideoId(currentItem),
                rawThumbUrl = currentItem.thumbUri ?: "",
                excludeUrl = failedUri,
            )
            withContext(Dispatchers.Main) {
                if (state.value.item?.title != currentItem.title) return@withContext
                if (fallback != null && fallback.streamUrl.isNotBlank() && fallback.streamUrl != failedUri) {
                    val updated = currentItem.copy(
                        uri = fallback.streamUrl,
                        thumbUri = fallback.hdThumbUrl.ifBlank { currentItem.thumbUri ?: "" },
                        streamUserAgent = fallback.userAgent,
                    )
                    val mutable = queue.toMutableList()
                    if (failedIndex in mutable.indices) {
                        mutable[failedIndex] = updated
                        queue = mutable
                    } else {
                        queue = listOf(updated)
                        index = 0
                    }
                    queueItems.value = queue
                    openCurrent(0L, restore = false, autoPlay = true)
                } else if (index + 1 < queue.size) {
                    next()
                } else {
                    state.value = state.value.copy(
                        error = "Unable to stream this track right now. Tap to retry or pick another song.",
                        playing = false,
                        buffering = false,
                    )
                    abandonFocus()
                    updateNotification()
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }
    }

    private fun loadNotificationArtwork(targetItem: MediaItem) {
        val targetKey = "${targetItem.uri}::${targetItem.title}"
        if (sessionArtKey == targetKey && sessionArtworkBitmap != null) return
        artworkJob?.cancel()
        sessionArtKey = targetKey

        // 0ms instant RAM cache check (already loaded by Search / MusicPlayer / Library)
        val instantBmp = targetItem.thumbUri?.takeIf { it.isNotBlank() }?.let { OnlineImageCache.get(it) }
            ?: OnlineImageCache.get("libart::${targetItem.uri}::${targetItem.title}".lowercase())
        if (instantBmp != null) {
            val scaled = scaleForNotification(instantBmp)
            sessionArtworkBitmap = scaled
            val mood = extractAdaptiveMoodColors(scaled)
            sessionArtworkColor = android.graphics.Color.rgb(
                (mood.primary.red * 255).toInt(),
                (mood.primary.green * 255).toInt(),
                (mood.primary.blue * 255).toInt(),
            )
            return
        }

        // Clear stale artwork from the previous track immediately so old art never lingers
        sessionArtworkBitmap = null
        sessionArtworkColor = android.graphics.Color.rgb(12, 24, 42)

        artworkJob = scope.launch(Dispatchers.IO) {
            try {
                val loaded: Bitmap? = if (targetItem.audio) {
                    TrackArtworkStore.resolveAndCacheCover(
                        context = this@PlaybackService,
                        uri = targetItem.uri,
                        title = targetItem.title,
                        artist = targetItem.artist,
                        existingThumbUri = targetItem.thumbUri,
                    )
                } else {
                    runCatching { Library.thumbnail(this@PlaybackService, targetItem) }.getOrNull()
                }
                if (loaded != null) {
                    val scaled = scaleForNotification(loaded)
                    val mood = extractAdaptiveMoodColors(scaled)
                    val dominantArgb = android.graphics.Color.rgb(
                        (mood.primary.red * 255).toInt(),
                        (mood.primary.green * 255).toInt(),
                        (mood.primary.blue * 255).toInt(),
                    )
                    withContext(Dispatchers.Main) {
                        val active = state.value.item
                        if (!released && active != null && "${active.uri}::${active.title}" == targetKey) {
                            sessionArtworkBitmap = scaled
                            sessionArtworkColor = dominantArgb
                            updateSession(forceMetadata = true)
                            updateNotification()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun notification(): android.app.Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).putExtra("show_player", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val curItem = state.value.item
        val title = curItem?.title?.takeIf { it.isNotBlank() } ?: "Mica One"
        val artist = curItem?.artist?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Artist", true) }
            ?: "Mica One"
        val builder = NotificationCompat.Builder(this, "playback")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Mica One")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(sessionArtworkColor)
            .setColorized(true)
            .setContentIntent(open)
            .setDeleteIntent(action("stop"))
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(state.value.playing || state.value.buffering)
            .addAction(
                if (shuffleEnabled.value) R.drawable.ic_notif_shuffle else R.drawable.ic_notif_repeat,
                if (shuffleEnabled.value) "Shuffle" else "Repeat",
                action(if (shuffleEnabled.value) "toggle_repeat" else "toggle_shuffle")
            )
            .addAction(R.drawable.ic_notif_prev, "Previous", action("previous"))
            .addAction(
                if (state.value.playing) R.drawable.ic_notif_pause
                else R.drawable.ic_notif_play,
                if (state.value.playing) "Pause" else "Play",
                action(if (state.value.playing) "pause" else "play"),
            )
            .addAction(R.drawable.ic_notif_next, "Next", action("next"))
            .addAction(
                if (currentTrackLiked) R.drawable.ic_notif_liked else R.drawable.ic_notif_like_add,
                if (currentTrackLiked) "Unlike" else "Like",
                action("toggle_like")
            )
            .setStyle(
                CompatMediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
        sessionArtworkBitmap?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun shouldShowNotification(): Boolean {
        val itm = state.value.item ?: return false
        if (itm.audio) return true
        if (PlaybackRules.isStreamUrl(itm.uri) ||
            itm.uri.startsWith("http://") ||
            itm.uri.startsWith("https://") ||
            itm.uri.startsWith("resolve://") ||
            itm.uri.startsWith("yt_") ||
            itm.uri.startsWith("saavn_") ||
            itm.folder == "Network Streams"
        ) {
            return true
        }
        return false
    }

    private fun foreground() {
        if (!shouldShowNotification()) {
            session.isActive = false
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(10)
            nm.cancel(11)
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            if (::dynamicIsland.isInitialized) dynamicIsland.hide()
            return
        }
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(
                10,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        else startForeground(10, notification())
        updateDynamicIsland()
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        if (inForeground) {
            if (::dynamicIsland.isInitialized) dynamicIsland.hide()
        } else {
            updateDynamicIsland()
        }
    }

    fun refreshNotification() {
        if (released) return
        if (state.value.item != null) {
            updateNotification()
        } else if (::dynamicIsland.isInitialized) {
            dynamicIsland.hide()
        }
    }

    private fun refreshLikedStatus(item: MediaItem) {
        if (likedTrackUri == item.uri) return
        likedTrackUri = item.uri
        scope.launch(Dispatchers.IO) {
            val liked = runCatching {
                Store.db(this@PlaybackService).dao().entries("Favorites").any { it.uri == item.uri }
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!released && state.value.item?.uri == item.uri && currentTrackLiked != liked) {
                    currentTrackLiked = liked
                    isCurrentTrackLiked.value = liked
                    updateSession(forceState = true)
                    updateDynamicIsland()
                }
            }
        }
    }

    fun toggleCurrentTrackLike() {
        val item = state.value.item ?: return
        val newLiked = !currentTrackLiked
        currentTrackLiked = newLiked
        isCurrentTrackLiked.value = newLiked
        likedTrackUri = item.uri
        updateSession(forceState = true)
        updateDynamicIsland()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = Store.db(this@PlaybackService).dao()
                if (newLiked) {
                    dao.add(PlaylistEntry().apply {
                        playlist = "Favorites"
                        uri = item.uri
                        title = item.title
                        added = System.currentTimeMillis()
                    })
                    sessionArtworkBitmap?.let { bmp ->
                        TrackArtworkStore.saveBitmap(
                            this@PlaybackService,
                            item.uri,
                            item.title,
                            item.artist,
                            bmp,
                            item.thumbUri ?: "",
                        )
                    }
                } else {
                    dao.remove("Favorites", item.uri)
                }
            }
        }
    }

    private fun scheduleMediaNotificationSync() {
        notifSyncJob?.cancel()
        notifSyncJob = scope.launch {
            delay(180L)
            if (released || state.value.item == null) return@launch
            if (!shouldShowNotification()) {
                session.isActive = false
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(10)
                nm.cancel(11)
                return@launch
            }
            updateSession(forceMetadata = true, forceState = true)
            getSystemService(NotificationManager::class.java).notify(10, notification())
            updateDynamicIsland()
        }
    }

    private fun updateDynamicIsland() {
        if (!::dynamicIsland.isInitialized) return
        val cur = state.value
        val curItem = cur.item
        if (curItem == null || !curItem.audio) {
            dynamicIsland.hide()
            return
        }
        refreshLikedStatus(curItem)
        val title = curItem.title.takeIf { it.isNotBlank() } ?: "Mica One"
        val artist = curItem.artist.takeIf { it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Artist", true) }
            ?: "Mica One"
        dynamicIsland.update(
            DynamicIslandController.Snapshot(
                title = title,
                artist = artist,
                playing = cur.playing,
                positionMs = cur.position.coerceAtLeast(0L),
                durationMs = cur.duration.coerceAtLeast(0L),
                artwork = sessionArtworkBitmap,
                accentColor = sessionArtworkColor,
                liked = currentTrackLiked,
                shuffle = shuffleEnabled.value,
            ),
            appInForeground = appInForeground,
        )
    }

    private fun updateNotification() {
        state.value.item?.let { refreshLikedStatus(it) }
        val nm = getSystemService(NotificationManager::class.java)
        if (!shouldShowNotification()) {
            session.isActive = false
            nm.cancel(10)
            nm.cancel(11)
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            if (::dynamicIsland.isInitialized) dynamicIsland.hide()
            return
        }
        updateSession()
        nm.notify(10, notification())
        nm.cancel(11)
        updateDynamicIsland()
        val curItem = state.value.item
        MicaMusicWidgetProvider.updateWidget(
            this,
            state.value.playing,
            curItem?.title ?: "MICA",
            curItem?.artist ?: "The Universe of Entertainment",
            sessionArtworkBitmap,
            posMs = state.value.position,
            durMs = state.value.duration,
            isLiked = currentTrackLiked,
            isRepeat = repeatMode.value != "off"
        )
    }

    private fun updateSession(forceMetadata: Boolean = false, forceState: Boolean = false) {
        val value = state.value
        val curItem = value.item
        val title = curItem?.title?.takeIf { it.isNotBlank() } ?: "Mica One"
        val artist = curItem?.artist?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Artist", true) }
            ?: "Mica One"
        val album = curItem?.folder?.takeIf { it.isNotBlank() && it != "Opened files" } ?: "Mica One"
        val dur = value.duration.coerceAtLeast(0L)
        val art = sessionArtworkBitmap
        val artIdentity = if (art != null) "${sessionArtKey}#${art.hashCode()}" else "none"

        val metadataChanged = forceMetadata ||
            sessionMediaKey != curItem?.uri ||
            sessionTitle != title ||
            sessionArtist != artist ||
            sessionDuration != dur ||
            sessionArtIdentity != artIdentity

        if (curItem != null && metadataChanged) {
            val compactMediaId = "${curItem.uri.hashCode()}_${title.hashCode()}"
            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, compactMediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            if (art != null) {
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            }
            curItem.thumbUri?.takeIf { it.startsWith("http") }?.let { httpArt ->
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, httpArt)
            }
            session.setMetadata(metaBuilder.build())
            sessionMediaKey = curItem.uri
            sessionTitle = title
            sessionArtist = artist
            sessionDuration = dur
            sessionArtIdentity = artIdentity
        }
        val status = when {
            curItem == null -> PlaybackStateCompat.STATE_STOPPED
            value.buffering && !value.playing -> PlaybackStateCompat.STATE_BUFFERING
            value.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val now = SystemClock.elapsedRealtime()
        // Android extrapolates the position from speed and timestamp. Avoid
        // rebroadcasting unchanged state on every UI timeline tick unless forced.
        if (
            !forceState &&
                status == sessionStatus &&
                value.rate == sessionRate &&
                kotlin.math.abs(value.position - sessionPosition) < 2000 &&
                now - sessionSentAt < 2000
        )
            return
        session.setShuffleMode(if (shuffleEnabled.value) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        val repMode = when (repeatMode.value) {
            "one" -> PlaybackStateCompat.REPEAT_MODE_ONE
            "all" -> PlaybackStateCompat.REPEAT_MODE_ALL
            else -> PlaybackStateCompat.REPEAT_MODE_NONE
        }
        session.setRepeatMode(repMode)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                        PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                )
                .addCustomAction(
                    "toggle_like",
                    if (currentTrackLiked) "Unlike" else "Like",
                    if (currentTrackLiked) R.drawable.ic_notif_liked else R.drawable.ic_notif_like_add,
                )
                .addCustomAction(
                    if (shuffleEnabled.value) "toggle_repeat" else "toggle_shuffle",
                    if (shuffleEnabled.value) "Shuffle On (Tap for Repeat)" else "Repeat On (Tap for Shuffle)",
                    if (shuffleEnabled.value) R.drawable.ic_notif_shuffle else R.drawable.ic_notif_repeat,
                )
                .setState(
                    status,
                    value.position.coerceAtLeast(0L),
                    if (status == PlaybackStateCompat.STATE_PLAYING) value.rate else 0f,
                    now,
                )
                .build()
        )
        sessionStatus = status
        sessionRate = value.rate
        sessionSentAt = now
        sessionPosition = value.position
    }

    override fun onDestroy() {
        isServiceRunning = false
        demuxJob?.cancel()
        if (!released) {
            saveFinalPosition()
            releasePlayback()
        }
        unregisterReceiver(noisy)
        if (::dynamicIsland.isInitialized) dynamicIsland.hide()
        session.release()
        super.onDestroy()
    }
}
