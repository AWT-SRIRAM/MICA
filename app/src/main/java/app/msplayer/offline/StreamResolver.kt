package app.msplayer.offline

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object StreamResolver {

    data class ResolvedStream(
        val streamUrl: String,
        val title: String,
        val isVideo: Boolean = true
    )

    /**
     * Resolves a raw pasted or shared input (which may be a direct media URL,
     * an Android intent: URI with foreign package, a watch page with embedded stream,
     * or a YouTube video link) into a playable stream URL and display title.
     */
    suspend fun resolve(rawInput: String): ResolvedStream = withContext(Dispatchers.IO) {
        var trimmed = rawInput.trim()
        var extractedTitle: String? = null

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

        // 3. YouTube link resolution (youtube.com or youtu.be)
        if (cleanUrl.contains("youtube.com/watch") || cleanUrl.contains("youtu.be/")) {
            val videoId = cleanUrl.substringAfter("v=").substringBefore('&')
                .ifEmpty { cleanUrl.substringAfter("youtu.be/").substringBefore('?').substringBefore('/') }
            if (videoId.isNotBlank()) {
                val streamUrl = runCatching { YtMusicClient.resolveStreamUrl(videoId) }.getOrNull()
                if (!streamUrl.isNullOrBlank()) {
                    return@withContext ResolvedStream(
                        streamUrl = streamUrl,
                        title = extractedTitle ?: "YouTube Video ($videoId)",
                        isVideo = true
                    )
                }
            }
        }

        // 4. Watch page / Webpage wrapper stream extraction (e.g. reaperzclub, dpdns, or generic HTML player pages)
        if (cleanUrl.contains("/watch/") || cleanUrl.contains("dpdns.org") || PlaybackRules.isWebpageWrapperUrl(cleanUrl)) {
            runCatching {
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
                val html = conn.inputStream.bufferedReader().use { it.readText() }

                // Check const STREAM_URL = '...' or var STREAM_URL = "..."
                val streamUrlRegex = Regex("""(?:const|var|let)\s+STREAM_URL\s*=\s*['"]([^'"]+)['"]""")
                val foundStream = streamUrlRegex.find(html)?.groupValues?.getOrNull(1)

                // Check const FILE_NAME = '...'
                val fileNameRegex = Regex("""(?:const|var|let)\s+FILE_NAME\s*=\s*['"]([^'"]+)['"]""")
                val foundTitle = fileNameRegex.find(html)?.groupValues?.getOrNull(1)

                // Check <title>...</title>
                val titleTagRegex = Regex("""<title>(?:Watch\s+)?(.*?)</title>""", RegexOption.IGNORE_CASE)
                val htmlTitle = titleTagRegex.find(html)?.groupValues?.getOrNull(1)?.trim()

                if (!foundStream.isNullOrBlank()) {
                    val finalTitle = extractedTitle ?: foundTitle ?: htmlTitle ?: "Network Stream"
                    return@withContext ResolvedStream(
                        streamUrl = foundStream,
                        title = finalTitle,
                        isVideo = true
                    )
                }
            }
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
