package com.alondra1.app.features.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alondra1.app.BuildConfig
import com.alondra1.app.data.notifications.Notifications
import com.alondra1.app.data.settings.ThemeMode
import com.alondra1.app.data.settings.TimeFormat
import com.alondra1.app.ui.LocalContainer
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, accountSection: @Composable () -> Unit, onBackground: () -> Unit = {}) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.alondra1.app.data.settings.Settings())
    val lists by container.todo.observeLists().collectAsStateWithLifecycle(initialValue = emptyList())
    var notificationsAllowed by remember { mutableStateOf(Notifications.canPost(context)) }
    var confirmWipe by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf(Triple(0, 0, 0)) }
    LifecycleResumeEffect(Unit) { notificationsAllowed = Notifications.canPost(context); onPauseOrDispose { } }
    LaunchedEffect(confirmWipe) {
        if (confirmWipe) counts = Triple(container.db.maintenance().taskCount(), container.db.maintenance().noteCount(), container.db.maintenance().eventCount())
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Section("General") {
                Choice("Theme", settings.theme.label, ThemeMode.entries.map { it.label to { scope.launch { container.settings.setTheme(it) }; Unit } })
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Background") },
                    supportingContent = {
                        val bg = settings.background
                        Text(if (!bg.enabled) "Off" else com.alondra1.app.data.settings.BackgroundCatalog.name(bg.image))
                    },
                    modifier = Modifier.clickable(onClick = onBackground).testTag("backgroundSettings"),
                )
                HorizontalDivider()
                Choice("First day of week",
                    settings.firstDayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: "Language default",
                    listOf("Language default" to { scope.launch { container.settings.setFirstDayOfWeek(null) }; Unit }) +
                        listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).map { d ->
                            d.getDisplayName(TextStyle.FULL, Locale.getDefault()) to { scope.launch { container.settings.setFirstDayOfWeek(d) }; Unit }
                        })
                HorizontalDivider()
                Choice("Time format", settings.timeFormat.label, TimeFormat.entries.map { it.label to { scope.launch { container.settings.setTimeFormat(it) }; Unit } })
                HorizontalDivider()
                Choice("Default list for new tasks",
                    lists.firstOrNull { it.id == settings.defaultListId }?.name ?: "Tasks",
                    lists.map { l -> l.name to { scope.launch { container.settings.setDefaultList(if (l.isDefault) null else l.id) }; Unit } })
            }

            Section("Notifications") {
                ListItem(
                    headlineContent = { Text("Reminders") },
                    supportingContent = {
                        Text(if (notificationsAllowed) "Allowed. Reminders arrive close to their time; Android may delay them slightly to save battery."
                        else "Off. Tasks and events still work; reminders just won't appear. Turn notifications on in Android settings.")
                    },
                    trailingContent = {
                        if (!notificationsAllowed) TextButton(onClick = {
                            val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            context.startActivity(intent)
                        }) { Text("Open settings") }
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Hide reminder text on the lock screen") },
                    supportingContent = { Text("The lock screen shows only \"Alondra1 reminder\".") },
                    trailingContent = { Switch(checked = settings.privateNotifications, onCheckedChange = { v -> scope.launch { container.settings.setPrivateNotifications(v) } }) },
                )
            }

            Section("Account & Cloud") { accountSection() }

            Section("Privacy & data") {
                ListItem(
                    headlineContent = { Text("Stored on this device") },
                    supportingContent = {
                        Text("Your tasks, notes and events are kept in this app's private storage. They are not uploaded anywhere, and they are not included in Google's cloud backup. When you move to a new phone with Android's device-to-device transfer, they come along.")
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Delete local data", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Removes all tasks, lists, notes, events and settings from this device.") },
                    modifier = Modifier.clickable { confirmWipe = true }.testTag("deleteLocalData"),
                )
            }

            Section("About") {
                ListItem(headlineContent = { Text("Alondra1 for Android") }, supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") })
            }
        }
    }

    if (confirmWipe) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all local data?") },
            text = {
                Column {
                    Text("This deletes ${counts.first} tasks, ${counts.second} notes and ${counts.third} events from this device. It can't be undone. Type DELETE to confirm.")
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.padding(top = 12.dp).testTag("wipeConfirm"))
                }
            },
            confirmButton = {
                TextButton(enabled = typed == "DELETE", onClick = { confirmWipe = false; scope.launch { container.deleteAllLocalData() } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) { Column { content() } }
}

@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(headlineContent = { Text(label) }, supportingContent = { Text(current) }, modifier = Modifier.clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, action) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() }) }
        }
    }
}
