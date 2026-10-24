package com.trinetrax.attendance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* TrinetraX brand: navy blue, white, restrained blue accents. */
val Navy = Color(0xFF0B1F3A)
val NavySurface = Color(0xFF12294A)
val BlueAccent = Color(0xFF4C8DFF)
val BlueSoft = Color(0xFFE8F0FF)
val SuccessGreen = Color(0xFF1E8E5A)
val WarningAmber = Color(0xFFB26A00)
val DangerRed = Color(0xFFC0392B)
val SurfaceLight = Color(0xFFF6F8FB)
val BorderLight = Color(0xFFE1E6EE)
val OnNavy = Color(0xFFF4F7FF)
val MutedText = Color(0xFF5B6B84)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = OnNavy,
    primaryContainer = BlueSoft,
    onPrimaryContainer = Navy,
    secondary = BlueAccent,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = Navy,
    surface = Color.White,
    onSurface = Navy,
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = MutedText,
    outline = BorderLight,
    error = DangerRed,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = BlueAccent,
    onPrimary = Color.White,
    primaryContainer = NavySurface,
    onPrimaryContainer = OnNavy,
    secondary = BlueAccent,
    onSecondary = Color.White,
    background = Color(0xFF0A1626),
    onBackground = OnNavy,
    surface = NavySurface,
    onSurface = OnNavy,
    surfaceVariant = Color(0xFF16304F),
    onSurfaceVariant = Color(0xFFB9C6DA),
    outline = Color(0xFF24405F),
    error = Color(0xFFE57373),
    onError = Color(0xFF2A0B0B)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

@Composable
fun TrinetraXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
