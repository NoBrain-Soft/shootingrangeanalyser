package com.nobrainsoft.rangeanalyser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How the app should look right now.
 *
 * Range mode is not a cosmetic dark theme. Outdoors in sunlight a phone screen loses most of its
 * apparent contrast, and the person holding it may be wearing gloves and looking at it for two
 * seconds between strings. So it drops to pure black and white, enlarges type, and grows every
 * touch target - deliberately at the cost of looking a bit blunt indoors.
 */
enum class AppTheme { SYSTEM, LIGHT, DARK, RANGE }

val LocalRangeMode = staticCompositionLocalOf { false }

/** Spacing and sizing, gathered so the glove-friendly minimums are stated once. */
object Dimens {
    /**
     * Minimum size for anything the user has to hit.
     *
     * Material's 48 dp assumes a bare fingertip indoors. Cold hands and shooting gloves need more,
     * and there is nothing on these screens dense enough to need the space back.
     */
    val touchTarget = 56.dp
    val touchTargetRange = 64.dp

    val gutter = 16.dp
    val gutterWide = 24.dp
    val itemSpacing = 12.dp
    val sectionSpacing = 20.dp
    val cardCorner = 16.dp
}

private val PrecisionOrange = Color(0xFFFF6B35)
private val PaperWhite = Color(0xFFF7F5F2)
private val Ink = Color(0xFF12161B)
private val SteelGrey = Color(0xFF4A5568)
private val HitGreen = Color(0xFF2E9E5B)
private val WarnAmber = Color(0xFFE8A33D)
private val MissRed = Color(0xFFD64545)

private val LightScheme = lightColorScheme(
    primary = PrecisionOrange,
    onPrimary = Color.White,
    secondary = SteelGrey,
    onSecondary = Color.White,
    background = PaperWhite,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8E4DF),
    onSurfaceVariant = Color(0xFF3A424D),
    error = MissRed,
)

private val DarkScheme = darkColorScheme(
    primary = PrecisionOrange,
    onPrimary = Color.Black,
    secondary = Color(0xFF9AA5B1),
    onSecondary = Color.Black,
    background = Ink,
    onBackground = PaperWhite,
    surface = Color(0xFF1B2027),
    onSurface = PaperWhite,
    surfaceVariant = Color(0xFF2A313A),
    onSurfaceVariant = Color(0xFFC3CAD3),
    error = Color(0xFFFF6B6B),
)

/**
 * Maximum contrast, for reading in direct sun. Pure black and white rather than the softened
 * near-blacks a dark theme normally uses, because the screen is fighting daylight.
 */
private val RangeScheme = darkColorScheme(
    primary = Color(0xFFFFB000),
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF5252),
    outline = Color.White,
)

/** Semantic colours that are not part of the Material scheme but are used across screens. */
object ScoreColors {
    val hit = HitGreen
    val warning = WarnAmber
    val miss = MissRed
    val uncertain = WarnAmber
}

private fun typographyFor(rangeMode: Boolean): Typography {
    val scale = if (rangeMode) 1.15f else 1f
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.scaled(scale),
        headlineMedium = base.headlineMedium.scaled(scale),
        headlineSmall = base.headlineSmall.scaled(scale),
        titleLarge = base.titleLarge.scaled(scale),
        titleMedium = base.titleMedium.scaled(scale),
        bodyLarge = base.bodyLarge.scaled(scale),
        bodyMedium = base.bodyMedium.scaled(scale),
        // Never below this: small grey print is unreadable in the conditions this app is used in.
        labelMedium = base.labelMedium.copy(
            fontSize = (base.labelMedium.fontSize.value * scale).coerceAtLeast(13f).sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(fontSize = (fontSize.value * factor).sp)

@Composable
fun RangeAnalyserTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    val rangeMode = theme == AppTheme.RANGE
    val scheme = when (theme) {
        AppTheme.LIGHT -> LightScheme
        AppTheme.DARK -> DarkScheme
        AppTheme.RANGE -> RangeScheme
        AppTheme.SYSTEM -> if (isSystemInDarkTheme()) DarkScheme else LightScheme
    }

    CompositionLocalProvider(LocalRangeMode provides rangeMode) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(rangeMode),
            content = content,
        )
    }
}
