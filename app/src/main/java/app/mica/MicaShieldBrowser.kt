package app.mica

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI

object MicaAdShield {
    private val blockedHosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "admob.com", "popads.net", "popcash.net",
        "propellerads.com", "adcash.com", "exoclick.com", "adnxs.com",
        "criteo.com", "taboola.com", "outbrain.com", "juicyads.com",
        "trafficjunky.com", "scorecardresearch.com", "quantserve.com",
        "moatads.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "casalemedia.com", "adroll.com", "zemanta.com", "smartadserver.com",
        "revcontent.com", "mgid.com", "infolinks.com", "bidswitch.net",
        "amazon-adsystem.com", "applovin.com", "unityads.unity3d.com",
        "vungle.com", "ironsrc.com", "chartboost.com", "adcolony.com",
        "adblade.com", "adfox.ru", "adtechus.com", "advertising.com",
        "adversal.com", "adzerk.net", "affiliatetracking.com", "clicksor.com",
        "ero-advertising.com", "feedjit.com", "histats.com", "imrworldwide.com",
        "kontera.com", "media.net", "statcounter.com", "trafficshop.com",
        "yandexadexchange.net", "zergnet.com", "track.adform.net", "disqus.com",
        "ay267.com"
    )

    // Pattern to catch numbered ad redirect domains like ay123.com, ay456.com etc.
    private val adRedirectPattern = Regex("""^ay\d+\.com$""", RegexOption.IGNORE_CASE)

    fun preload(context: Context) {
        // Pre-warm ad-blocking HashSet
    }

    fun isAdHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val clean = host.lowercase().trim()
        if (clean in blockedHosts) return true
        if (blockedHosts.any { bad -> clean.endsWith(".$bad") }) return true
        // Block numbered ad redirect domains like ay123.com, ay456.com
        if (adRedirectPattern.matches(clean)) return true
        // Block common ad redirect pattern hosts
        val parts = clean.split('.')
        if (parts.size >= 2) {
            val domain = parts[parts.size - 2]
            if (domain.matches(Regex("""^(redirect|click|track|pop|ads?)\d*$"""))) return true
        }
        return false
    }

    val COSMETIC_CSS = """
        iframe[id*='google_ads'], .adsbygoogle, [class*='ad-banner'], 
        [id*='ad-banner'], .ad-container, .ad-box, [class*='sponsor'], 
        [id*='sponsor'], div[class*='ad_'], div[id*='ad_'], .banner-ad,
        .pop-ad, .popup-overlay, #disqus_thread,
        .modal-backdrop, [class*='telegram'], [class*='Telegram'],
        [class*='app-download'], [class*='download-app'],
        [class*='mobile-app'], div[class*='popup'] { 
            display: none !important; 
            visibility: hidden !important; 
            height: 0 !important; 
            width: 0 !important; 
            opacity: 0 !important;
            pointer-events: none !important;
        }
    """.trimIndent()

    val YOUTUBE_SKIP_SCRIPT = """
        (function() {
            function skipAd() {
                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                if (skipBtn) { skipBtn.click(); }
                var overlayClose = document.querySelector('.ytp-ad-overlay-close-button');
                if (overlayClose) { overlayClose.click(); }
                var video = document.querySelector('video');
                var adContainer = document.querySelector('.ad-showing, .video-ads');
                if (video && adContainer && isFinite(video.duration)) {
                    video.currentTime = video.duration - 0.1;
                }
            }
            setInterval(skipAd, 600);
        })();
    """.trimIndent()
}

