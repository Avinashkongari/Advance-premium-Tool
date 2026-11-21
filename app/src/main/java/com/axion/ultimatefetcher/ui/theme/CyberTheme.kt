package com.axion.ultimatefetcher.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.axion.ultimatefetcher.data.AppThemeMode
import com.example.R

val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

@Immutable
data class CyberExtendedColors(
    val isDark: Boolean,
    val cyan: Color,
    val violet: Color,
    val success: Color,
    val warning: Color,
    val cardBorder: Color,
    val glassSurface: Color
)

private val DarkCyberColors = CyberExtendedColors(
    isDark = true,
    cyan = Color(0xFF00D4FF),
    violet = Color(0xFF8B5CF6),
    success = Color(0xFF00D68F),
    warning = Color(0xFFFFB547),
    cardBorder = Color(0xFF23304A),
    glassSurface = Color(0xE6111827)
)

private val LightCyberColors = CyberExtendedColors(
    isDark = false,
    cyan = Color(0xFF007EA7),
    violet = Color(0xFF6D28D9),
    success = Color(0xFF059669),
    warning = Color(0xFFD97706),
    cardBorder = Color(0xFFD6DEEC),
    glassSurface = Color(0xF5FFFFFF)
)

val LocalCyberColors = staticCompositionLocalOf { DarkCyberColors }

private val CyberDarkColorScheme = darkColorScheme(
    primary = Color(0xFF5865F2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF252E6E),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFF00D4FF),
    onSecondary = Color(0xFF002029),
    secondaryContainer = Color(0xFF003847),
    onSecondaryContainer = Color(0xFFB3F0FF),
    tertiary = Color(0xFF8B5CF6),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFF080B12),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2436),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2C3A52),
    error = Color(0xFFFF5263),
    onError = Color(0xFFFFFFFF)
)

private val CyberLightColorScheme = lightColorScheme(
    primary = Color(0xFF4752E6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF151C63),
    secondary = Color(0xFF007EA7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F1FF),
    onSecondaryContainer = Color(0xFF002938),
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE7EDF7),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

private val CyberTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

private val CyberShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun UltimateFetcherTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) CyberDarkColorScheme else CyberLightColorScheme
    val cyberColors = if (useDark) DarkCyberColors else LightCyberColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !useDark
                controller.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    CompositionLocalProvider(LocalCyberColors provides cyberColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CyberTypography,
            shapes = CyberShapes,
            content = content
        )
    }
}
