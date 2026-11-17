package app.msplayer.offline

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.MutableStateFlow

enum class AppThemeMode(val key: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    DARK_LIQUID_GLASS("dark_liquid_glass", "Dark Liquid Glass"),
    WHITE_LIQUID_GLASS("white_liquid_glass", "White Liquid Glass");

    companion object {
        fun fromStorage(rawKey: String?, legacyGlassEnabled: Boolean): AppThemeMode =
            when (rawKey?.lowercase()) {
                "light" -> LIGHT
                "dark" -> DARK
                "dark_liquid_glass", "dark_glass", "liquid_glass", "auto_glass", "auto" -> DARK_LIQUID_GLASS
                "white_liquid_glass", "light_glass" -> WHITE_LIQUID_GLASS
                else -> if (legacyGlassEnabled) DARK_LIQUID_GLASS else DARK
            }
    }
}

@Immutable
data class AppPalette(
    val mode: AppThemeMode,
    val isLight: Boolean,
    val isGlass: Boolean,
    val background: Color,
    val surface: Color,
    val cardTint: Color,
    val cardFallback: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSecondary: Color,
    val onAccent: Color,
    val navContainer: Color,
    val popupTint: Color,
    val popupFallback: Color,
    val chipBackground: Color,
    val chipText: Color,
    val divider: Color,
)

val LightPalette = AppPalette(
    mode = AppThemeMode.LIGHT,
    isLight = true,
    isGlass = false,
    background = Color(0xFFF3F6FA),
    surface = Color(0xFFFFFFFF),
    cardTint = Color(0xFFFFFFFF),
    cardFallback = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    accent = Color(0xFF0284C7),
    accentSecondary = Color(0xFF0369A1),
    onAccent = Color.White,
    navContainer = Color(0xFFFFFFFF),
    popupTint = Color(0xFFFFFFFF),
    popupFallback = Color(0xFFFFFFFF),
    chipBackground = Color(0xFFE2E8F0),
    chipText = Color(0xFF0F172A),
    divider = Color(0xFFCBD5E1),
)

val DarkPalette = AppPalette(
    mode = AppThemeMode.DARK,
    isLight = false,
    isGlass = false,
    background = Color(0xFF070A0F),
    surface = Color(0xFF121721),
    cardTint = Color(0xFF121721),
    cardFallback = Color(0xFF121721),
    cardBorder = Color.White.copy(alpha = 0.12f),
    textPrimary = Color.White,
    textSecondary = Color(0xFF9FB0C4),
    accent = Color(0xFF00A8FF),
    accentSecondary = Color(0xFF66D8F3),
    onAccent = Color.Black,
    navContainer = Color(0xFF0E131B),
    popupTint = Color(0xFF161D28),
    popupFallback = Color(0xFF161D28),
    chipBackground = Color(0xFF1E2633),
    chipText = Color(0xFFE2E8F0),
    divider = Color.White.copy(alpha = 0.10f),
)

val WhiteLiquidGlassPalette = AppPalette(
    mode = AppThemeMode.WHITE_LIQUID_GLASS,
    isLight = true,
    isGlass = true,
    background = Color(0xFFF4F8FF),
    surface = Color(0xCCFFFFFF),
    cardTint = Color(0xB8FFFFFF),
    cardFallback = Color(0xF5FFFFFF),
    cardBorder = Color.White.copy(alpha = 0.92f),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    accent = Color(0xFF0284C7),
    accentSecondary = Color(0xFF0EA5E9),
    onAccent = Color.White,
    navContainer = Color(0xB8FFFFFF),
    popupTint = Color(0xE8FFFFFF),
    popupFallback = Color(0xFFFFFFFF),
    chipBackground = Color(0xB8FFFFFF),
    chipText = Color(0xFF0F172A),
    divider = Color(0xFFCBD5E1),
)

val DarkLiquidGlassPalette = AppPalette(
    mode = AppThemeMode.DARK_LIQUID_GLASS,
    isLight = false,
    isGlass = true,
    background = Color(0xFF020409),
    surface = Color(0xFF0B1320),
    cardTint = Color(0x4C0B1320),
    cardFallback = Color(0xFF101318),
    cardBorder = Color.White.copy(alpha = 0.18f),
    textPrimary = Color.White,
    textSecondary = Color(0xFF9FB0C4),
    accent = Color(0xFF00A8FF),
    accentSecondary = Color(0xFF66D8F3),
    onAccent = Color.Black,
    navContainer = Color(0x360C1524),
    popupTint = Color(0xEB070D17),
    popupFallback = Color(0xFF131922),
    chipBackground = Color(0xFF283342),
    chipText = Color(0xFFE0E5EF),
    divider = Color.White.copy(alpha = 0.10f),
)

