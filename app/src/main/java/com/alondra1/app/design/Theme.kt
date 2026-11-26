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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Dark app UI: the website direction (Android UI spec §3.1). Text on every surface below is at least 4.5:1
    // (muted text #A5A2BB on the raised surface #262436 is 6.0:1); the accent is a selection cue next to a shape or
    // weight change, never the only one.
    val Chrome = Color(0xFF17151F) // bg.app
    val Panel = Color(0xFF201E2B) // bg.surface: task cards, panels
    val Row = Color(0xFF262436) // bg.surfaceRaised: composer, raised blocks
    val Line = Color(0xFF302E40) // border.subtle
    val TextDark = Color(0xFFE8E6F0) // text.primary
    val MutedDark = Color(0xFFA5A2BB) // text.secondary
    val Accent = Color(0xFFB9B6F2) // accent.primary
    val AccentStrong = Color(0xFF7589FF) // accent.strong: small progress accents
    val Selected = Color(0xFF302F54)
    val Star = Color(0xFFFFD34F) // status.important
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
data class Extra(val star: Color, val overdue: Color, val event: Color, val task: Color, val isDark: Boolean, val progress: Color = Brand.AccentStrong)

val LocalExtra = staticCompositionLocalOf { Extra(Brand.Star, Brand.Overdue, Brand.EventColor, Brand.TaskColor, true) }

/** Spacing and shape tokens (4 dp grid, Android UI spec §3.2). Use these instead of ad-hoc values on new screens. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val screen = 20.dp // horizontal screen padding
    val card = 16.dp // inner card padding
    val cardGap = 10.dp // between task cards
    val touch = 48.dp // smallest interactive target
}

object Radius {
    val card = RoundedCornerShape(20.dp)
    val composer = RoundedCornerShape(20.dp)
    val control = RoundedCornerShape(14.dp)
}

/** Type scale in sp so it follows the user's font size (title 28 bold, task title 16 medium, metadata 13). */
val AppTypography = androidx.compose.material3.Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun Alondra1Theme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val extra = if (dark) Extra(Brand.Star, Brand.Overdue, Brand.EventColor, Brand.TaskColor, true)
    else Extra(Color(0xFFB07D00), Brand.OverdueLight, Color(0xFF3657D6), Color(0xFF16803C), false, progress = Color(0xFF3F55D6))
    // Status and navigation bar icons follow the app theme, not the system one (spec §3.3: readable over the app).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            (view.context as? android.app.Activity)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
            ),
            content = content,
        )
    }
}
