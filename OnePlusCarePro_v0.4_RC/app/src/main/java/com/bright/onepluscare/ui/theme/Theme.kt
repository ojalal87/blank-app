package com.bright.onepluscare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Restrained accent: used for the primary action and selection, never for status. */
val AccentRed = Color(0xFFEB0029)

/** Status palette. Always paired with an icon and a text label (never color alone). */
@Immutable
data class StatusColors(
    val healthy: Color = Color(0xFF3DDC97),
    val attention: Color = Color(0xFFFFB547),
    val critical: Color = Color(0xFFFF6B6B),
    val info: Color = Color(0xFF8AB4F8),
    val neutral: Color = Color(0xFFA8A8AE),
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

private val AmoledScheme = darkColorScheme(
    primary = AccentRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0610),
    onPrimaryContainer = Color(0xFFFFD9DC),
    secondary = Color(0xFFD0D0D6),
    onSecondary = Color(0xFF111113),
    secondaryContainer = Color(0xFF26262A),
    onSecondaryContainer = Color(0xFFEDEDF0),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F3),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFFB4B4BA),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF19191C),
    surfaceContainerHighest = Color(0xFF222226),
    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF2A2A2F),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
    errorContainer = Color(0xFF3B0E0E),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFEDEDF0),
    inverseOnSurface = Color(0xFF111113),
    scrim = Color.Black,
)

private val CareTypography = Typography().let { t ->
    t.copy(
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Large tabular numerals for metrics. Scales with the user's font size like every other style. */
val MetricTextStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

@Composable
fun CareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmoledScheme, typography = CareTypography) {
        androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides StatusColors(), content = content)
    }
}