val LightGlassPalette = WhiteLiquidGlassPalette
val DarkGlassPalette = DarkLiquidGlassPalette
val LightAppPalette = LightPalette
val DarkAppPalette = DarkPalette
val GlassAppPalette = DarkLiquidGlassPalette

val LocalAppPalette = staticCompositionLocalOf { DarkLiquidGlassPalette }

fun paletteFor(mode: AppThemeMode, isSystemDark: Boolean = true): AppPalette = when (mode) {
    AppThemeMode.LIGHT -> LightPalette
    AppThemeMode.DARK -> DarkPalette
    AppThemeMode.DARK_LIQUID_GLASS -> DarkLiquidGlassPalette
    AppThemeMode.WHITE_LIQUID_GLASS -> WhiteLiquidGlassPalette
}

object GlassTheme {
    val enabled = MutableStateFlow(true)
    val themeMode = MutableStateFlow(AppThemeMode.DARK_LIQUID_GLASS)
    val CrystalCobalt = Color(0xFF0A66FF)

    fun init(context: Context) {
        val prefs = Store.prefs(context)
        val resolved = AppThemeMode.fromStorage(
            rawKey = prefs.getString("app_theme_mode", null),
            legacyGlassEnabled = prefs.getBoolean("liquid_glass", true),
        )
        themeMode.value = resolved
        enabled.value = resolved == AppThemeMode.DARK_LIQUID_GLASS || resolved == AppThemeMode.WHITE_LIQUID_GLASS
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        val isGlass = mode == AppThemeMode.DARK_LIQUID_GLASS || mode == AppThemeMode.WHITE_LIQUID_GLASS
        Store.prefs(context).edit()
            .putString("app_theme_mode", mode.key)
            .putBoolean("liquid_glass", isGlass)
            .apply()
        themeMode.value = mode
        enabled.value = isGlass
    }

    fun setEnabled(context: Context, value: Boolean) {
        val cur = themeMode.value
        val target = if (value) {
            if (cur == AppThemeMode.LIGHT || cur == AppThemeMode.WHITE_LIQUID_GLASS) AppThemeMode.WHITE_LIQUID_GLASS
            else AppThemeMode.DARK_LIQUID_GLASS
        } else {
            if (cur == AppThemeMode.LIGHT || cur == AppThemeMode.WHITE_LIQUID_GLASS) AppThemeMode.LIGHT
            else AppThemeMode.DARK
        }
        setThemeMode(context, target)
    }
}

object MotionSpec {
    val PressSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 900f)
    val AlphaSpring = spring<Float>(dampingRatio = 0.88f, stiffness = 500f)
    val MorphSpring = spring<Float>(dampingRatio = 0.80f, stiffness = 800f)
    val MorphRectSpring = spring<androidx.compose.ui.geometry.Rect>(dampingRatio = 0.82f, stiffness = 800f)
    val PanelOffsetSpring = spring<IntOffset>(dampingRatio = 0.84f, stiffness = 850f)
    val ItemPlacementSpring = spring<IntOffset>(dampingRatio = 0.82f, stiffness = 800f)
    val DpSpring = spring<Dp>(dampingRatio = 0.78f, stiffness = 520f)
    val ColorSpring = spring<Color>(dampingRatio = 0.90f, stiffness = 360f)

    fun animationsEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
}

@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { MotionSpec.animationsEnabled(context) }
}

data class AdaptiveMoodColors(
    val primary: Color = Accent,
    val secondary: Color = Cyan,
)

/**
 * Samples embedded cover art on a downscaled 24x24 grid in <1ms to tint Liquid Glass glows cleanly.
 */
