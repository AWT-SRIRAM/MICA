package app.msplayer.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class TrackArtMeta(
    val artist: String = "",
    val thumbUrl: String = "",
    val localCoverPath: String = "",
)

/**
 * Persistent lightweight store for track artist metadata & offline cover art thumbnails
 * powering Library (Liked Songs, Downloaded, My Top 50).
 */
object TrackArtworkStore {
    private const val META_FILE = "track_art_meta_v1.json"
    private val lock = Any()
    private var cachedRoot: JSONObject? = null

    private fun coversDir(context: Context): File =
        File(context.filesDir, "track_covers").apply { if (!exists()) mkdirs() }

    private fun keyHash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.lowercase().trim().toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun titleKey(title: String): String =
        "title::" + title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").lowercase().trim()

    private fun loadRoot(context: Context): JSONObject {
        cachedRoot?.let { return it }
        val f = File(context.filesDir, META_FILE)
        val parsed = runCatching {
            if (f.exists() && f.length() > 2) JSONObject(f.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
        cachedRoot = parsed
        return parsed
    }

    private fun persistRoot(context: Context, root: JSONObject) {
        runCatching {
            File(context.filesDir, META_FILE).writeText(root.toString())
        }
    }

    fun saveMeta(
        context: Context,
        uri: String,
        title: String,
        artist: String = "",
        thumbUrl: String = "",
        localCoverPath: String = "",
    ) {
        synchronized(lock) {
            val root = loadRoot(context)
            fun upsert(k: String) {
                if (k.isBlank()) return
                val existing = root.optJSONObject(k) ?: JSONObject()
                if (artist.isNotBlank() && !artist.equals("Unknown", true) && !artist.equals("Unknown Artist", true)) {
                    existing.put("artist", artist)
                }
                if (thumbUrl.isNotBlank() && (thumbUrl.startsWith("http") || thumbUrl.startsWith("spotify_track://"))) {
                    existing.put("thumbUrl", thumbUrl)
                }
                if (localCoverPath.isNotBlank()) {
                    existing.put("localCoverPath", localCoverPath)
                }
                root.put(k, existing)
            }
            upsert(uri)
            if (title.isNotBlank()) upsert(titleKey(title))
            persistRoot(context, root)
        }
    }

    fun saveBitmap(
        context: Context,
        uri: String,
        title: String,
        artist: String,
        bitmap: Bitmap,
        thumbUrl: String = "",
    ): File? {
        val file = File(coversDir(context), "${keyHash(title.ifBlank { uri })}.jpg")
        val ok = runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            true
        }.getOrDefault(false)
        if (ok && file.exists() && file.length() > 100) {
            saveMeta(context, uri, title, artist, thumbUrl, file.absolutePath)
            return file
        }
        return null
    }

    fun findMeta(context: Context, uri: String, title: String): TrackArtMeta? {
        synchronized(lock) {
            val root = loadRoot(context)
            val obj = root.optJSONObject(uri)
                ?: (if (title.isNotBlank()) root.optJSONObject(titleKey(title)) else null)
                ?: return null
            return TrackArtMeta(
                artist = obj.optString("artist", ""),
                thumbUrl = obj.optString("thumbUrl", ""),
                localCoverPath = obj.optString("localCoverPath", ""),
            )
        }
    }

    fun downloadAndCacheCover(
        context: Context,
        uri: String,
        title: String,
        artist: String,
        thumbUrl: String,
        forceOverwrite: Boolean = false,
    ): File? {
        if (thumbUrl.isBlank() || !thumbUrl.startsWith("http")) return null
        val file = File(coversDir(context), "${keyHash(title.ifBlank { uri })}.jpg")
        if (!forceOverwrite && file.exists() && file.length() > 512) {
            saveMeta(context, uri, title, artist, thumbUrl, file.absolutePath)
            return file
        }
        return runCatching {
            val conn = (URL(thumbUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size > 512) {
                    FileOutputStream(file).use { it.write(bytes) }
                    saveMeta(context, uri, title, artist, thumbUrl, file.absolutePath)
                    OnlineImageCache.evict(uri)
                    OnlineImageCache.evict(thumbUrl)
                    return@runCatching file
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Resolves album cover art for any Library track (Liked, Downloaded, Top 50) with full offline caching:
     * 1. Saved local disk cover (.jpg)
     * 2. Embedded ID3 artwork for local audio files
     * 3. Stored remote thumbUri (downloaded & cached to disk)
     * 4. One-time automatic HD cover backfill via JioSaavn/YouTube Music (cached permanently to disk)
     */
    suspend fun resolveAndCacheCover(
        context: Context,
        uri: String,
        title: String,
        artist: String,
        existingThumbUri: String? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "libart::${uri}::${title}".lowercase()
        OnlineImageCache.get(cacheKey)?.let { return@withContext it }

        val meta = findMeta(context, uri, title)
        val localPaths = listOfNotNull(
            meta?.localCoverPath?.takeIf { it.isNotBlank() },
            existingThumbUri?.takeIf { it.startsWith("file://") }?.removePrefix("file://"),
            existingThumbUri?.takeIf { it.startsWith("/") },
            File(coversDir(context), "${keyHash(title.ifBlank { uri })}.jpg").absolutePath,
        ).distinct()

        // 1. Cached local cover file on disk (0ms offline)
        for (path in localPaths) {
            val f = File(path)
            if (f.exists() && f.length() > 256) {
                val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
                if (bmp != null) {
                    OnlineImageCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            }
        }

        // 2. Embedded ID3 cover art for local content://, file:// or raw path tracks
        if (uri.startsWith("file://") || uri.startsWith("content://") || uri.startsWith("/")) {
            val embedded = runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    if (uri.startsWith("file://") || uri.startsWith("/")) {
                        val path = if (uri.startsWith("file://")) Uri.parse(uri).path else uri
                        if (!path.isNullOrBlank()) mmr.setDataSource(path)
                    } else {
                        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { fd ->
                            mmr.setDataSource(fd.fileDescriptor)
                        }
                    }
                    mmr.embeddedPicture?.let { bytes ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        val sample = (maxOf(opts.outWidth, opts.outHeight) / 256).coerceAtLeast(1)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                } finally {
                    mmr.release()
                }
            }.getOrNull()
            if (embedded != null) {
                saveBitmap(context, uri, title, artist, embedded)
                OnlineImageCache.put(cacheKey, embedded)
                return@withContext embedded
            }
        }

        // 3. Remote thumbUri from MediaItem, Spotify oEmbed, or TrackArtworkStore
        var remoteUrl = existingThumbUri?.takeIf { it.startsWith("http") }
            ?: meta?.thumbUrl?.takeIf { it.startsWith("http") }

        val spotifyTrackId = when {
            existingThumbUri?.startsWith("spotify_track://") == true -> existingThumbUri.removePrefix("spotify_track://")
            meta?.thumbUrl?.startsWith("spotify_track://") == true -> meta.thumbUrl.removePrefix("spotify_track://")
            else -> ""
        }
        if (spotifyTrackId.isNotBlank()) {
            val fetchedSpotifyThumb = SpotifyClient.resolveTrackThumbnail(spotifyTrackId)
            if (!fetchedSpotifyThumb.isNullOrBlank()) {
                remoteUrl = fetchedSpotifyThumb
            }
        }

        if (!remoteUrl.isNullOrBlank()) {
            val savedFile = downloadAndCacheCover(context, uri, title, artist, remoteUrl)
            val bmp = savedFile?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            if (bmp != null) {
                OnlineImageCache.put(cacheKey, bmp)
                return@withContext bmp
            }
        }

        // 4. One-time automatic HD artwork backfill for existing Downloaded / Liked tracks
        val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
        if (cleanTitle.length >= 2 && !cleanTitle.startsWith("202") && !cleanTitle.endsWith(".m4a")) {
            val cleanArtist = artist.takeIf { it.isNotBlank() && !it.equals("MS Player", true) && !it.equals("Unknown", true) && !it.equals("Opened files", true) } ?: ""
            val query = "$cleanTitle $cleanArtist".trim()
            val foundUrl = runCatching {
                JioSaavnClient.searchSongs(query).firstOrNull { it.thumbUrl.isNotBlank() }?.thumbUrl
                    ?: YtMusicClient.searchSongs(query).firstOrNull { it.thumbUrl.isNotBlank() }?.thumbUrl
            }.getOrNull()
            if (!foundUrl.isNullOrBlank()) {
                val savedFile = downloadAndCacheCover(context, uri, title, cleanArtist, foundUrl)
                val bmp = savedFile?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                if (bmp != null) {
                    OnlineImageCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            }
        }

        null
    }
}

data class DownloadProgress(
    val videoId: String,
    val title: String,
    val progress: Float, // 0f .. 1f
    val isComplete: Boolean = false,
    val isFailed: Boolean = false,
)

object AudioDownloadManager {

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads = _activeDownloads.asStateFlow()

    fun getDownloadDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(base, "MSPlayer")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    fun getTargetFile(title: String, artist: String): File {
        val cleanTitle = sanitizeFilename(title.ifBlank { "Track" })
        val cleanArtist = sanitizeFilename(artist.ifBlank { "Unknown" })
        val filename = if (cleanArtist != "Unknown") "$cleanTitle - $cleanArtist.m4a" else "$cleanTitle.m4a"
        return File(getDownloadDir(), filename)
    }

    fun isDownloaded(title: String, artist: String): Boolean {
        val file = getTargetFile(title, artist)
        return file.exists() && file.length() > 32 * 1024L
    }

    fun downloadSong(
        context: Context,
        scope: CoroutineScope,
        videoId: String,
        title: String,
        artist: String,
        thumbUrl: String = "",
    ) {
        if (_activeDownloads.value[videoId]?.isComplete == true) {
            Toast.makeText(context, "Already downloaded: $title", Toast.LENGTH_SHORT).show()
            return
        }

        val targetFile = getTargetFile(title, artist)
        if (targetFile.exists() && targetFile.length() > 32 * 1024L) {
            Toast.makeText(context, "Song already exists on device", Toast.LENGTH_SHORT).show()
            return
        }

        // Initialize progress
        _activeDownloads.value = _activeDownloads.value + (videoId to DownloadProgress(videoId, title, 0.05f))
        Toast.makeText(context, "Downloading \"$title\"...", Toast.LENGTH_SHORT).show()

        scope.launch(Dispatchers.IO) {
            try {
                val streamUrl = YtMusicClient.resolveStreamUrl(videoId)
                if (streamUrl.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        _activeDownloads.value = _activeDownloads.value + (videoId to DownloadProgress(videoId, title, 0f, isFailed = true))
                        Toast.makeText(context, "Could not resolve audio stream for download", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val conn = URL(streamUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    throw Exception("HTTP ${conn.responseCode}")
                }

                val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: (4 * 1024 * 1024L)
                val tempFile = File(getDownloadDir(), "${targetFile.name}.part")

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var accumulated = 0L
                        var lastProgressUpdate = System.currentTimeMillis()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            accumulated += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= 350L) {
                                lastProgressUpdate = now
                                val frac = (accumulated.toFloat() / totalBytes).coerceIn(0.05f, 0.95f)
                                _activeDownloads.value = _activeDownloads.value + (videoId to DownloadProgress(videoId, title, frac))
                            }
                        }
                    }
                }

                if (tempFile.exists() && tempFile.length() > 10 * 1024L) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)

                    // Cache offline cover art & track metadata for Library -> Downloaded
                    val fileUri = Uri.fromFile(targetFile).toString()
                    TrackArtworkStore.saveMeta(context.applicationContext, fileUri, title, artist, thumbUrl)
                    TrackArtworkStore.saveMeta(context.applicationContext, targetFile.name, title, artist, thumbUrl)
                    TrackArtworkStore.saveMeta(context.applicationContext, targetFile.absolutePath, title, artist, thumbUrl)
                    if (thumbUrl.isNotBlank()) {
                        TrackArtworkStore.downloadAndCacheCover(context.applicationContext, fileUri, title, artist, thumbUrl)
                        TrackArtworkStore.downloadAndCacheCover(context.applicationContext, targetFile.name, title, artist, thumbUrl)
                    }

                    // Index into MediaStore so it immediately appears in Music and Library tabs
                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(targetFile.absolutePath),
                        arrayOf("audio/mp4", "audio/m4a"),
                    ) { _, scannedUri ->
                        if (scannedUri != null) {
                            TrackArtworkStore.saveMeta(context.applicationContext, scannedUri.toString(), title, artist, thumbUrl)
                            if (thumbUrl.isNotBlank()) {
                                TrackArtworkStore.downloadAndCacheCover(context.applicationContext, scannedUri.toString(), title, artist, thumbUrl)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        _activeDownloads.value = _activeDownloads.value + (videoId to DownloadProgress(videoId, title, 1f, isComplete = true))
                        Toast.makeText(context, "Saved to Music/MSPlayer: $title", Toast.LENGTH_LONG).show()
                    }
                } else {
                    tempFile.delete()
                    throw Exception("Downloaded file too small")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _activeDownloads.value = _activeDownloads.value + (videoId to DownloadProgress(videoId, title, 0f, isFailed = true))
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
