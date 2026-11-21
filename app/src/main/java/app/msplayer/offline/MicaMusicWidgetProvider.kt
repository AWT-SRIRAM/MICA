package app.msplayer.offline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews

class MicaMusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "app.mica.ACTION_WIDGET_TOGGLE"
        const val ACTION_WIDGET_PREV = "app.mica.ACTION_WIDGET_PREV"
        const val ACTION_WIDGET_NEXT = "app.mica.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_LIKE = "app.mica.ACTION_WIDGET_LIKE"
        const val ACTION_WIDGET_REPEAT = "app.mica.ACTION_WIDGET_REPEAT"
        const val EXTRA_PICK_INDEX = "pick_index"

        private var lastIsPlaying = false
        private var lastTitle = "MICA"
        private var lastArtist = "The Universe of Entertainment"
        private var lastArt: Bitmap? = null
        private var lastPosMs = 0L
        private var lastDurMs = 0L
        private var lastIsLiked = false
        private var lastIsRepeat = false

        fun updateWidget(
            context: Context,
            isPlaying: Boolean,
            title: String,
            artist: String,
            artBitmap: Bitmap?,
            posMs: Long = 0L,
            durMs: Long = 0L,
            isLiked: Boolean = false,
            isRepeat: Boolean = false
        ) {
            lastIsPlaying = isPlaying
            lastTitle = title.ifBlank { "MICA" }
            lastArtist = artist.ifBlank { "The Universe of Entertainment" }
            lastArt = artBitmap
            lastPosMs = posMs
            lastDurMs = durMs
            lastIsLiked = isLiked
            lastIsRepeat = isRepeat

            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, MicaMusicWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (widgetIds == null || widgetIds.isEmpty()) return

            for (widgetId in widgetIds) {
                val views = buildRemoteViews(
                    context, isPlaying, lastTitle, lastArtist, artBitmap,
                    lastPosMs, lastDurMs, lastIsLiked, lastIsRepeat
                )
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%02d:%02d".format(min, sec)
        }

        fun buildRemoteViews(
            context: Context,
            isPlaying: Boolean,
            title: String,
            artist: String,
            artBitmap: Bitmap?,
            posMs: Long = 0L,
            durMs: Long = 0L,
            isLiked: Boolean = false,
            isRepeat: Boolean = false
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mica_music)

            // Text metadata
            views.setTextViewText(R.id.widget_track_title, title)
            views.setTextViewText(R.id.widget_track_artist, artist)

            // Timeline & Progress Bar
            val safeDur = durMs.coerceAtLeast(1L)
            val progressPercent = ((posMs.coerceAtLeast(0L).toDouble() / safeDur.toDouble()) * 100).toInt().coerceIn(0, 100)
            views.setProgressBar(R.id.widget_progress_bar, 100, progressPercent, false)
            views.setTextViewText(R.id.widget_time_elapsed, formatDuration(posMs))
            views.setTextViewText(R.id.widget_time_total, if (durMs > 0L) formatDuration(durMs) else "03:30")

            // Play/Pause icon state
            val playPauseRes = if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_widget_play
            views.setImageViewResource(R.id.widget_btn_play_pause, playPauseRes)

            // Like / Favorite icon state
            val likeRes = if (isLiked) R.drawable.ic_notif_liked else R.drawable.ic_notif_like_add
            views.setImageViewResource(R.id.widget_btn_like, likeRes)

            // Repeat / Shuffle icon state
            val repeatRes = if (isRepeat) R.drawable.ic_notif_repeat else R.drawable.ic_notif_shuffle
            views.setImageViewResource(R.id.widget_btn_shuffle_repeat, repeatRes)

            // Album artwork
            if (artBitmap != null && !artBitmap.isRecycled) {
                views.setImageViewBitmap(R.id.widget_album_art, artBitmap)
            } else {
                views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_ms_logo)
            }

            // Top section click -> Open MICA App
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPi = PendingIntent.getActivity(
                context,
                100,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_top_section, openAppPi)

            // 5 Symmetric Control Buttons
            views.setOnClickPendingIntent(
                R.id.widget_btn_like_container,
                getBroadcastPendingIntent(context, ACTION_WIDGET_LIKE, 101)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_prev_container,
                getBroadcastPendingIntent(context, ACTION_WIDGET_PREV, 102)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_play_pause_container,
                getBroadcastPendingIntent(context, ACTION_WIDGET_TOGGLE, 103)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_next_container,
                getBroadcastPendingIntent(context, ACTION_WIDGET_NEXT, 104)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_shuffle_repeat_container,
                getBroadcastPendingIntent(context, ACTION_WIDGET_REPEAT, 105)
            )

            return views
        }

        private fun getBroadcastPendingIntent(context: Context, actionStr: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MicaMusicWidgetProvider::class.java).apply {
                action = actionStr
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (widgetId in appWidgetIds) {
            val views = buildRemoteViews(
                context, lastIsPlaying, lastTitle, lastArtist, lastArt,
                lastPosMs, lastDurMs, lastIsLiked, lastIsRepeat
            )
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        val action = intent?.action ?: return
        val serviceAction = when (action) {
            ACTION_WIDGET_TOGGLE -> "toggle"
            ACTION_WIDGET_PREV -> "previous"
            ACTION_WIDGET_NEXT -> "next"
            ACTION_WIDGET_LIKE -> "toggle_like"
            ACTION_WIDGET_REPEAT -> "toggle_repeat"
            else -> return
        }

        if (PlaybackService.isServiceRunning) {
            val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                this.action = serviceAction
            }
            context.startService(serviceIntent)
        } else {
            // When service isn't active yet, launch MainActivity to safely bring app to foreground
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_play", true)
            }
            context.startActivity(openIntent)
        }
    }
}