fun extractAdaptiveMoodColors(bitmap: Bitmap?): AdaptiveMoodColors {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return AdaptiveMoodColors()
    return runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val hsv = FloatArray(3)
        var bestScore = -1f
        var bestColor = android.graphics.Color.rgb(0, 168, 255)
        var secondScore = -1f
        var secondColor = android.graphics.Color.rgb(44, 230, 210)
        var bestHue = -1f

        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val px = scaled.getPixel(x, y)
                if (android.graphics.Color.alpha(px) < 180) continue
                android.graphics.Color.colorToHSV(px, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (sat < 0.22f || value < 0.25f) continue
                val vibrancy = sat * 1.4f + value * 0.7f - kotlin.math.abs(value - 0.68f) * 0.5f
                if (vibrancy > bestScore) {
                    secondScore = bestScore
                    secondColor = bestColor
                    bestScore = vibrancy
                    bestColor = px
                    bestHue = hue
                } else if (vibrancy > secondScore && kotlin.math.abs(hue - bestHue) > 28f) {
                    secondScore = vibrancy
                    secondColor = px
                }
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        if (bestScore <= 0f) AdaptiveMoodColors()
        else {
            val outHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(bestColor, outHsv)
            outHsv[1] = outHsv[1].coerceIn(0.55f, 0.92f)
            outHsv[2] = outHsv[2].coerceIn(0.68f, 1.0f)
            val p = Color(android.graphics.Color.HSVToColor(outHsv))

            android.graphics.Color.colorToHSV(secondColor, outHsv)
            outHsv[1] = outHsv[1].coerceIn(0.45f, 0.90f)
            outHsv[2] = outHsv[2].coerceIn(0.65f, 1.0f)
            val s = if (secondScore > 0f) Color(android.graphics.Color.HSVToColor(outHsv)) else p.copy(alpha = 0.85f)
            AdaptiveMoodColors(primary = p, secondary = s)
        }
    }.getOrDefault(AdaptiveMoodColors())
}

@Composable
fun rememberGlassEnabled(): State<Boolean> = GlassTheme.enabled.collectAsState()

@Composable
fun rememberAppThemeMode(): State<AppThemeMode> = GlassTheme.themeMode.collectAsState()

@Composable
fun rememberAppPalette(): AppPalette {
    val mode by rememberAppThemeMode()
    val isSystemDark = isSystemInDarkTheme()
    return remember(mode, isSystemDark) { paletteFor(mode, isSystemDark) }
}

fun liquidSpecularBrush(
    accentGlow: Float = 0.34f,
    accentColor: Color = Accent,
    isLight: Boolean = false,
): Brush = if (isLight) {
    Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.98f),
            Color.White.copy(alpha = 0.72f),
            Color(0xFFBAE6FD).copy(alpha = 0.45f),
            accentColor.copy(alpha = accentGlow.coerceAtMost(0.32f)),
        )
    )
} else {
    Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.72f),
            Color.White.copy(alpha = 0.24f),
            Color.White.copy(alpha = 0.12f),
            accentColor.copy(alpha = accentGlow),
        )
    )
}

fun liquidSelectedBorder(): Brush = Brush.linearGradient(
    colors = listOf(
        Cyan.copy(alpha = 0.95f),
        GlassTheme.CrystalCobalt,
        Color.White.copy(alpha = 0.85f),
        Cyan.copy(alpha = 0.65f),
    )
)

fun liquidSelectedSurface(): Brush = Brush.verticalGradient(
    colors = listOf(
        Color(0x780B355A),
        Color(0x5608223B),
        Color(0x6E0C2D4E),
    )
)

/**
 * Apple iOS–style spring press response + subtle specular light bloom while held down.
 */
fun Modifier.iosLiquidPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    scaleDown: Float = 0.94f,
    sheenAlpha: Float = 0.14f,
): Modifier = composed {
    val animationsOn = rememberAnimationsEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (enabled && pressed) scaleDown else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (animationsOn) MotionSpec.PressSpring else spring(stiffness = Spring.StiffnessHigh),
        label = "iosPressScale",
    )
    val sheen by animateFloatAsState(
        targetValue = if (enabled && pressed) sheenAlpha else 0f,
        animationSpec = MotionSpec.AlphaSpring,
        label = "iosPressSheen",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (sheen > 0.004f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = sheen * 1.15f),
                            Color.White.copy(alpha = sheen * 0.35f),
                        )
                    )
                )
            }
        }
}