data class DetectedStream(
    val url: String,
    val title: String,
    val mimeType: String = "video/mp4",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MicaShieldBrowserScreen(
    initialUrl: String,
    title: String,
    onBack: () -> Unit,
    onPlayInMica: (MediaItem) -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf(title) }
    var blockedCount by remember { mutableIntStateOf(0) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var detectedStream by remember { mutableStateOf<DetectedStream?>(null) }
    var detectedSubtitleUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var isResolvingStream by remember { mutableStateOf(false) }
    var lastResolvedUrl by remember { mutableStateOf("") }
    var autoLaunchedStreamUrl by remember { mutableStateOf("") }

    fun autoLaunchInMica(stream: DetectedStream) {
        if (stream.url.isBlank() || stream.url == autoLaunchedStreamUrl) return
        autoLaunchedStreamUrl = stream.url
        // Immediately pause and mute all HTML5 audio/video inside WebView to prevent dual audio!
        webViewRef?.evaluateJavascript(
            "document.querySelectorAll('video, audio').forEach(function(el) { el.pause(); el.muted = true; });",
            null
        )
        val cleanTitle = (stream.title.ifBlank { pageTitle })
            .replace(Regex("""(?i)\s*[-|]\s*reaper.*"""), "")
            .replace(Regex("""(?i)^watch\s+"""), "")
            .trim()
        val mediaItem = MediaItem(
            uri = stream.url,
            title = cleanTitle.ifBlank { "Playing Stream" },
            audio = false,
            folder = "Stream Sniffer",
            subtitleUri = detectedSubtitleUrl
        )
        onPlayInMica(mediaItem)
    }

    fun isYouTubeWatchUrl(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        val clean = u.lowercase()
        return (clean.contains("youtube.com/watch") || clean.contains("youtu.be/") || clean.contains("youtube.com/shorts/")) &&
            !clean.contains("accounts.google.com")
    }

    fun playInMicaDirect(rawUrl: String, customTitle: String? = null) {
        if (rawUrl.isBlank() || rawUrl == lastResolvedUrl || isResolvingStream) return
        isResolvingStream = true
        lastResolvedUrl = rawUrl
        // Immediately pause and mute all HTML5 audio/video inside WebView to prevent dual audio!
        webViewRef?.evaluateJavascript(
            "document.querySelectorAll('video, audio').forEach(function(el) { el.pause(); el.muted = true; });",
            null
        )
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                StreamResolver.resolve(rawUrl)
            }
            isResolvingStream = false
            if (resolved.streamUrl.isNotBlank()) {
                val cleanTitle = (customTitle?.takeIf { it.isNotBlank() } ?: resolved.title)
                    .replace(Regex("""(?i)\s*[-|]\s*reaper.*"""), "")
                    .replace(Regex("""(?i)^watch\s+"""), "")
                    .trim()
                val mediaItem = MediaItem(
                    uri = resolved.streamUrl,
                    title = cleanTitle.ifBlank { "Playing Stream" },
                    audio = false,
                    folder = "Stream Sniffer",
                    subtitleUri = resolved.subtitleUrl ?: detectedSubtitleUrl,
                    streamUserAgent = resolved.userAgent
                )
                onPlayInMica(mediaItem)
            }
        }
    }

    DisposableEffect(Unit) {
        webViewRef?.onResume()
        onDispose { }
    }

    fun navigateBackSafely() {
        val wv = webViewRef ?: run { onBack(); return }
        if (!wv.canGoBack()) {
            // If we're on the initial URL, exit; otherwise go back to the initial URL
            if (currentUrl == initialUrl || currentUrl.isBlank()) {
                onBack()
            } else {
                wv.loadUrl(initialUrl)
            }
            return
        }
        val list = wv.copyBackForwardList()
        val currIdx = list.currentIndex
        if (currIdx <= 0) {
            if (currentUrl == initialUrl || currentUrl.isBlank()) {
                onBack()
            } else {
                wv.loadUrl(initialUrl)
            }
            return
        }
        var stepBack = -1
        while (currIdx + stepBack >= 0) {
            val item = list.getItemAtIndex(currIdx + stepBack)
            val host = Uri.parse(item.url).host
            if (MicaAdShield.isAdHost(host) || item.url == currentUrl) {
                stepBack--
            } else {
                break
            }
        }
        if (currIdx + stepBack >= 0) {
            wv.goBackOrForward(stepBack)
        } else {
            // All history entries were ad redirects; reload the initial URL instead of exiting
            wv.loadUrl(initialUrl)
        }
    }

    BackHandler {
        navigateBackSafely()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E1A))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Modern Sleek App Bar with Brave-style Shield Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF111827))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    navigateBackSafely()
                }) {
                    Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White)
                }

                // Shield Status Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(100.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Security, "MICA Ad Shield Active", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.width(8.dp))

                // URL & Title pill
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1F2937))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        pageTitle.ifBlank { "Mica Browser" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        currentUrl.substringAfter("://").substringBefore("/"),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Rounded.Refresh, "Refresh", tint = Color.White, modifier = Modifier.size(20.dp))
                }

                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // Web Loading Progress
            if (isLoading && loadingProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadingProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.5.dp),
                    color = Color(0xFF38BDF8),
                    trackColor = Color.Transparent,
                )
            }

            // Embedded Android WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 MicaBrowser/1.0"
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                        }

                        // Stream sniffer JS interface
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onVideoFound(videoUrl: String, videoTitle: String) {
                                if (videoUrl.isNotBlank() && !videoUrl.startsWith("blob:")) {
                                    post {
                                        val clean = (if (videoTitle.isNotBlank()) videoTitle else pageTitle)
                                            .replace(Regex("""(?i)\s*[-|]\s*reaper.*"""), "")
                                            .replace(Regex("""(?i)^watch\s+"""), "")
                                            .trim()
                                        val s = DetectedStream(
                                            url = videoUrl,
                                            title = clean.ifBlank { "Playing Stream" }
                                        )
                                        detectedStream = s
                                        autoLaunchInMica(s)
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onSubtitleFound(subUrl: String, label: String) {
                                if (subUrl.isNotBlank() && !subUrl.startsWith("blob:")) {
                                    post {
                                        detectedSubtitleUrl = subUrl
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onYouTubeWatch(watchUrl: String, watchTitle: String) {
                                post {
                                    if (isYouTubeWatchUrl(watchUrl)) {
                                        playInMicaDirect(watchUrl, watchTitle)
                                    }
                                }
                            }
                        }, "MicaSniffer")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val reqUrl = request?.url ?: return false
                                val host = reqUrl.host
                                if (MicaAdShield.isAdHost(host)) {
                                    post { blockedCount++ }
                                    return true
                                }
                                val urlStr = reqUrl.toString()
                                if (isYouTubeWatchUrl(urlStr)) {
                                    playInMicaDirect(urlStr, view?.title ?: pageTitle)
                                    return true
                                }
                                if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                                    return true
                                }
                                return false
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                val u = url ?: return
                                if (isYouTubeWatchUrl(u)) {
                                    playInMicaDirect(u, view?.title ?: pageTitle)
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url ?: return null
                                val host = reqUrl.host

                                // 1. Ad & Tracker Interception
                                if (MicaAdShield.isAdHost(host)) {
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                }

                                // 2. Video Stream Sniffer (.m3u8 / .mp4 / video streams / anime / cartoons)
                                val urlStr = reqUrl.toString()
                                val path = reqUrl.path?.lowercase() ?: ""
                                val isYtHost = host?.contains("youtube.com", ignoreCase = true) == true ||
                                    host?.contains("googlevideo.com", ignoreCase = true) == true
                                val isVideoCandidate = !isYtHost && (
                                    path.endsWith(".m3u8") ||
                                    path.endsWith(".mp4") ||
                                    path.endsWith(".mkv") ||
                                    urlStr.contains(".m3u8", ignoreCase = true) ||
                                    urlStr.contains(".mp4", ignoreCase = true) ||
                                    urlStr.contains("/stream/", ignoreCase = true) ||
                                    urlStr.contains("get_video", ignoreCase = true) ||
                                    urlStr.contains("master.m3u8", ignoreCase = true) ||
                                    urlStr.contains("/hls/", ignoreCase = true)
                                )

                                if (isVideoCandidate && !urlStr.contains(".js") && !urlStr.contains(".css") && !urlStr.contains(".png") && !urlStr.contains(".jpg")) {
                                    if (detectedStream?.url != urlStr) {
                                        post {
                                            if (detectedStream?.url != urlStr) {
                                                val s = DetectedStream(
                                                    url = urlStr,
                                                    title = view?.title ?: pageTitle
                                                )
                                                detectedStream = s
                                                autoLaunchInMica(s)
                                            }
                                        }
                                    }
                                }

                                // 3. Subtitle Sniffer (.vtt / .srt / timedtext / /captions/)
                                val isSubtitleCandidate = path.endsWith(".vtt") ||
                                    path.endsWith(".srt") ||
                                    urlStr.contains(".vtt", ignoreCase = true) ||
                                    urlStr.contains(".srt", ignoreCase = true) ||
                                    urlStr.contains("timedtext", ignoreCase = true) ||
                                    urlStr.contains("/captions/", ignoreCase = true)

                                if (isSubtitleCandidate && !urlStr.contains(".js") && !urlStr.contains(".css") && !urlStr.contains(".png") && !urlStr.contains(".jpg")) {
                                    if (detectedSubtitleUrl != urlStr) {
                                        post {
                                            detectedSubtitleUrl = urlStr
                                        }
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                currentUrl = url.orEmpty()
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                currentUrl = url.orEmpty()
                                pageTitle = view?.title.orEmpty().ifBlank { title }
                                canGoBack = view?.canGoBack() == true

                                // Inject Cosmetic CSS to collapse ads
                                val cssCode = "(function() { " +
                                    "var style = document.createElement('style'); " +
                                    "style.type = 'text/css'; " +
                                    "style.innerHTML = '" + MicaAdShield.COSMETIC_CSS.replace("\n", " ") + "'; " +
                                    "document.head.appendChild(style); " +
                                    "})();"
                                view?.evaluateJavascript(cssCode, null)

                                // Inject YouTube skip script
                                view?.evaluateJavascript(MicaAdShield.YOUTUBE_SKIP_SCRIPT, null)

                                if (isYouTubeWatchUrl(url)) {
                                    playInMicaDirect(url.orEmpty(), pageTitle)
                                }

                                // Inject HTML5 video, iframe & subtitle track listener for anime & cartoons (ReaperClubz)
                                val videoSniffScript = """
                                    (function() {
                                        if (window._micaSniffInit) return;
                                        window._micaSniffInit = true;
                                        // YouTube Mobile SPA & link interception
                                        window.addEventListener('yt-navigate-finish', function() {
                                            var u = window.location.href;
                                            if (u.indexOf('/watch') !== -1 || u.indexOf('/shorts') !== -1) {
                                                window.MicaSniffer.onYouTubeWatch(u, document.title || '');
                                            }
                                        });
                                        document.addEventListener('click', function(e) {
                                            var a = e.target.closest('a');
                                            if (a && a.href && (a.href.indexOf('/watch') !== -1 || a.href.indexOf('/shorts') !== -1)) {
                                                window.MicaSniffer.onYouTubeWatch(a.href, a.innerText || document.title || '');
                                            }
                                        }, true);
                                        function getCleanPageTitle() {
                                            var heading = document.querySelector('h1, h2, .anime-title, .title, .film-name, .entry-title');
                                            var raw = (heading ? (heading.innerText || heading.textContent) : '') || document.title || '';
                                            return raw.replace(/[-|]\s*reaper.*/i, '').replace(/^watch\s+/i, '').trim();
                                        }
                                        function checkVideoAndSubs() {
                                            var cleanTitle = getCleanPageTitle();
                                            var vList = document.querySelectorAll('video');
                                            for (var i = 0; i < vList.length; i++) {
                                                var v = vList[i];
                                                var s = v.src || (v.querySelector('source') ? v.querySelector('source').src : '');
                                                if (s && s.startsWith('http')) {
                                                    window.MicaSniffer.onVideoFound(s, cleanTitle);
                                                }
                                                var tracks = v.querySelectorAll('track');
                                                for (var j = 0; j < tracks.length; j++) {
                                                    var t = tracks[j];
                                                    if (t.src && t.src.startsWith('http')) {
                                                        window.MicaSniffer.onSubtitleFound(t.src, t.label || t.srclang || 'Subtitles');
                                                    }
                                                }
                                            }
                                            var allTracks = document.querySelectorAll('track');
                                            for (var k = 0; k < allTracks.length; k++) {
                                                var trk = allTracks[k];
                                                if (trk.src && trk.src.startsWith('http')) {
                                                    window.MicaSniffer.onSubtitleFound(trk.src, trk.label || 'Subtitles');
                                                }
                                            }
                                        }
                                        setInterval(checkVideoAndSubs, 1500);
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(videoSniffScript, null)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadingProgress = newProgress
                                if (newProgress == 100) isLoading = false
                            }

                            override fun onReceivedTitle(view: WebView?, t: String?) {
                                if (!t.isNullOrBlank()) pageTitle = t
                            }
                        }

                        loadUrl(initialUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // Animated loading skeleton overlay: replaces dead black screen with informative MICA Shield loader
        AnimatedVisibility(
            visible = isLoading && loadingProgress < 85,
            enter = fadeIn(),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 56.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0E1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.5.dp, Color(0xFF10B981).copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = "Shield",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Text(
                        pageTitle.ifBlank { title },
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Securing connection & blocking ads...",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.5.sp
                    )
                    LinearProgressIndicator(
                        progress = { (loadingProgress.coerceAtLeast(10) / 100f).coerceIn(0.1f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF10B981),
                        trackColor = Color(0xFF1E293B)
                    )
                }
            }
        }

        // Animated Stream Sniffer Floating Banner: "▶ Play in Mica Video Player"
        AnimatedVisibility(
            visible = detectedStream != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            detectedStream?.let { stream ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF2563EB), Color(0xFF7C3AED))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable {
                            val cleanTitle = (stream.title.ifBlank { pageTitle })
                                .replace(Regex("""(?i)\s*[-|]\s*reaper.*"""), "")
                                .replace(Regex("""(?i)^watch\s+"""), "")
                                .trim()
                            val mediaItem = MediaItem(
                                uri = stream.url,
                                title = cleanTitle.ifBlank { "Playing Stream" },
                                audio = false,
                                folder = "Stream Sniffer",
                                subtitleUri = detectedSubtitleUrl
                            )
                            // Immediately pause and mute all HTML5 audio/video inside WebView to prevent dual audio!
                            webViewRef?.evaluateJavascript(
                                "document.querySelectorAll('video, audio').forEach(function(el) { el.pause(); el.muted = true; });",
                                null
                            )
                            onPlayInMica(mediaItem)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.PlayArrow, "Play", tint = Color(0xFF2563EB), modifier = Modifier.size(24.dp))
                        }
                        Column {
                            Text(
                                "Play in MICA Video Player",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "LibVLC 4K • Hardware Accel • Subtitles",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = { detectedStream = null },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Rounded.Close, "Dismiss", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Animated Resolving Stream Indicator: "Opening in MICA Video Player..."
        AnimatedVisibility(
            visible = isResolvingStream,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.2.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = Color(0xFF38BDF8),
                    strokeWidth = 2.8.dp
                )
                Column {
                    Text(
                        "Opening in MICA Video Player...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Resolving high quality stream & hardware acceleration",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}
