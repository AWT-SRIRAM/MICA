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
        const val ACTION_WIDGET_PLAY_PICK = "app.mica.ACTION_WIDGET_PLAY_PICK"
        const val EXTRA_PICK_INDEX = "pick_index"

        private var lastIsPlaying = false
        private var lastTitle = "MICA"
        private var lastArtist = "The Universe of Entertainment"
        private var lastArt: Bitmap? = null

        fun updateWidget(
            context: Context,
            isPlaying: Boolean,
            title: String,
            artist: String,
            artBitmap: Bitmap?
        ) {
            lastIsPlaying = isPlaying
            lastTitle = title.ifBlank { "MICA" }
            lastArtist = artist.ifBlank { "The Universe of Entertainment" }
            lastArt = artBitmap

            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, MicaMusicWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (widgetIds == null || widgetIds.isEmpty()) return

            for (widgetId in widgetIds) {
                val views = buildRemoteViews(context, isPlaying, lastTitle, lastArtist, artBitmap)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun buildRemoteViews(
            context: Context,
            isPlaying: Boolean,
            title: String,
            artist: String,
            artBitmap: Bitmap?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mica_music)

            // Text metadata
            views.setTextViewText(R.id.widget_track_title, title)
            views.setTextViewText(R.id.widget_track_artist, artist)

            // Play/Pause icon state
            val playPauseRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            views.setImageViewResource(R.id.widget_btn_play_pause, playPauseRes)

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

            // Control Buttons
            views.setOnClickPendingIntent(
                R.id.widget_btn_prev,
                getServicePendingIntent(context, "previous", 101)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_play_pause_container,
                getServicePendingIntent(context, "toggle", 102)
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_next,
                getServicePendingIntent(context, "next", 103)
            )

            // Quick Picks (4 Spotify-style recommendations)
            val picks = FeedCacheManager.cachedTopHits.value.ifEmpty { FeedCacheManager.defaultCuratedHits() }
            val quickTitles = listOf(
                picks.getOrNull(0)?.title ?: "Tamil Hits",
                picks.getOrNull(1)?.title ?: "Top Hits",
                picks.getOrNull(2)?.title ?: "Lofi Beats",
                picks.getOrNull(3)?.title ?: "Discover"
            )

            val titleViews = listOf(
                R.id.widget_quick_title_1,
                R.id.widget_quick_title_2,
                R.id.widget_quick_title_3,
                R.id.widget_quick_title_4
            )
            val cardViews = listOf(
                R.id.widget_quick_card_1,
                R.id.widget_quick_card_2,
                R.id.widget_quick_card_3,
                R.id.widget_quick_card_4
            )

            for (i in 0 until 4) {
                views.setTextViewText(titleViews[i], quickTitles[i])
                views.setOnClickPendingIntent(
                    cardViews[i],
                    getQuickPickPendingIntent(context, i, 200 + i)
                )
            }

            return views
        }

        private fun getServicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java).apply {
                this.action = action
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        private fun getQuickPickPendingIntent(context: Context, pickIndex: Int, requestCode: Int): PendingIntent {
            val intent = Intent(context, MicaMusicWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_PLAY_PICK
                putExtra(EXTRA_PICK_INDEX, pickIndex)
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
            val views = buildRemoteViews(context, lastIsPlaying, lastTitle, lastArtist, lastArt)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        if (intent?.action == ACTION_WIDGET_PLAY_PICK) {
            val pickIndex = intent.getIntExtra(EXTRA_PICK_INDEX, 0)
            val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                action = "widget_play_pick"
                putExtra(EXTRA_PICK_INDEX, pickIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