@Composable
fun LiquidGlassBackdropBackground(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    primaryGlow: Color = Accent,
    secondaryGlow: Color = Color(0xFF2CE6D2),
    mode: AppThemeMode = if (enabled) AppThemeMode.DARK_LIQUID_GLASS else AppThemeMode.DARK,
) {
    val pColor by animateColorAsState(primaryGlow, MotionSpec.ColorSpring, label = "primaryGlow")
    val sColor by animateColorAsState(secondaryGlow, MotionSpec.ColorSpring, label = "secondaryGlow")

    when (mode) {
        AppThemeMode.DARK_LIQUID_GLASS -> {
            Box(
                modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF030710),
                                Color(0xFF020409),
                                Color(0xFF06101F),
                            ),
                            start = Offset.Zero,
                            end = Offset(1200f, 2400f),
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    pColor.copy(alpha = 0.34f),
                                    pColor.copy(alpha = 0.11f),
                                    Color.Transparent,
                                ),
                                center = Offset(900f, 340f),
                                radius = 980f,
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    sColor.copy(alpha = 0.26f),
                                    sColor.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                                center = Offset(160f, 1950f),
                                radius = 940f,
                            )
                        )
                )
            }
        }
        AppThemeMode.WHITE_LIQUID_GLASS -> {
            // Optical crystal-pearl backdrop with soft refractive sky/lavender light fields
            // so AGSL liquid-glass lens distortion & chromatic rim effects visibly refract light.
            Box(
                modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFF8FBFF),
                                Color(0xFFEEF5FF),
                                Color(0xFFF3F7FD),
                                Color(0xFFEAF2FC),
                            ),
                            start = Offset.Zero,
                            end = Offset(1200f, 2500f),
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.22f),
                                    Color(0xFF7DD3FC).copy(alpha = 0.09f),
                                    Color.Transparent,
                                ),
                                center = Offset(920f, 280f),
                                radius = 960f,
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF818CF8).copy(alpha = 0.16f),
                                    Color(0xFFC4B5FD).copy(alpha = 0.06f),
                                    Color.Transparent,
                                ),
                                center = Offset(180f, 1280f),
                                radius = 880f,
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF2DD4BF).copy(alpha = 0.14f),
                                    Color(0xFF99F6E4).copy(alpha = 0.05f),
                                    Color.Transparent,
                                ),
                                center = Offset(860f, 2260f),
                                radius = 860f,
                            )
                        )
                )
            }
        }
        AppThemeMode.LIGHT -> {
            Box(modifier.fillMaxSize().background(LightPalette.background))
        }
        AppThemeMode.DARK -> {
            Box(modifier.fillMaxSize().background(DarkPalette.background))
        }
    }
}

fun Modifier.liquidGlassSurface(
    backdrop: LayerBackdrop?,
    enabled: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(22.dp),
    tint: Color = Color(0x2E0B1424),
    blurRadius: Dp = 10.dp,
    refractionHeight: Dp = 12.dp,
    refractionAmount: Dp = 24.dp,
    depthEffect: Boolean = true,
    chromaticAberration: Boolean = true,
    shadow: Shadow? = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.42f)),
    fallbackColor: Color = Color(0xFF101216),
    fallbackBorderColor: Color = Color.White.copy(alpha = 0.14f),
    isLight: Boolean = false,
): Modifier {
    val resolvedShadow = if (isLight && shadow != null) {
        Shadow(radius = 16.dp, color = Color(0xFF0F172A).copy(alpha = 0.10f))
    } else {
        shadow
    }
    if (enabled && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        depthEffect = depthEffect,
                        chromaticAberration = chromaticAberration,
                    )
                }
            },
            highlight = {
                Highlight(
                    width = if (isLight) 1.3.dp else 1.dp,
                    blurRadius = if (isLight) 0.6.dp else 0.5.dp,
                    alpha = if (isLight) 0.98f else 0.92f,
                    style = HighlightStyle.Default,
                )
            },
            shadow = { resolvedShadow },
            onDrawSurface = { drawRect(tint) },
        )
    }
    return this
        .clip(shape)
        .background(if (enabled) tint else fallbackColor, shape)
        .border(
            width = if (isLight) 1.dp else 0.9.dp,
            brush = if (enabled) liquidSpecularBrush(isLight = isLight) else Brush.linearGradient(listOf(fallbackBorderColor, fallbackBorderColor)),
            shape = shape,
        )
}

