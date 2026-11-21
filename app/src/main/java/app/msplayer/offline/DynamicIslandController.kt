package app.msplayer.offline

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Spotify-style Liquid Glass Dynamic Island overlay controller.
 *
 * Positions cleanly below the system status bar touch zone (so Android 12–16
 * never intercepts taps or clips icons), uses zero-allocation caching on playback
 * ticks for 120Hz butter-smooth performance, and expands on tap into a frosted
 * Liquid Glass player card with HD album art, live seekbar, and 5 controls.
 */
class DynamicIslandController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onSeekTo(positionMs: Long)
        fun onToggleLike()
        fun onToggleShuffle()
        fun onOpenApp()
    }

    data class Snapshot(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val artwork: Bitmap?,
        val accentColor: Int,
        val liked: Boolean,
        val shuffle: Boolean,
    )

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: FrameLayout? = null
    private var pillContainer: LinearLayout? = null
    private var expandedContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Collapsed pill views
    private var pillThumb: ImageView? = null
    private var pillTitle: TextView? = null
    private var pillSpectrum: SpectrumBarsView? = null

    // Expanded card views
    private var expThumb: ImageView? = null
    private var expTitle: TextView? = null
    private var expArtist: TextView? = null
    private var expPosText: TextView? = null
    private var expDurText: TextView? = null
    private var expSeekBar: SeekBar? = null
    private var expLikeBtn: ImageView? = null
    private var expPrevBtn: ImageView? = null
    private var expPlayPauseBtn: ImageView? = null
    private var expNextBtn: ImageView? = null
    private var expShuffleBtn: ImageView? = null

    // Zero-allocation state caches so 500ms ticks never re-allocate bitmaps
    private var lastArtworkRef: Bitmap? = null
    private var cachedPillThumb: Bitmap? = null
    private var cachedExpThumb: Bitmap? = null
    private var lastPlayingBound: Boolean? = null
    private var lastLikedBound: Boolean? = null
    private var lastShuffleBound: Boolean? = null
    private var lastAccentBound: Int = Int.MIN_VALUE
    private var lastBgColor: Int = Color.rgb(30, 80, 140)
    private var lastBoundTitle: String? = null
    private var lastBoundArtist: String? = null

    private var isAdded = false
    private var isExpanded = false
    private var userDismissedForTrack: String? = null
    private var userSeeking = false
    private var lastSnapshot: Snapshot? = null

    private val autoCollapseRunnable = Runnable {
        if (isExpanded && !userSeeking) {
            setExpanded(false)
        }
    }

    fun isPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun isEnabledByUser(): Boolean =
        Store.prefs(context).getBoolean("dynamic_island_overlay", false)

    fun update(snapshot: Snapshot?, appInForeground: Boolean) {
        mainHandler.post {
            updateOnMain(snapshot, appInForeground)
        }
    }

    fun hide() {
        mainHandler.post {
            removeOverlay()
        }
    }

    fun resetDismiss() {
        userDismissedForTrack = null
    }

    private fun updateOnMain(snapshot: Snapshot?, appInForeground: Boolean) {
        if (snapshot == null || appInForeground || !isEnabledByUser() || !isPermissionGranted()) {
            removeOverlay()
            return
        }
        val trackKey = "${snapshot.title}::${snapshot.artist}"
        if (!Store.prefs(context).getBoolean("enable_floating_island_overlay", false)) {
            removeOverlay()
            return
        }
        if (userDismissedForTrack != null && userDismissedForTrack == trackKey) {
            removeOverlay()
            return
        }
        if (userDismissedForTrack != null && userDismissedForTrack != trackKey) {
            userDismissedForTrack = null
        }
        if (!snapshot.playing && !isExpanded) {
            removeOverlay()
            return
        }

        lastSnapshot = snapshot
        ensureOverlayAdded()
        bindSnapshot(snapshot, forceAll = false)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun statusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(32f)
        return h.coerceIn(dp(24f), dp(54f))
    }

    private fun ensureOverlayAdded() {
        if (isAdded && rootView != null) return

        // Reset bind caches for fresh view hierarchy
        lastArtworkRef = null
        cachedPillThumb = null
        cachedExpThumb = null
        lastPlayingBound = null
        lastLikedBound = null
        lastShuffleBound = null
        lastAccentBound = Int.MIN_VALUE
        lastBoundTitle = null
        lastBoundArtist = null

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && isExpanded) {
                    setExpanded(false)
                    true
                } else {
                    false
                }
            }
        }

        // ---- 1. COLLAPSED LIQUID GLASS DYNAMIC ISLAND PILL ----
        // Placed cleanly right beneath the status bar so taps are 100% instant & never eaten by StatusBar!
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(5f), dp(12f), dp(5f))
            background = createPillBackground()
            elevation = dp(12f).toFloat()
            isClickable = true
            isFocusable = false
        }

        val pThumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
        }
        pillThumb = pThumb

        val pTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(138f)
            minWidth = dp(56f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8f)
                marginEnd = dp(10f)
            }
        }
        pillTitle = pTitle

        val spectrum = SpectrumBarsView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(16f))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        pillSpectrum = spectrum

        pill.addView(pThumb)
        pill.addView(pTitle)
        pill.addView(spectrum)

        // Ultra-responsive touch handling on collapsed pill: Tap -> expand, Long-press -> open app, Swipe up -> dismiss
        var downY = 0f
        var downX = 0f
        var downTime = 0L
        var gestureHandled = false
        val longPressRunnable = Runnable {
            if (!gestureHandled) {
                gestureHandled = true
                callbacks.onOpenApp()
            }
        }
        pill.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    downTime = System.currentTimeMillis()
                    gestureHandled = false
                    pill.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).start()
                    mainHandler.postDelayed(longPressRunnable, 480L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureHandled && downY - ev.rawY > dp(24f) && abs(downY - ev.rawY) > abs(downX - ev.rawX)) {
                        gestureHandled = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        lastSnapshot?.let { userDismissedForTrack = "${it.title}::${it.artist}" }
                        removeOverlay()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                    val dt = System.currentTimeMillis() - downTime
                    if (!gestureHandled && ev.action == MotionEvent.ACTION_UP && dt < 460L) {
                        setExpanded(true)
                    }
                    true
                }
                else -> false
            }
        }

        // ---- 2. EXPANDED SPOTIFY-STYLE LIQUID GLASS ISLAND (Matches IMG-20261117-WA0016.jpg) ----
        val screenW = context.resources.displayMetrics.widthPixels
        val expandedW = minOf(screenW - dp(20f), dp(382f))
        val expanded = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
            background = createExpandedGlassBackground(lastBgColor)
            elevation = dp(18f).toFloat()
            visibility = View.GONE
            clipToPadding = false
        }

        // Top row: [56dp Album Thumbnail + corner badge]  [Title / Artist]  [Wave / Open App]
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val thumbFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58f), dp(58f))
            setOnClickListener {
                setExpanded(false)
                callbacks.onOpenApp()
            }
        }
        val eThumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        expThumb = eThumb
        thumbFrame.addView(eThumb)

        val badgeView = ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = FrameLayout.LayoutParams(dp(18f), dp(18f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(2f), dp(2f))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF0091EA"))
                setStroke(dp(1.2f), Color.parseColor("#EE0B0F16"))
            }
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }
        thumbFrame.addView(badgeView)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(13f)
                marginEnd = dp(8f)
            }
            setOnClickListener {
                setExpanded(false)
                callbacks.onOpenApp()
            }
        }

        val eTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isSingleLine = true
        }
        expTitle = eTitle

        val eArtist = TextView(context).apply {
            setTextColor(Color.parseColor("#DCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
        }
        expArtist = eArtist

        textCol.addView(eTitle)
        textCol.addView(eArtist)

        val eOpen = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setImageDrawable(createIconDrawable(IconKind.WAVE_BADGE, Color.WHITE))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#24FFFFFF"))
            }
            setOnClickListener {
                setExpanded(false)
                callbacks.onOpenApp()
            }
        }

        topRow.addView(thumbFrame)
        topRow.addView(textCol)
        topRow.addView(eOpen)

        // Middle row: [01:02]  [===o-------]  [05:51]
        val seekRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12f)
            }
        }

        val ePos = TextView(context).apply {
            setTextColor(Color.parseColor("#D4FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            text = "00:00"
            minWidth = dp(38f)
        }
        expPosText = ePos

        val eSeek = SeekBar(context).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
            }
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#48FFFFFF"))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = lastSnapshot?.durationMs ?: 0L
                        if (dur > 0L) {
                            expPosText?.text = formatTime((dur * progress) / 1000L)
                        }
                        rescheduleAutoCollapse()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                    mainHandler.removeCallbacks(autoCollapseRunnable)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    val dur = lastSnapshot?.durationMs ?: 0L
                    val progress = seekBar?.progress ?: 0
                    if (dur > 0L) {
                        callbacks.onSeekTo((dur * progress) / 1000L)
                    }
                    rescheduleAutoCollapse()
                }
            })
        }
        expSeekBar = eSeek

        val eDur = TextView(context).apply {
            setTextColor(Color.parseColor("#D4FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            text = "00:00"
            gravity = Gravity.END
            minWidth = dp(38f)
        }
        expDurText = eDur

        seekRow.addView(ePos)
        seekRow.addView(eSeek)
        seekRow.addView(eDur)

        // Bottom row: 5 clean Spotify-style icons: [Like ✓] [Prev ⏮] [Play/Pause ⏸] [Next ⏭] [Shuffle 🔀]
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8f)
            }
        }

        fun makeActionButton(sizeDp: Float, onClick: () -> Unit): ImageView =
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(sizeDp), 1f)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                setOnClickListener {
                    onClick()
                    rescheduleAutoCollapse()
                }
            }

        val eLike = makeActionButton(40f) { callbacks.onToggleLike() }
        val ePrev = makeActionButton(42f) { callbacks.onPrevious() }
        val ePlay = makeActionButton(46f) { callbacks.onPlayPause() }
        val eNext = makeActionButton(42f) { callbacks.onNext() }
        val eShuf = makeActionButton(40f) { callbacks.onToggleShuffle() }

        ePrev.setImageDrawable(createIconDrawable(IconKind.PREV, Color.WHITE))
        eNext.setImageDrawable(createIconDrawable(IconKind.NEXT, Color.WHITE))

        expLikeBtn = eLike
        expPrevBtn = ePrev
        expPlayPauseBtn = ePlay
        expNextBtn = eNext
        expShuffleBtn = eShuf

        controlsRow.addView(eLike)
        controlsRow.addView(ePrev)
        controlsRow.addView(ePlay)
        controlsRow.addView(eNext)
        controlsRow.addView(eShuf)

        expanded.addView(topRow)
        expanded.addView(seekRow)
        expanded.addView(controlsRow)

        // Swipe up on expanded card collapses back to compact pill
        var expDownY = 0f
        expanded.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    expDownY = ev.rawY
                    rescheduleAutoCollapse()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (expDownY - ev.rawY > dp(32f)) {
                        setExpanded(false)
                        true
                    } else false
                }
                else -> false
            }
        }

        container.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(36f),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        container.addView(
            expanded,
            FrameLayout.LayoutParams(
                expandedW,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )

        pillContainer = pill
        expandedContainer = expanded
        rootView = container

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val notchLevel = Store.prefs(context).getBoolean("dynamic_island_notch_level", false)
            y = if (notchLevel) dp(2f) else (statusBarHeightPx() + dp(4f))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = params
        runCatching {
            wm.addView(container, params)
            isAdded = true
            isExpanded = false
        }
    }

    private fun setExpanded(expand: Boolean) {
        if (!isAdded || isExpanded == expand) return
        isExpanded = expand
        mainHandler.removeCallbacks(autoCollapseRunnable)

        val pill = pillContainer ?: return
        val exp = expandedContainer ?: return
        val root = rootView ?: return
        val params = layoutParams ?: return

        if (expand) {
            pill.visibility = View.GONE
            pillSpectrum?.setActive(false)
            exp.visibility = View.VISIBLE
            lastSnapshot?.let { bindSnapshot(it, forceAll = true) }
            runCatching { wm.updateViewLayout(root, params) }
            exp.alpha = 0f
            exp.scaleX = 0.80f
            exp.scaleY = 0.65f
            exp.pivotX = exp.layoutParams.width / 2f
            exp.pivotY = 0f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(exp, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(exp, View.SCALE_X, 0.80f, 1f),
                    ObjectAnimator.ofFloat(exp, View.SCALE_Y, 0.65f, 1f),
                )
                duration = 230L
                interpolator = OvershootInterpolator(0.75f)
                start()
            }
            rescheduleAutoCollapse()
        } else {
            exp.visibility = View.GONE
            if (lastSnapshot?.playing == true) {
                pill.visibility = View.VISIBLE
                pillSpectrum?.setActive(true)
                runCatching { wm.updateViewLayout(root, params) }
                pill.alpha = 0f
                pill.scaleX = 1.12f
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(pill, View.ALPHA, 0f, 1f),
                        ObjectAnimator.ofFloat(pill, View.SCALE_X, 1.12f, 1f),
                    )
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    start()
                }
            } else {
                removeOverlay()
            }
        }
    }

    private fun rescheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, 8000L)
    }

    private fun bindSnapshot(s: Snapshot, forceAll: Boolean) {
        // 1. Artwork — only scale & round when the Bitmap instance actually changes!
        if (forceAll || s.artwork !== lastArtworkRef) {
            lastArtworkRef = s.artwork
            val bmp = s.artwork
            if (bmp != null) {
                cachedPillThumb = roundedBitmap(bmp, dp(26f), circular = true)
                cachedExpThumb = roundedBitmap(bmp, dp(58f), circular = false, radiusPx = dp(14f).toFloat())
                pillThumb?.setImageBitmap(cachedPillThumb)
                expThumb?.setImageBitmap(cachedExpThumb)
            } else {
                cachedPillThumb = null
                cachedExpThumb = null
                pillThumb?.setImageResource(R.drawable.ic_launcher)
                expThumb?.setImageResource(R.drawable.ic_launcher)
            }
        }

        // 2. Title & Artist — only touch TextViews when text changes
        if (forceAll || lastBoundTitle != s.title) {
            lastBoundTitle = s.title
            val cleanShort = s.title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim().ifBlank { s.title }
            pillTitle?.text = cleanShort
            expTitle?.text = s.title
            expTitle?.isSelected = true
        }
        if (forceAll || lastBoundArtist != s.artist) {
            lastBoundArtist = s.artist
            expArtist?.text = s.artist
        }

        // 3. Accent color & waveform state
        val vibrantAccent = boostAccentForDarkGlass(s.accentColor)
        if (forceAll || lastAccentBound != s.accentColor) {
            lastAccentBound = s.accentColor
            lastBgColor = s.accentColor
            pillSpectrum?.setBarColor(vibrantAccent)
            expandedContainer?.background = createExpandedGlassBackground(s.accentColor)
        }
        pillSpectrum?.setActive(s.playing && !isExpanded)

        // 4. Expanded card live seekbar & buttons (only update when expanded or forced!)
        if (!isExpanded && !forceAll) return

        val dur = s.durationMs.coerceAtLeast(0L)
        val pos = s.positionMs.coerceIn(0L, max(dur, 1L))
        if (!userSeeking) {
            expPosText?.text = formatTime(pos)
            expDurText?.text = if (dur > 0L) formatTime(dur) else "--:--"
            expSeekBar?.progress = if (dur > 0L) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
        }

        if (forceAll || lastPlayingBound != s.playing) {
            lastPlayingBound = s.playing
            expPlayPauseBtn?.setImageDrawable(
                createIconDrawable(if (s.playing) IconKind.PAUSE else IconKind.PLAY, Color.WHITE)
            )
        }
        if (forceAll || lastLikedBound != s.liked) {
            lastLikedBound = s.liked
            expLikeBtn?.setImageDrawable(
                createIconDrawable(
                    if (s.liked) IconKind.LIKED_CHECK else IconKind.LIKE_OUTLINE,
                    if (s.liked) Color.parseColor("#FF22C55E") else Color.parseColor("#CCFFFFFF"),
                )
            )
        }
        if (forceAll || lastShuffleBound != s.shuffle || forceAll) {
            lastShuffleBound = s.shuffle
            expShuffleBtn?.setImageDrawable(
                createIconDrawable(
                    IconKind.SHUFFLE,
                    if (s.shuffle) vibrantAccent else Color.parseColor("#B8FFFFFF"),
                )
            )
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        pillSpectrum?.setActive(false)
        if (isAdded && rootView != null) {
            runCatching { wm.removeViewImmediate(rootView) }
        }
        isAdded = false
        isExpanded = false
        rootView = null
        pillContainer = null
        expandedContainer = null
    }

    private fun createPillBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18f).toFloat()
            setColor(Color.parseColor("#F0080B10"))
            setStroke(dp(1f), Color.parseColor("#44FFFFFF"))
        }

    private fun createExpandedGlassBackground(accentArgb: Int): LayerDrawable {
        // Clamp glow brightness so white text & icons always maintain crisp contrast
        val hsv = FloatArray(3)
        Color.colorToHSV(accentArgb, hsv)
        hsv[1] = hsv[1].coerceIn(0.42f, 0.92f)
        hsv[2] = hsv[2].coerceIn(0.28f, 0.62f)
        val safeGlow = Color.HSVToColor(hsv)
        val r = Color.red(safeGlow)
        val g = Color.green(safeGlow)
        val b = Color.blue(safeGlow)

        // Base dark obsidian liquid glass
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28f).toFloat()
            setColor(Color.parseColor("#F00A0D13"))
        }

        // Bottom ambient cover-art glow (matching Spotify's Origin Island warm bottom aura)
        val glow = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(
                Color.argb(180, r, g, b),
                Color.argb(66, r, g, b),
                Color.TRANSPARENT,
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28f).toFloat()
        }

        // Top Liquid Glass specular border
        val rim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28f).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1.1f), Color.parseColor("#4AFFFFFF"))
        }

        return LayerDrawable(arrayOf(base, glow, rim))
    }

    private fun boostAccentForDarkGlass(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun roundedBitmap(source: Bitmap, sizePx: Int, circular: Boolean, radiusPx: Float = 0f): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        if (circular) {
            canvas.drawOval(rect, paint)
        } else {
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        }
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val srcW = source.width.toFloat().coerceAtLeast(1f)
        val srcH = source.height.toFloat().coerceAtLeast(1f)
        val scale = max(sizePx / srcW, sizePx / srcH)
        val dx = (sizePx - srcW * scale) / 2f
        val dy = (sizePx - srcH * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(source, matrix, paint)
        return out
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format("%02d:%02d", min, sec)
    }

    private enum class IconKind { PLAY, PAUSE, NEXT, PREV, LIKED_CHECK, LIKE_OUTLINE, SHUFFLE, WAVE_BADGE }

    private fun createIconDrawable(kind: IconKind, tint: Int): BitmapDrawable {
        val size = dp(28f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = size.toFloat()
        val h = size.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        when (kind) {
            IconKind.PAUSE -> {
                val barW = w * 0.20f
                val gap = w * 0.14f
                val top = h * 0.18f
                val bot = h * 0.82f
                val cx = w / 2f
                c.drawRoundRect(RectF(cx - gap / 2f - barW, top, cx - gap / 2f, bot), barW / 2f, barW / 2f, p)
                c.drawRoundRect(RectF(cx + gap / 2f, top, cx + gap / 2f + barW, bot), barW / 2f, barW / 2f, p)
            }
            IconKind.PLAY -> {
                val path = Path().apply {
                    moveTo(w * 0.28f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.28f, h * 0.82f)
                    close()
                }
                c.drawPath(path, p)
            }
            IconKind.NEXT -> {
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.22f)
                    lineTo(w * 0.64f, h * 0.50f)
                    lineTo(w * 0.22f, h * 0.78f)
                    close()
                }
                c.drawPath(path, p)
                c.drawRoundRect(RectF(w * 0.68f, h * 0.22f, w * 0.80f, h * 0.78f), w * 0.05f, w * 0.05f, p)
            }
            IconKind.PREV -> {
                val path = Path().apply {
                    moveTo(w * 0.78f, h * 0.22f)
                    lineTo(w * 0.36f, h * 0.50f)
                    lineTo(w * 0.78f, h * 0.78f)
                    close()
                }
                c.drawPath(path, p)
                c.drawRoundRect(RectF(w * 0.20f, h * 0.22f, w * 0.32f, h * 0.78f), w * 0.05f, w * 0.05f, p)
            }
            IconKind.LIKED_CHECK -> {
                c.drawCircle(w / 2f, h / 2f, w * 0.40f, p)
                p.color = Color.parseColor("#FF0B0E14")
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.10f
                val path = Path().apply {
                    moveTo(w * 0.32f, h * 0.51f)
                    lineTo(w * 0.45f, h * 0.64f)
                    lineTo(w * 0.69f, h * 0.38f)
                }
                c.drawPath(path, p)
            }
            IconKind.LIKE_OUTLINE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                c.drawCircle(w / 2f, h / 2f, w * 0.38f, p)
                c.drawLine(w * 0.50f, h * 0.32f, w * 0.50f, h * 0.68f, p)
                c.drawLine(w * 0.32f, h * 0.50f, w * 0.68f, h * 0.50f, p)
            }
            IconKind.SHUFFLE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.085f
                val p1 = Path().apply {
                    moveTo(w * 0.18f, h * 0.32f)
                    lineTo(w * 0.38f, h * 0.32f)
                    lineTo(w * 0.62f, h * 0.68f)
                    lineTo(w * 0.80f, h * 0.68f)
                }
                val p2 = Path().apply {
                    moveTo(w * 0.18f, h * 0.68f)
                    lineTo(w * 0.38f, h * 0.68f)
                    lineTo(w * 0.62f, h * 0.32f)
                    lineTo(w * 0.80f, h * 0.32f)
                }
                c.drawPath(p1, p)
                c.drawPath(p2, p)
                c.drawLine(w * 0.72f, h * 0.24f, w * 0.82f, h * 0.32f, p)
                c.drawLine(w * 0.72f, h * 0.40f, w * 0.82f, h * 0.32f, p)
                c.drawLine(w * 0.72f, h * 0.60f, w * 0.82f, h * 0.68f, p)
                c.drawLine(w * 0.72f, h * 0.76f, w * 0.82f, h * 0.68f, p)
            }
            IconKind.WAVE_BADGE -> {
                val barW = w * 0.11f
                val heights = floatArrayOf(0.36f, 0.74f, 0.54f, 0.84f)
                val xs = floatArrayOf(0.20f, 0.40f, 0.60f, 0.80f)
                for (i in heights.indices) {
                    val bh = h * heights[i]
                    val top = (h - bh) / 2f
                    val cx = w * xs[i]
                    c.drawRoundRect(RectF(cx - barW / 2f, top, cx + barW / 2f, top + bh), barW / 2f, barW / 2f, p)
                }
            }
        }
        return BitmapDrawable(context.resources, bmp)
    }

    /** Lightweight hardware-layered 4-bar audio visualizer throttled to ~24fps for zero stutter. */
    private class SpectrumBarsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1DB954")
            style = Paint.Style.FILL
        }
        private var phase = 0f
        private var active = false
        private var lastFrameMs = 0L
        private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val now = System.currentTimeMillis()
                if (now - lastFrameMs >= 42L) {
                    lastFrameMs = now
                    phase = it.animatedValue as Float
                    invalidate()
                }
            }
        }

        fun setBarColor(color: Int) {
            if (paint.color != color) {
                paint.color = color
                invalidate()
            }
        }

        fun setActive(playing: Boolean) {
            if (active == playing) return
            active = playing
            if (playing) {
                if (!animator.isStarted) animator.start()
            } else {
                animator.cancel()
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val count = 4
            val gap = w * 0.11f
            val barW = (w - gap * (count - 1)) / count
            val offsets = floatArrayOf(0f, 1.4f, 2.7f, 0.7f)
            for (i in 0 until count) {
                val frac = if (active) {
                    0.28f + 0.66f * abs(sin(phase + offsets[i])).toFloat()
                } else {
                    0.24f
                }
                val bh = (h * frac).coerceAtLeast(barW)
                val top = (h - bh) / 2f
                val left = i * (barW + gap)
                canvas.drawRoundRect(RectF(left, top, left + barW, top + bh), barW / 2f, barW / 2f, paint)
            }
        }
    }
}
