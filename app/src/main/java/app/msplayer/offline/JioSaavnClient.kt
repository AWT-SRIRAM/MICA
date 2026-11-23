package app.msplayer.offline

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class JioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationText: String = "",
    val thumbUrl: String = "",
    val streamUrl: String = "",
    val language: String = "",
)

object JioSaavnClient {
    private const val TAG = "JioSaavnClient"
    private const val DES_KEY = "38346591"

    /**
     * Decrypts JioSaavn encrypted_media_url to direct CDN stream link (ending in _320.mp4)
     */
    fun decryptMediaUrl(encryptedUrl: String): String? {
        if (encryptedUrl.isBlank()) return null
        try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val keySpec = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(encryptedUrl, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val decryptedUrl = String(decryptedBytes, Charsets.UTF_8)
            // Ensure highest quality 320kbps format
            return decryptedUrl
                .replace("_96.mp4", "_320.mp4")
                .replace("_160.mp4", "_320.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "DES Decryption error: ${e.message}")
            return null
        }
    }

    /**
     * Searches JioSaavn for songs and resolves direct 320kbps streams
     */
    fun extractArtist(item: JSONObject): String {
        val moreInfo = item.optJSONObject("more_info")

        // 1. Direct string in more_info or item (ensure it is NOT a JSON string starting with { or [)
        val pStr = moreInfo?.optString("primary_artists")?.takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
            ?: item.optString("primary_artists").takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
        if (!pStr.isNullOrBlank()) {
            return cleanHtml(pStr)
        }

        val sStr = moreInfo?.optString("singers")?.takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
            ?: item.optString("singers").takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
        if (!sStr.isNullOrBlank()) {
            return cleanHtml(sStr)
        }

        // 2. Parse artistMap object
        val artistMap = moreInfo?.optJSONObject("artistMap") ?: item.optJSONObject("artistMap")
        if (artistMap != null) {
            val primaryArr = artistMap.optJSONArray("primary_artists")
                ?: artistMap.optJSONArray("artists")
                ?: artistMap.optJSONArray("singers")
            if (primaryArr != null && primaryArr.length() > 0) {
                val names = mutableListOf<String>()
                for (i in 0 until primaryArr.length()) {
                    val aObj = primaryArr.optJSONObject(i)
                    val aName = aObj?.optString("name")?.takeIf { it.isNotBlank() }
                    if (aName != null) names.add(aName)
                }
                if (names.isNotEmpty()) {
                    return cleanHtml(names.joinToString(", "))
                }
            }
        }

        // 3. Check if primary_artists or singers is a JSONArray
        val pArr = moreInfo?.optJSONArray("primary_artists") ?: item.optJSONArray("primary_artists")
            ?: moreInfo?.optJSONArray("singers") ?: item.optJSONArray("singers")
        if (pArr != null && pArr.length() > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until pArr.length()) {
                val aObj = pArr.optJSONObject(i)
                val aName = aObj?.optString("name") ?: pArr.optString(i)
                if (aName.isNotBlank() && !aName.startsWith("{")) names.add(aName)
            }
            if (names.isNotEmpty()) {
                return cleanHtml(names.joinToString(", "))
            }
        }

        // 4. Music director
        val mStr = moreInfo?.optString("music")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
            ?: item.optString("music").takeIf { it.isNotBlank() && !it.startsWith("{") }
        if (!mStr.isNullOrBlank()) return cleanHtml(mStr)

        return "Unknown"
    }

    private fun cleanHtml(s: String): String =
        s.replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&").trim()

