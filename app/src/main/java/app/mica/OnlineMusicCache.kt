package app.mica

import android.content.Context
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-speed in-memory & disk cache for online tracks and category hits.
 * Enables instant 0ms tab switching without repeated network fetches.
 */
object OnlineMusicCache {
    private const val PREFS_KEY = "online_music_cache_v4"
    private const val TTL_MS = 2 * 3600 * 1000L // 2 hours
    private val memoryCache = LruCache<String, Pair<List<YtTrack>, Long>>(30)

    fun get(context: Context, categoryKey: String): List<YtTrack>? {
        val key = categoryKey.lowercase().trim()
        val now = System.currentTimeMillis()

        // 1. Fast memory lookup
        memoryCache.get(key)?.let { (tracks, timestamp) ->
            if (now - timestamp < TTL_MS && tracks.none { it.artist.isBlank() || it.artist.equals("Unknown", true) || it.artist.startsWith("{") || it.artist.startsWith("[") }) return FeedCacheManager.run { tracks.deduplicated() }
            else memoryCache.remove(key)
        }

        // 2. Persistent disk lookup
        return runCatching {
            val jsonStr = Store.prefs(context).getString("${PREFS_KEY}_$key", null) ?: return null
            val root = JSONObject(jsonStr)
            val time = root.optLong("time", 0L)
            if (now - time >= TTL_MS) return null

            val arr = root.optJSONArray("tracks") ?: return null
            val tracks = mutableListOf<YtTrack>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val art = o.optString("artist", "")
                if (art.isBlank() || art.equals("Unknown", true) || art.startsWith("{") || art.startsWith("[")) return null // Stale entry with missing/raw artist
                tracks.add(
                    YtTrack(
                        videoId = o.optString("id", ""),
                        title = o.optString("title", ""),
                        artist = art,
                        durationText = o.optString("dur", ""),
                        thumbUrl = o.optString("thumb", ""),
                        directStreamUrl = o.optString("stream", "")
                    )
                )
            }
            if (tracks.isNotEmpty()) {
                val deduped = FeedCacheManager.run { tracks.deduplicated() }
                memoryCache.put(key, deduped to time)
                deduped
            } else null
        }.getOrNull()
    }

    fun put(context: Context, categoryKey: String, tracks: List<YtTrack>) {
        if (tracks.isEmpty()) return
        val key = categoryKey.lowercase().trim()
        val deduped = FeedCacheManager.run { tracks.deduplicated() }
        val now = System.currentTimeMillis()

        // Store memory
        memoryCache.put(key, deduped to now)

        // Store disk asynchronously
        runCatching {
            val arr = JSONArray()
            tracks.take(60).forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("id", t.videoId)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("dur", t.durationText)
                        put("thumb", t.thumbUrl)
                        put("stream", t.directStreamUrl)
                    }
                )
            }
            val root = JSONObject().apply {
                put("time", now)
                put("tracks", arr)
            }
            Store.prefs(context).edit().putString("${PREFS_KEY}_$key", root.toString()).apply()
        }
    }

    fun clear(context: Context, categoryKey: String) {
        val key = categoryKey.lowercase().trim()
        memoryCache.remove(key)
        Store.prefs(context).edit().remove("${PREFS_KEY}_$key").apply()
    }
}
