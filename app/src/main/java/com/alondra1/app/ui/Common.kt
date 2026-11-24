package com.alondra1.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alondra1.app.AppContainer
import com.alondra1.app.data.settings.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("SnackbarHostState not provided") }
val LocalTimeFormat = staticCompositionLocalOf { TimeFormat.SYSTEM }

/** Shows "message · Undo" and runs [onUndo] when the user taps Undo. */
suspend fun SnackbarHostState.showUndo(message: String, onUndo: suspend () -> Unit) {
    currentSnackbarData?.dismiss()
    val result = showSnackbar(message = message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

object Format {
    // Built per use so a locale change while the app runs is respected.
    private val monthDay get() = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    private val monthDayYear get() = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    val longDate: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    fun relativeDate(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) date.format(monthDay) else date.format(monthDayYear)
    }

    fun is24(context: android.content.Context, format: TimeFormat) = when (format) {
        TimeFormat.H12 -> false
        TimeFormat.H24 -> true
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    }

    fun time(time: LocalTime, is24: Boolean): String =
        time.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()))

    fun dateTime(value: LocalDateTime, today: LocalDate, is24: Boolean) =
        relativeDate(value.toLocalDate(), today) + " " + time(value.toLocalTime(), is24)

    fun monthTitle(year: Int, month: java.time.Month): String =
        month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) } + " " + year
}

@Composable
fun is24Hour(): Boolean = Format.is24(LocalContext.current, LocalTimeFormat.current)

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** UTC-millis bridge for Material 3 date pickers, which work on UTC midnight. */
object PickerDates {
    fun toMillis(date: LocalDate): Long = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    fun fromMillis(millis: Long): LocalDate = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
}
