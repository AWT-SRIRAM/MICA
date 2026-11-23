package app.msplayer.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiRecommender {
    /**
     * Uses Gemini to generate personalized song recommendations and queries based on
     * user's favorite artists, preferred language (defaults to Tamil), and vibe.
     */
    suspend fun recommendMusicQueries(
        context: Context,
        favoriteArtists: List<String>,
        preferredLanguage: String = "Tamil",
        mood: String = "Feel Good"
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = SpeechToTextClient.activeGeminiKey(context).takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val models = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")

        val artistsHint = if (favoriteArtists.isNotEmpty()) {
            favoriteArtists.take(5).joinToString(", ")
        } else {
            "Anirudh Ravichander, AR Rahman, Yuvan Shankar Raja, Harris Jayaraj, Santhosh Narayanan"
        }

        val prompt = "Recommend 8 specific, trending $preferredLanguage song titles or artist playlists based on user listening to: $artistsHint. Mood: $mood. Return STRICT JSON: {\"queries\": [\"Song Title 1 $preferredLanguage\", \"Song Title 2 $preferredLanguage\"]}"

        for (m in models) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("response_mime_type", "application/json")
                    })
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                if (conn.responseCode in 200..299) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(resp)
                    val candidates = root.optJSONArray("candidates")
                    val text = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "")
                    if (!text.isNullOrBlank()) {
                        val parsed = JSONObject(text).optJSONArray("queries")
                        if (parsed != null && parsed.length() > 0) {
                            val list = mutableListOf<String>()
                            for (i in 0 until parsed.length()) {
                                val q = parsed.optString(i, "").trim()
                                if (q.isNotBlank()) list.add(q)
                            }
                            if (list.isNotEmpty()) return@withContext list
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        emptyList()
    }
}
