package app.msplayer.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class SpotifyTrack(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String = "",
    val thumbUrl: String = ""
)

data class ImportedSpotify(
    val title: String,
    val author: String,
    val thumbUrl: String,
    val tracks: List<SpotifyTrack>
)

object SpotifyClient {

    private val SPOTIFY_PATTERN = Pattern.compile(
        """https?://open\.spotify\.com/(playlist|album|track)/([a-zA-Z0-9]+)(\?.*)?""",
        Pattern.CASE_INSENSITIVE
    )

    fun isSpotifyUrl(url: String): Boolean {
        val trimmed = url.trim()
        return SPOTIFY_PATTERN.matcher(trimmed).find() ||
                trimmed.contains("spotify.link/") ||
                trimmed.contains("spotify.com/")
    }

    suspend fun importUrl(url: String): ImportedSpotify? = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val matcher = SPOTIFY_PATTERN.matcher(trimmed)
        val embedUrl = if (matcher.find()) {
            val type = matcher.group(1)
            val id = matcher.group(2)
            "https://open.spotify.com/embed/$type/$id"
        } else if (trimmed.contains("open.spotify.com/embed/")) {
            trimmed
        } else {
            return@withContext null
        }

        runCatching {
            val conn = (URL(embedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val scriptMarker = """<script id="__NEXT_DATA__" type="application/json">"""
            val startIdx = html.indexOf(scriptMarker)
            if (startIdx == -1) return@withContext null
            val jsonStart = startIdx + scriptMarker.length
            val jsonEnd = html.indexOf("</script>", jsonStart)
            if (jsonEnd == -1) return@withContext null

            val jsonStr = html.substring(jsonStart, jsonEnd).trim()
            val root = JSONObject(jsonStr)
            val entity = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
                ?: return@withContext null

            val playlistTitle = entity.optString("name", "Spotify Playlist").trim()
            val subtitle = entity.optString("subtitle", "").trim()
            val coverSources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
            var coverUrl = ""
            if (coverSources != null && coverSources.length() > 0) {
                // Pick largest
                coverUrl = coverSources.optJSONObject(coverSources.length() - 1)?.optString("url", "") ?: ""
            }

            val trackListArray = entity.optJSONArray("trackList") ?: org.json.JSONArray()
            val tracks = mutableListOf<SpotifyTrack>()

            for (i in 0 until trackListArray.length()) {
                val item = trackListArray.optJSONObject(i) ?: continue
                val trackTitle = item.optString("title", "").trim()
                if (trackTitle.isBlank()) continue
                val artist = item.optString("subtitle", "").trim().ifBlank { "Unknown Artist" }
                val duration = item.optLong("duration", 0L).coerceAtLeast(0L)
                tracks.add(
                    SpotifyTrack(
                        title = trackTitle,
                        artist = artist,
                        durationMs = duration,
                        album = playlistTitle,
                        thumbUrl = coverUrl
                    )
                )
            }

            ImportedSpotify(
                title = playlistTitle,
                author = subtitle.ifBlank { "Spotify" },
                thumbUrl = coverUrl,
                tracks = tracks
            )
        }.getOrNull()
    }
}