    suspend fun searchSongs(query: String): List<JioTrack> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val results = mutableListOf<JioTrack>()

        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val searchUrl = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=15&p=1&q=$encodedQuery&_marker=0&api_version=4&ctx=web6dot0"
            val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Connection", "keep-alive")
            }

            if (conn.responseCode in 200..299) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(resp)
                val list = root.optJSONArray("results")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        var title = item.optString("song", item.optString("title", ""))
                        title = cleanHtml(title)
                        val moreInfo = item.optJSONObject("more_info")
                        val artist = extractArtist(item)
                        val durationSec = item.optInt("duration", 0)
                        val durationText = if (durationSec > 0) "${durationSec / 60}:${(durationSec % 60).toString().padStart(2, '0')}" else ""
                        val language = item.optString("language", "").lowercase(Locale.ROOT)
                        var img = item.optString("image", "")
                        // Upgrade to 500x500 HD
                        img = img.replace("150x150.jpg", "500x500.jpg")
                            .replace("50x50.jpg", "500x500.jpg")

                        val encUrl = moreInfo?.optString("encrypted_media_url", "") ?: ""
                        var directStream = if (encUrl.isNotBlank()) decryptMediaUrl(encUrl) ?: "" else ""

                        if (directStream.isBlank()) {
                            val vlink = moreInfo?.optString("vlink", "") ?: ""
                            if (vlink.isNotBlank()) {
                                directStream = vlink.replace("_96.mp4", "_320.mp4")
                            }
                        }

                        if (id.isNotBlank() && title.isNotBlank()) {
                            results.add(
                                JioTrack(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    durationText = durationText,
                                    thumbUrl = img,
                                    streamUrl = directStream,
                                    language = language,
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
        }
        results
    }

    /**
     * Resolves direct 320kbps stream URL for a given title and artist.
     */
    suspend fun resolve320kbps(title: String, artist: String): JioTrack? = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
        val songs = searchSongs("$cleanTitle $artist")
        songs.firstOrNull { it.streamUrl.isNotBlank() } ?: songs.firstOrNull()
    }

    /**
     * Searches JioSaavn for curated playlists matching query.
     */
    suspend fun searchPlaylists(query: String): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList()
        val list = mutableListOf<PlaylistCard>()
        try {
            val enc = URLEncoder.encode(clean, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getPlaylistResults&q=$enc&_format=json&_marker=0&ctx=android&n=20"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(text)
                val resultsArr = root.optJSONArray("results")
                if (resultsArr != null) {
                    for (i in 0 until resultsArr.length()) {
                        val p = resultsArr.optJSONObject(i) ?: continue
                        val listId = p.optString("listid", p.optString("id", ""))
                        val title = p.optString("listname", p.optString("title", ""))
                            .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
                        val subtitle = p.optString("artist_name", p.optString("subtitle", "${p.optString("numsongs", "50")} Songs"))
                            .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
                        var img = p.optString("image", "")
                            .replace("150x150.jpg", "500x500.jpg")
                            .replace("50x50.jpg", "500x500.jpg")
                        val count = p.optInt("count", p.optInt("numsongs", 0))
                        if (listId.isNotBlank() && title.isNotBlank()) {
                            list.add(
                                PlaylistCard(
                                    title = title,
                                    subtitle = subtitle,
                                    coverUrl = img,
                                    searchQuery = title,
                                    listId = listId,
                                    songCount = count
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchPlaylists error: ${e.message}")
        }
        list
    }

    /**
     * Fetches up to 50+ individual songs with 320kbps streams from a JioSaavn playlist.
     */
    suspend fun fetchPlaylistTracks(listId: String): List<YtTrack> = withContext(Dispatchers.IO) {
        if (listId.isBlank()) return@withContext emptyList()
        val tracks = mutableListOf<YtTrack>()
        try {
            val url = "https://www.jiosaavn.com/api.php?__call=playlist.getDetails&listid=$listId&_format=json&_marker=0&ctx=android"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(text)
                for (i in 0 until 100) {
                    val s = root.optJSONObject(i.toString()) ?: continue
                    val id = s.optString("id", "")
                    var songTitle = s.optString("song", s.optString("title", ""))
                        .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
                    val artist = extractArtist(s)
                    val durSec = s.optInt("duration", 0)
                    val durText = if (durSec > 0) "${durSec / 60}:${(durSec % 60).toString().padStart(2, '0')}" else ""
                    var img = s.optString("image", "")
                        .replace("150x150.jpg", "500x500.jpg")
                        .replace("50x50.jpg", "500x500.jpg")
                    val encUrl = s.optString("encrypted_media_url", "")
                    val directStream = if (encUrl.isNotBlank()) decryptMediaUrl(encUrl) ?: "" else ""

                    if (id.isNotBlank() && songTitle.isNotBlank()) {
                        tracks.add(
                            YtTrack(
                                videoId = "saavn_$id",
                                title = songTitle,
                                artist = artist,
                                durationText = durText,
                                thumbUrl = img,
                                directStreamUrl = directStream
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylistTracks error: ${e.message}")
        }
        tracks
    }

    /**
     * Fetches similar / recommended 320kbps tracks based on a JioSaavn song ID or artist.
     */
    suspend fun fetchSimilarSongs(saavnId: String, title: String, artist: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val cleanId = saavnId.removePrefix("saavn_").trim()
        val tracks = mutableListOf<YtTrack>()
        val seedLower = title.lowercase().trim()
        if (cleanId.isNotBlank()) {
            try {
                val url = "https://www.jiosaavn.com/api.php?__call=reco.getreco&pid=$cleanId&_format=json&_marker=0&ctx=android"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 4500
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode in 200..299) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }.trim()
                    val arr = if (text.startsWith("[")) org.json.JSONArray(text) else JSONObject(text).optJSONArray("list")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            val id = s.optString("id", "")
                            val songTitle = s.optString("song", s.optString("title", ""))
                                .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
                            val moreInfo = s.optJSONObject("more_info")
                            val songArtist = extractArtist(s).let { if (it == "Unknown" && artist.isNotBlank()) artist else it }
                            val durSec = s.optInt("duration", 0)
                            val durText = if (durSec > 0) "${durSec / 60}:${(durSec % 60).toString().padStart(2, '0')}" else ""
                            val img = s.optString("image", "")
                                .replace("150x150.jpg", "500x500.jpg")
                                .replace("50x50.jpg", "500x500.jpg")
                            val encUrl = s.optString("encrypted_media_url", moreInfo?.optString("encrypted_media_url", "") ?: "")
                            val directStream = if (encUrl.isNotBlank()) decryptMediaUrl(encUrl) ?: "" else ""
                            if (id.isNotBlank() && songTitle.isNotBlank() && songTitle.lowercase().trim() != seedLower) {
                                tracks.add(
                                    YtTrack(
                                        videoId = "saavn_$id",
                                        title = songTitle,
                                        artist = songArtist,
                                        durationText = durText,
                                        thumbUrl = img,
                                        directStreamUrl = directStream,
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchSimilarSongs reco error: ${e.message}")
            }
        }
        if (tracks.size < 6) {
            val primaryArtist = artist.substringBefore(",").substringBefore("•").trim()
            val fallbackQuery = if (primaryArtist.isNotBlank() && !primaryArtist.equals("Unknown", ignoreCase = true)) {
                "$primaryArtist hit songs"
            } else {
                title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            }
            val searched = searchSongs(fallbackQuery)
            searched.forEach { j ->
                if (j.title.lowercase().trim() != seedLower && tracks.none { it.title.equals(j.title, ignoreCase = true) }) {
                    tracks.add(
                        YtTrack(
                            videoId = "saavn_${j.id}",
                            title = j.title,
                            artist = j.artist,
                            durationText = j.durationText,
                            thumbUrl = j.thumbUrl,
                            directStreamUrl = j.streamUrl,
                        )
                    )
                }
            }
        }
        tracks.take(15)
    }
}
