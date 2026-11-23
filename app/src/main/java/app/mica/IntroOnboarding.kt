package app.mica

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class IntroSlide(
    val title: String,
    val subtitle: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color,
    val isPermissionSlide: Boolean = false,
)

val introSlides = listOf(
    IntroSlide(
        title = "Welcome to MICA ONE",
        subtitle = "The Universe of Entertainment",
        description = "Crafted with passion for pristine sound, cinematic 4K video, and ad-free streaming in one lightning-fast player.",
        icon = Icons.Rounded.GraphicEq,
        accentColor = Color(0xFF6366F1),
    ),
    IntroSlide(
        title = "Ad-Shield & Stream Sniffer",
        subtitle = "Explore Without Popups or Ads",
        description = "Browse anime, cartoons, and web videos with Brave-style ad-blocking. LibVLC hardware engine turns any stream into 4K clarity with a single tap.",
        icon = Icons.Rounded.CloudDownload,
        accentColor = Color(0xFF06B6D4),
    ),
    IntroSlide(
        title = "Studio Subtitles & Gestures",
        subtitle = "Active Always & Hand-Crafted",
        description = "Pinch to zoom, slide for volume, and enjoy crystal-clear subtitles that always stay active and in sync automatically.",
        icon = Icons.Rounded.Subtitles,
        accentColor = Color(0xFFF59E0B),
    ),
    IntroSlide(
        title = "MICA Jam — Listen Together",
        subtitle = "Real-Time Music Sync",
        description = "Turn any room into a concert. Connect with friends over Wi-Fi or hotspot and stream in perfect sync without internet.",
        icon = Icons.Rounded.Group,
        accentColor = Color(0xFFEC4899),
    ),
    IntroSlide(
        title = "Honest Permissions & Storage",
        subtitle = "Why We Need Your Permission",
        description = "We believe in authentic user privacy. MICA needs media access strictly on your phone to organize your songs and videos, and notification access for seamless lock screen playback controls. Nothing ever leaves your device.",
        icon = Icons.Rounded.FolderOpen,
        accentColor = Color(0xFF10B981),
        isPermissionSlide = true,
    ),
)

object IntroManager {
    private const val PREF_KEY = "intro_completed_v081"

    fun isIntroCompleted(context: Context): Boolean {
        return Store.prefs(context).getBoolean(PREF_KEY, false)
    }

    fun markIntroCompleted(context: Context) {
        Store.prefs(context).edit().putBoolean(PREF_KEY, true).apply()
    }

    fun resetIntro(context: Context) {
        Store.prefs(context).edit().putBoolean(PREF_KEY, false).apply()
    }
}

@Composable
fun IntroOnboardingScreen(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { introSlides.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Gradient atmospheric background glow
        val currentAccent = introSlides[pagerState.currentPage].accentColor
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(400.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            currentAccent.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        radius = 800f,
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar with Skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MICA ONE",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )

                if (pagerState.currentPage < introSlides.size - 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable {
                                IntroManager.markIntroCompleted(context)
                                (context as? MainActivity)?.requestAccess()
                                onFinished()
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Skip",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Carousel Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                val slide = introSlides[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Feature Icon Orb
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(slide.accentColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(slide.accentColor.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = null,
                                tint = slide.accentColor,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = slide.title,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = slide.subtitle,
                        color = slide.accentColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = slide.description,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                    )

                    if (slide.isPermissionSlide) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(slide.accentColor.copy(alpha = 0.25f))
                                    .border(1.dp, slide.accentColor, RoundedCornerShape(12.dp))
                                    .clickable { (context as? MainActivity)?.requestAccess() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Grant Media & Alerts", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .clickable { (context as? MainActivity)?.openAllFilesAccessSettings() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("All Files Access", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Bottom Navigation and Action Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(introSlides.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (isSelected) 24.dp else 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isSelected) currentAccent else Color.White.copy(alpha = 0.25f)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Primary CTA button
                val isLastPage = pagerState.currentPage == introSlides.size - 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    currentAccent,
                                    currentAccent.copy(alpha = 0.85f),
                                )
                            )
                        )
                        .clickable {
                            if (isLastPage) {
                                IntroManager.markIntroCompleted(context)
                                (context as? MainActivity)?.requestAccess()
                                onFinished()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isLastPage) "Enter MICA ONE" else "Continue",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
