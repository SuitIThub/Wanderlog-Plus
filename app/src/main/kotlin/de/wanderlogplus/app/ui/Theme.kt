package de.wanderlogplus.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm orange Material 3 scheme (tones derived from seed #A5520F); surfaces stay warm instead of the purple baseline.
private val LightColors = lightColorScheme(
    primary = Color(0xFFA5520F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC0),
    onPrimaryContainer = Color(0xFF341100),
    inversePrimary = Color(0xFFFFB77C),
    secondary = Color(0xFF755846),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC7),
    onSecondaryContainer = Color(0xFF2B1709),
    tertiary = Color(0xFF5F6135),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E6AF),
    onTertiaryContainer = Color(0xFF1C1D00),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF4DED3),
    onSurfaceVariant = Color(0xFF52443C),
    surfaceTint = Color(0xFFA5520F),
    inverseSurface = Color(0xFF382E29),
    inverseOnSurface = Color(0xFFFFEDE4),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD7C3B8),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceDim = Color(0xFFE8D7CE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF1E9),
    surfaceContainer = Color(0xFFFCEBE2),
    surfaceContainerHigh = Color(0xFFF6E5DC),
    surfaceContainerHighest = Color(0xFFF0DFD7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF552000),
    primaryContainer = Color(0xFF7F3D00),
    onPrimaryContainer = Color(0xFFFFDCC0),
    inversePrimary = Color(0xFFA5520F),
    secondary = Color(0xFFE5BFA8),
    onSecondary = Color(0xFF422B1B),
    secondaryContainer = Color(0xFF5B4130),
    onSecondaryContainer = Color(0xFFFFDCC7),
    tertiary = Color(0xFFC9CA94),
    onTertiary = Color(0xFF31320B),
    tertiaryContainer = Color(0xFF474920),
    onTertiaryContainer = Color(0xFFE5E6AF),
    background = Color(0xFF1A120C),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF1A120C),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C3B8),
    surfaceTint = Color(0xFFFFB77C),
    inverseSurface = Color(0xFFF0DFD7),
    inverseOnSurface = Color(0xFF382E29),
    outline = Color(0xFF9F8D83),
    outlineVariant = Color(0xFF52443C),
    surfaceBright = Color(0xFF42372F),
    surfaceDim = Color(0xFF1A120C),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceContainerLow = Color(0xFF221A14),
    surfaceContainer = Color(0xFF271E18),
    surfaceContainerHigh = Color(0xFF322822),
    surfaceContainerHighest = Color(0xFF3D332C),
)

/** Serif title on top of hero images. */
val HeroTitle: TextStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 33.sp,
)

/** Whether [WanderlogPlusTheme] renders its dark scheme; read by components with hand-picked light/dark colors (badges). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * App theme. [dynamicColor] is off by default: on Android 12+ the wallpaper palette would otherwise replace the
 * warm scheme the design is built around.
 */
@Composable
fun WanderlogPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
