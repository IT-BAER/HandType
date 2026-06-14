package com.baer.handtype.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Single source of truth for HandType's brand palette.
 *
 * Before this theme existed the app wrapped everything in a bare `MaterialTheme {}`,
 * so every component reading a color role rendered in the default M3 baseline purple,
 * while brand colors were hardcoded `Color(0x..)` literals per component. This file
 * maps the brand palette onto real M3 color roles so tokens carry the brand and a
 * dark scheme is possible. Migration mapping used across the codebase:
 *   cream paper  0xFFF5EFE4 -> background
 *   card cream   0xFFFFF9F2 / white -> surface
 *   ink          0xFF1A1410 -> onBackground / onSurface
 *   green        0xFF23443A -> primary
 *   brown        0xFF7C4D11 -> secondary / tertiary
 *   red          0xFFAA3333 -> error
 *   tip cream    0xFFEEE8DB -> surfaceVariant
 */
object BrandColors {
    val Green = Color(0xFF23443A)
    val Brown = Color(0xFF7C4D11)
    val Cream = Color(0xFFF5EFE4)
    val CreamHi = Color(0xFFFFF9F2)
    val CreamVariant = Color(0xFFEEE8DB)
    val Ink = Color(0xFF1A1410)
    val Red = Color(0xFFAA3333)
}

private val LightColors = lightColorScheme(
    primary = BrandColors.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE0D6),
    onPrimaryContainer = Color(0xFF0C1F18),
    secondary = BrandColors.Brown,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAD9C2),
    onSecondaryContainer = Color(0xFF2E1C06),
    tertiary = BrandColors.Brown,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAD9C2),
    onTertiaryContainer = Color(0xFF2E1C06),
    background = BrandColors.Cream,
    onBackground = BrandColors.Ink,
    surface = BrandColors.CreamHi,
    onSurface = BrandColors.Ink,
    surfaceVariant = BrandColors.CreamVariant,
    // Darkened from the M3 default so body/secondary text clears WCAG AA on cream.
    onSurfaceVariant = Color(0xFF574E42),
    surfaceContainer = Color(0xFFF1E9DA),
    surfaceContainerHigh = Color(0xFFFBF5EC),
    outline = Color(0xFF867C6C),
    outlineVariant = Color(0xFFD7CCBA),
    error = BrandColors.Red,
    onError = Color.White,
    errorContainer = Color(0xFFF6DAD7),
    onErrorContainer = Color(0xFF410E0A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC9B4),
    onPrimary = Color(0xFF0C2119),
    primaryContainer = Color(0xFF234F40),
    onPrimaryContainer = Color(0xFFB5E7D5),
    secondary = Color(0xFFD9A86A),
    onSecondary = Color(0xFF402A0C),
    secondaryContainer = Color(0xFF5C421F),
    onSecondaryContainer = Color(0xFFF3DCBC),
    tertiary = Color(0xFFD9A86A),
    onTertiary = Color(0xFF402A0C),
    tertiaryContainer = Color(0xFF5C421F),
    onTertiaryContainer = Color(0xFFF3DCBC),
    background = Color(0xFF14110D),
    onBackground = Color(0xFFEDE6DA),
    surface = Color(0xFF1E1A15),
    onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF2A251E),
    onSurfaceVariant = Color(0xFFC9BFAE),
    surfaceContainer = Color(0xFF221E18),
    surfaceContainerHigh = Color(0xFF2C2720),
    outline = Color(0xFF978C7B),
    outlineVariant = Color(0xFF4A4338),
    error = Color(0xFFE9A0A0),
    onError = Color(0xFF3A0A07),
    errorContainer = Color(0xFF6E2723),
    onErrorContainer = Color(0xFFF8D7D4),
)

private val HandTypeShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * Wraps content in the HandType brand theme.
 *
 * @param dynamicColor opt-in Material You. Default false to protect the brand identity;
 *   when true on Android 12+ the wallpaper palette is used and the brand scheme is the
 *   fallback so identity is never lost on older devices.
 */
@Composable
fun HandTypeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val lightBars = colorScheme.background.luminance() > 0.5f
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = HandTypeShapes,
        content = content,
    )
}