fun Modifier.frostedGlassChrome(
    enabled: Boolean,
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = Color(0x740B1321),
    fallbackColor: Color = Color.Black.copy(alpha = 0.62f),
    borderWidth: Dp = 1.dp,
    fallbackBorderColor: Color = Color.White.copy(alpha = 0.18f),
    isLight: Boolean = false,
): Modifier = if (enabled) {
    val gradientColors = if (isLight) {
        listOf(
            Color.White.copy(alpha = 0.88f).compositeOver(tint),
            tint,
            Color(0xFFE2EEFF).copy(alpha = 0.55f).compositeOver(tint),
        )
    } else {
        listOf(
            Color.White.copy(alpha = 0.17f).compositeOver(tint),
            tint,
            Color(0xFF04080F).copy(alpha = (tint.alpha + 0.14f).coerceIn(0.45f, 0.95f)).compositeOver(tint),
        )
    }
    this
        .clip(shape)
        .background(Brush.verticalGradient(gradientColors), shape)
        .drawWithContent {
            drawContent()
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (isLight) 0.95f else 0.36f),
                        Color.White.copy(alpha = if (isLight) 0.55f else 0.12f),
                        Color.Transparent,
                    )
                ),
                start = Offset(size.width * 0.08f, 1.dp.toPx()),
                end = Offset(size.width * 0.92f, 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        .border(borderWidth, liquidSpecularBrush(isLight = isLight), shape)
} else {
    this
        .clip(shape)
        .background(fallbackColor, shape)
        .border(borderWidth, fallbackBorderColor, shape)
}

@Composable
fun LiquidGlassPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    forceDark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    val useLight = palette.isLight && !forceDark
    val glassEnabled = palette.isGlass && !forceDark
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .frostedGlassChrome(
                enabled = glassEnabled,
                shape = RoundedCornerShape(18.dp),
                tint = if (useLight) Color(0xE8FFFFFF) else Color(0xEB070D17),
                fallbackColor = if (useLight) Color(0xFFFFFFFF) else Color(0xFF131922),
                fallbackBorderColor = if (useLight) Color(0xFFDCE3EE) else Color.White.copy(alpha = 0.18f),
                isLight = useLight,
            ),
        offset = offset,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (useLight) Color(0xFF0F172A) else Color.White) {
            content()
        }
    }
}

@Composable
fun LiquidGlassPopupMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val baseContent = LocalContentColor.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 168.dp)
            .clip(RoundedCornerShape(10.dp))
            .iosLiquidPress(interactionSource, enabled = enabled, scaleDown = 0.97f, sheenAlpha = 0.10f)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(Modifier.padding(end = 10.dp)) { leadingIcon() }
        }
        Box(Modifier.weight(1f)) {
            ProvideTextStyle(
                TextStyle(
                    color = if (enabled) baseContent else baseContent.copy(alpha = 0.42f),
                    fontSize = 14.sp,
                )
            ) {
                text()
            }
        }
        if (trailingIcon != null) {
            Box(Modifier.padding(start = 10.dp)) { trailingIcon() }
        }
    }
}

@Composable
fun LiquidGlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    immersiveBlur: Boolean = false,
) {
    val palette = LocalAppPalette.current
    val useLight = palette.isLight && !immersiveBlur
    val glassEnabled = palette.isGlass || immersiveBlur && palette.isGlass
    val titleColor = if (useLight) Color(0xFF0F172A) else Color.White
    val bodyColor = if (useLight) Color(0xFF334155) else Color.White.copy(alpha = 0.88f)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (immersiveBlur) ImmersivePlayerDialog()
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val boundedHeight = (maxHeight - 16.dp).coerceAtLeast(220.dp)
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.94f)
                    .heightIn(max = boundedHeight)
                    .frostedGlassChrome(
                        enabled = glassEnabled,
                        shape = RoundedCornerShape(24.dp),
                        tint = if (useLight) Color(0xEAFFFFFF) else Color(0x940B131F),
                        fallbackColor = if (useLight) Color(0xFFFFFFFF) else Color(0xFF141A24),
                        fallbackBorderColor = if (useLight) Color(0xFFDCE3EE) else Color.White.copy(alpha = 0.18f),
                        isLight = useLight,
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides titleColor) {
                    if (title != null) {
                        ProvideTextStyle(TextStyle(color = titleColor, fontSize = 18.sp)) {
                            Box(Modifier.padding(bottom = 10.dp)) { title() }
                        }
                    }
                    if (text != null) {
                        ProvideTextStyle(TextStyle(color = bodyColor, fontSize = 14.sp)) {
                            Box(Modifier.weight(1f, fill = false)) { text() }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        if (dismissButton != null) {
                            dismissButton()
                            Spacer(Modifier.width(6.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}
