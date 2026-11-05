package com.emotionalleaves.cartwise.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Star = Color(0xFFE0A526)

private val Light = lightColorScheme(
    primary = Color(0xFF0F6E64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEAE4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9ECE8),
    onSecondaryContainer = Color(0xFF06201D),
    tertiary = Color(0xFFA9691F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE3C4),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFEAE6DE),
    onSurfaceVariant = Color(0xFF4F4D47),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1EEE8),
    surfaceContainerHigh = Color(0xFFECE9E2),
    surfaceContainerHighest = Color(0xFFE6E2DA),
    outline = Color(0xFFC9C4B9),
    outlineVariant = Color(0xFFE0DBD1),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DD3C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0D4F46),
    onPrimaryContainer = Color(0xFFBFF0E6),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF26403C),
    onSecondaryContainer = Color(0xFFCDE8E2),
    tertiary = Color(0xFFF0BD7A),
    onTertiary = Color(0xFF432A00),
    tertiaryContainer = Color(0xFF5F3F0B),
    onTertiaryContainer = Color(0xFFFFDDB4),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE3E2DD),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE3E2DD),
    surfaceVariant = Color(0xFF262B29),
    onSurfaceVariant = Color(0xFFBFC2BD),
    surfaceContainerLowest = Color(0xFF0C0F0E),
    surfaceContainerLow = Color(0xFF181C1B),
    surfaceContainer = Color(0xFF1C211F),
    surfaceContainerHigh = Color(0xFF242A28),
    surfaceContainerHighest = Color(0xFF2E3432),
    outline = Color(0xFF59605D),
    outlineVariant = Color(0xFF3A403E),
    error = Color(0xFFF2B8B5),
)

private val Type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    )
}

private val CartShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun isDark(pref: String): Boolean = when (pref) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun CartwiseTheme(pref: String, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(pref)) Dark else Light,
        typography = Type,
        shapes = CartShapes,
        content = content,
    )
}
