package app.msplayer.offline

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object StreamResolver {

    data class ResolvedStream(
        val streamUrl: String,
        val title: String,
        val isVideo: Boolean = true,
        val userAgent: String = "",
        val thumbUrl: String = "",
        val author: String = "",
        val subtitleUrl: String? = null,
    )

    /**
     * Resolves a raw pasted or shared input (which may be a direct media URL,
     * an Android intent: URI with foreign package, a watch page with embedded stream,
     * or a YouTube video link) into a playable stream URL and display title.
     */
    suspend fun resolve(rawInput: String): ResolvedStream = withContext(Dispatchers.IO) {
        var trimmed = rawInput.trim()
        var extractedTitle: String? = null

        // 0. Extract URL from share sheet text (e.g., "Check this out https://youtu.be/...")
        val embeddedUrl = Regex("""https?://[^\s]+""").find(trimmed)?.value
        if (embeddedUrl != null && !trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("intent:")) {
            extractedTitle = trimmed.substringBefore(embeddedUrl).trim().removeSuffix(":").trim().takeIf { it.isNotBlank() }
            trimmed = embeddedUrl
        }

        // 1. Handle Android intent: URI (e.g. from MX Player, VLC, or browser intent links)
        if (trimmed.startsWith("intent:", ignoreCase = true) || trimmed.contains("#Intent;")) {
            // Extract S.title=... extra if present
            val titleRegex = Regex("""S\.title=([^;]+)""")
            titleRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { rawTitle ->
                extractedTitle = runCatching { URLDecoder.decode(rawTitle, "UTF-8") }.getOrDefault(rawTitle)
            }
            // Strip intent: prefix and #Intent fragment
            trimmed = trimmed.removePrefix("intent:").substringBefore("#Intent")
        }

        // 2. Normalize basic stream URL
        val cleanUrl = PlaybackRules.normalizeStreamUrl(trimmed)

        // 2b. Spotify link resolution (audio track / playlist direct stream)
        if (SpotifyClient.isSpotifyUrl(cleanUrl)) {
            val imported = SpotifyClient.importUrl(cleanUrl)
            if (imported != null && imported.tracks.isNotEmpty()) {
                val firstTrack = imported.tracks.first()
                val trId = firstTrack.trackId
                val lazyUri = if (trId.isNotBlank()) "resolve://spotify/${trId}::${firstTrack.title}::${firstTrack.artist}" else "resolve://spotify/${firstTrack.title}::${firstTrack.artist}"
                val specificThumb = if (trId.isNotBlank()) "spotify_track://$trId" else imported.thumbUrl
                return@withContext ResolvedStream(
                    streamUrl = lazyUri,
                    title = "${firstTrack.title} • ${firstTrack.artist}",
                    isVideo = false,
                    thumbUrl = specificThumb,
                    author = firstTrack.artist
                )
            }
        }

        // 2c. Apple Music link resolution (music.apple.com)
        if (cleanUrl.contains("music.apple.com")) {
            val appleMeta = runCatching {
                val oembedUrl = "https://music.apple.com/oembed?url=" + java.net.URLEncoder.encode(cleanUrl, "UTF-8")
                val conn = (URL(oembedUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode in 200..299) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = org.json.JSONObject(json)
                    val rawTitle = obj.optString("title", "").trim()
                    val author = obj.optString("author_name", "").trim()
                    val thumb = obj.optString("thumbnail_url", "").trim()
                    Triple(rawTitle, author, thumb)
                } else null
            }.getOrNull()

            val title = appleMeta?.first?.takeIf { it.isNotBlank() } ?: run {
                val parts = cleanUrl.substringAfter("music.apple.com/").split('/')
                val songPart = parts.getOrNull(2) ?: parts.getOrNull(1) ?: "Apple Music Track"
                songPart.replace('-', ' ')
            }
            val artist = appleMeta?.second?.takeIf { it.isNotBlank() } ?: ""
            val thumb = appleMeta?.third ?: ""
            val lazyUri = "resolve://audio/${title}::${artist}"
            return@withContext ResolvedStream(
                streamUrl = lazyUri,
                title = if (artist.isNotBlank()) "$title • $artist" else title,
                isVideo = false,
                thumbUrl = thumb,
                author = artist
            )
        }

        // 2d. Amazon Music link resolution (music.amazon.com / amazon.com/music)
        if (cleanUrl.contains("music.amazon.com") || cleanUrl.contains("amazon.com/music")) {
            val titlePart = runCatching {
                val path = Uri.parse(cleanUrl).path.orEmpty()
                val segments = path.split('/').filter { it.isNotBlank() }
                val lastSeg = segments.lastOrNull { !it.startsWith("B0") && it != "albums" && it != "tracks" }
                lastSeg?.replace('-', ' ')?.replace('+', ' ')
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Amazon Music Track"
            val lazyUri = "resolve://audio/${titlePart}::Amazon Music"
            return@withContext ResolvedStream(
                streamUrl = lazyUri,
                title = titlePart,
                isVideo = false,
                author = "Amazon Music"
            )
        }

        // 3. YouTube link resolution (all 10 formats: watch, youtu.be, shorts, live, embed, playlists)
        val ytVideoId = run {
            val vidMatch = Regex("""(?:v=|/v/|youtu\.be/|/shorts/|/live/|/embed/|/e/)([\w-]{11})""").find(cleanUrl)
            if (vidMatch != null) {
                vidMatch.groupValues[1]
            } else if (cleanUrl.contains("youtu.be/")) {
                cleanUrl.substringAfter("youtu.be/").substringBefore('?').substringBefore('/')
            } else if (cleanUrl.contains("youtube.com/shorts/")) {
                cleanUrl.substringAfter("youtube.com/shorts/").substringBefore('?').substringBefore('/')
            } else if (cleanUrl.contains("youtube.com/live/")) {
                cleanUrl.substringAfter("youtube.com/live/").substringBefore('?').substringBefore('/')
            } else if (cleanUrl.contains("youtube.com/embed/")) {
                cleanUrl.substringAfter("youtube.com/embed/").substringBefore('?').substringBefore('/')
            } else if (cleanUrl.contains("watch?") || cleanUrl.contains("/watch/")) {
                if (cleanUrl.contains("v=")) {
                    cleanUrl.substringAfter("v=").substringBefore('&').substringBefore('#')
                } else ""
            } else ""
        }.trim()

        if (ytVideoId.isNotBlank()) {
            val videoId = ytVideoId
            // Concurrently fetch metadata and stream resolution to achieve sub-second startup
            val (ytStream, oembed) = coroutineScope {
                val oembedDeferred = async {
                    runCatching {
                        val conn = (URL("https://noembed.com/embed?url=https://www.youtube.com/watch?v=$videoId").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 2000
                            readTimeout = 2000
                            setRequestProperty("User-Agent", "Mozilla/5.0")
                        }
                        val json = conn.inputStream.bufferedReader().use { it.readText() }
                        val obj = org.json.JSONObject(json)
                        val title = obj.optString("title", "").trim()
                        val author = obj.optString("author_name", "").trim()
                        val thumb = obj.optString("thumbnail_url", "").trim()
                        Triple(title, author, thumb)
                    }.getOrNull()
                }

                val ytStreamDeferred = async {
                    runCatching { YtMusicClient.resolveVideoStreamUrlWithUa(videoId) }.getOrNull()
                }

                val stream: YtResolvedStream? = ytStreamDeferred.await()
                val meta: Triple<String, String, String>? = runCatching { withTimeoutOrNull(800) { oembedDeferred.await() } }.getOrNull()
                Pair(stream, meta)
            }

            val resolvedTitle = extractedTitle
                ?: ytStream?.title?.takeIf { it.isNotBlank() }
                ?: oembed?.first?.takeIf { it.isNotBlank() }
                ?: "YouTube Stream ($videoId)"
            val resolvedAuthor = ytStream?.author?.takeIf { it.isNotBlank() } ?: oembed?.second ?: ""
            val resolvedThumb = ytStream?.thumbUrl?.takeIf { it.isNotBlank() } ?: oembed?.third ?: "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

            val isExplicitMusicLink = rawInput.contains("music.youtube.com")

            if (ytStream != null && ytStream.url.isNotBlank() && ytStream.isVideo) {
                return@withContext ResolvedStream(
                    streamUrl = ytStream.url,
                    title = resolvedTitle,
                    isVideo = !isExplicitMusicLink,
                    userAgent = ytStream.userAgent,
                    thumbUrl = resolvedThumb,
                    author = resolvedAuthor,
                    subtitleUrl = ytStream.subtitleUrl,
                )
            }

            // Fallback to stream resolution
            val ytAudio = runCatching { YtMusicClient.resolveStreamUrlWithUa(videoId) }.getOrNull()
            if (ytAudio != null && ytAudio.url.isNotBlank()) {
                return@withContext ResolvedStream(
                    streamUrl = ytAudio.url,
                    title = resolvedTitle,
                    isVideo = !isExplicitMusicLink && ytAudio.isVideo,
                    userAgent = ytAudio.userAgent,
                    thumbUrl = resolvedThumb,
                    author = resolvedAuthor
                )
            }
            if (isExplicitMusicLink && ytStream?.url.isNullOrBlank() && resolvedTitle.isNotBlank()) {
                // Only for explicit YouTube Music requests: route to dual audio engine via track title
                val lazyUri = "resolve://audio/${resolvedTitle}::${resolvedAuthor}"
                return@withContext ResolvedStream(
                    streamUrl = lazyUri,
                    title = resolvedTitle,
                    isVideo = false,
                    thumbUrl = resolvedThumb,
                    author = resolvedAuthor
                )
            }
            return@withContext ResolvedStream(
                streamUrl = ytStream?.url.orEmpty(),
                title = resolvedTitle,
                isVideo = !isExplicitMusicLink && (ytStream?.isVideo ?: true),
                userAgent = ytStream?.userAgent.orEmpty(),
                thumbUrl = resolvedThumb,
                author = resolvedAuthor
            )
        }

        // 4. Watch page / Webpage wrapper / Telegram bot link extraction (reaperzclub, dpdns, file-to-link)
        val isWrapper = cleanUrl.contains("/watch/") || cleanUrl.contains("dpdns.org") || cleanUrl.contains("reaper") || PlaybackRules.isWebpageWrapperUrl(cleanUrl)
        if (isWrapper) {
            val extracted = runCatching {
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                }
                val cType = conn.contentType?.lowercase().orEmpty()
                val finalUrl = conn.url.toString()
                val cd = conn.getHeaderField("Content-Disposition").orEmpty()
                val cdFilename = Regex("""filename\*?=['"]?(?:UTF-8'')?([^'";\r\n]+)""", RegexOption.IGNORE_CASE)
                    .find(cd)?.groupValues?.getOrNull(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

                val isKnownVideoExt = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".m3u8", ".mpd").any { finalUrl.contains(it, ignoreCase = true) }
                // If URL redirected to or serves binary media directly:
                if (cType.startsWith("video/") || cType.startsWith("audio/") || cType.contains("octet-stream") || cType.contains("matroska") || isKnownVideoExt) {
                    val rawName = cdFilename ?: Uri.parse(finalUrl).lastPathSegment?.substringBefore('?') ?: "Network Stream"
                    val cleanTitle = rawName.replace('_', ' ').replace(".mkv", "", true).replace(".mp4", "", true).trim()
                    return@runCatching ResolvedStream(
                        streamUrl = finalUrl,
                        title = cleanTitle,
                        isVideo = !cType.startsWith("audio/")
                    )
                }

                // Safe read: bounded to 64KB so we never OOM on large payloads
                val charBuf = CharArray(65536)
                val readLen = conn.inputStream.bufferedReader().use { it.read(charBuf, 0, 65536) }
                val html = if (readLen > 0) String(charBuf, 0, readLen) else ""

                // Check STREAM_URL variants
                val streamUrlRegex = Regex("""(?:const|var|let|window\.)\s*(?:STREAM_URL|stream_url|streamUrl|download_url|directUrl)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                var foundStream = streamUrlRegex.find(html)?.groupValues?.getOrNull(1)

                if (foundStream.isNullOrBlank()) {
                    val videoSrcRegex = Regex("""<(?:video|source)[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                    foundStream = videoSrcRegex.find(html)?.groupValues?.getOrNull(1)
                }

                if (foundStream.isNullOrBlank()) {
                    val aHrefRegex = Regex("""<a[^>]+href=['"]([^'"]*(?:stream|download|file|video)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
                    foundStream = aHrefRegex.find(html)?.groupValues?.getOrNull(1)
                }

                val fileNameRegex = Regex("""(?:const|var|let)\s*(?:FILE_NAME|fileName|file_name)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                val foundTitle = fileNameRegex.find(html)?.groupValues?.getOrNull(1)

                val titleTagRegex = Regex("""<title>(?:Watch\s+)?(.*?)</title>""", RegexOption.IGNORE_CASE)
                val htmlTitle = titleTagRegex.find(html)?.groupValues?.getOrNull(1)?.trim()

                val subTrackRegex = Regex("""<track[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                val foundSub = subTrackRegex.find(html)?.groupValues?.getOrNull(1)
                val absSub = foundSub?.let { if (it.startsWith("http://") || it.startsWith("https://")) it else URL(URL(cleanUrl), it).toString() }

                if (!foundStream.isNullOrBlank()) {
                    val rawTitle = extractedTitle ?: foundTitle ?: cdFilename ?: htmlTitle ?: Uri.parse(foundStream).lastPathSegment?.substringBefore('?') ?: "Network Stream"
                    val cleanTitle = rawTitle.replace('_', ' ').replace(".mkv", "", true).replace(".mp4", "", true).trim()
                    val absStream = if (foundStream.startsWith("http://") || foundStream.startsWith("https://")) foundStream else URL(URL(cleanUrl), foundStream).toString()
                    ResolvedStream(
                        streamUrl = absStream,
                        title = cleanTitle,
                        isVideo = true,
                        subtitleUrl = absSub
                    )
                } else null
            }.getOrNull()
            if (extracted != null) return@withContext extracted
        }

        // 5. Default direct media stream
        val isAudio = cleanUrl.contains(".mp3") || cleanUrl.contains(".m4a") || cleanUrl.contains(".aac") || cleanUrl.contains(".flac") || cleanUrl.contains(".ogg")
        val fallbackTitle = extractedTitle
            ?: (runCatching { Uri.parse(cleanUrl).lastPathSegment?.substringBefore('?') }.getOrNull()?.takeIf { it.isNotBlank() } ?: cleanUrl)

        return@withContext ResolvedStream(
            streamUrl = cleanUrl,
            title = fallbackTitle,
            isVideo = !isAudio
        )
    }
}
