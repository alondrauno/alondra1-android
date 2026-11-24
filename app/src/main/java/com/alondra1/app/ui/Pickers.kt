package com.alondra1.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialogFor(initial: LocalDate?, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit, title: String = "Choose a date") {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let(PickerDates::toMillis))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(PickerDates.fromMillis(it)) }; onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state, title = { Text(title, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogFor(initial: LocalTime?, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val start = initial ?: LocalTime.of(9, 0)
    val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = is24Hour())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a time") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
