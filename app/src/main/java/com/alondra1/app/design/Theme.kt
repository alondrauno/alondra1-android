package com.alondra1.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alondra1.app.data.settings.ThemeMode

/** Brand tokens (logo + Windows UI-DESIGN.md). Text pairs meet WCAG AA in both themes. */
object Brand {
    val Indigo = Color(0xFF27243E)
    val Periwinkle = Color(0xFF55518F)
    val PeriwinkleDark = Color(0xFF444079)
    val Lavender = Color(0xFFDCD9F2)
    val PalePeriwinkle = Color(0xFFA7A8CE)
    val Peach = Color(0xFFF7D7CB)
    val Canvas = Color(0xFFFBF9FE)

    // Dark app UI, from the Windows dark theme
    val Chrome = Color(0xFF16151F)
    val Panel = Color(0xFF1E1D2A)
    val Row = Color(0xFF252434)
    val Line = Color(0xFF33324A)
    val TextDark = Color(0xFFECEBF5)
    val MutedDark = Color(0xFFA4A3BE)
    val Accent = Color(0xFFBEBFF2)
    val Selected = Color(0xFF302F54)
    val Star = Color(0xFFF5C542)
    val Danger = Color(0xFFE5484D)
    val Overdue = Color(0xFFFF8A80)
    val OverdueLight = Color(0xFFB42318)
    val EventColor = Color(0xFF6E8BFF)
    val TaskColor = Color(0xFF5FD08A)
}

private val DarkColors = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Brand.Indigo,
    primaryContainer = Brand.Selected,
    onPrimaryContainer = Brand.TextDark,
    secondary = Brand.PalePeriwinkle,
    onSecondary = Brand.Indigo,
    secondaryContainer = Brand.Selected,
    onSecondaryContainer = Brand.TextDark,
    tertiary = Brand.Peach,
    background = Brand.Chrome,
    onBackground = Brand.TextDark,
    surface = Brand.Chrome,
    onSurface = Brand.TextDark,
    surfaceVariant = Brand.Row,
    onSurfaceVariant = Brand.MutedDark,
    surfaceContainerLowest = Brand.Chrome,
    surfaceContainerLow = Brand.Panel,
    surfaceContainer = Brand.Panel,
    surfaceContainerHigh = Brand.Row,
    surfaceContainerHighest = Brand.Line,
    outline = Brand.Line,
    outlineVariant = Brand.Line,
    error = Brand.Overdue,
)

private val LightColors = lightColorScheme(
    primary = Brand.Periwinkle,
    onPrimary = Color.White,
    primaryContainer = Brand.Lavender,
    onPrimaryContainer = Brand.Indigo,
    secondary = Brand.Periwinkle,
    secondaryContainer = Color(0xFFEEECF9),
    onSecondaryContainer = Brand.Indigo,
    tertiary = Color(0xFF7A4A38),
    background = Brand.Canvas,
    onBackground = Brand.Indigo,
    surface = Brand.Canvas,
    onSurface = Brand.Indigo,
    surfaceVariant = Color(0xFFF0EEF8),
    onSurfaceVariant = Color(0xFF5B5870),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF6F4FA),
    surfaceContainerHigh = Color(0xFFF0EEF8),
    surfaceContainerHighest = Color(0xFFE7E5F6),
    outline = Color(0xFFE3DEF0),
    outlineVariant = Color(0xFFE3DEF0),
    error = Brand.OverdueLight,
)

@Immutable
data class Extra(val star: Color, val overdue: Color, val event: Color, val task: Color, val isDark: Boolean)

val LocalExtra = staticCompositionLocalOf { Extra(Brand.Star, Brand.Overdue, Brand.EventColor, Brand.TaskColor, true) }

@Composable
fun Alondra1Theme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val extra = if (dark) Extra(Brand.Star, Brand.Overdue, Brand.EventColor, Brand.TaskColor, true)
    else Extra(Color(0xFFB07D00), Brand.OverdueLight, Color(0xFF3657D6), Color(0xFF16803C), false)
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
            ),
            content = content,
        )
    }
}
