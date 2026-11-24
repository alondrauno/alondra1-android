package com.alondra1.app.features.todo

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.alondra1.app.AppContainer
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.local.TaskListEntity
import com.alondra1.app.data.notifications.Notifications
import com.alondra1.app.data.repository.InvalidTitleException
import com.alondra1.app.design.LocalExtra
import com.alondra1.app.domain.TaskRules
import com.alondra1.app.ui.DatePickerDialogFor
import com.alondra1.app.ui.EmptyState
import com.alondra1.app.ui.Format
import com.alondra1.app.ui.LocalSnackbar
import com.alondra1.app.ui.TimePickerDialogFor
import com.alondra1.app.ui.is24Hour
import com.alondra1.app.ui.showUndo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class TaskDetailState(val loading: Boolean = true, val task: TaskEntity? = null, val lists: List<TaskListEntity> = emptyList())

class TaskDetailViewModel(private val container: AppContainer, val taskId: String) : ViewModel() {
    private val todo = container.todo
    val state: StateFlow<TaskDetailState> = combine(todo.observeTask(taskId), todo.observeLists()) { task, lists ->
        TaskDetailState(loading = false, task = task?.takeIf { it.deletedUtc == null }, lists = lists)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailState())

    var saveError by mutableStateOf<String?>(null)
        private set
    private var textJob: Job? = null

    /** Field edits commit immediately (text fields after a short pause). */
    fun edit(debounce: Boolean = false, change: (TaskEntity) -> TaskEntity) {
        if (state.value.task == null) return
        textJob?.cancel()
        val job = viewModelScope.launch {
            if (debounce) delay(400)
            val latest = todo.getTask(taskId) ?: return@launch
            try {
                todo.updateTask(change(latest))
                saveError = null
            } catch (e: InvalidTitleException) {
                saveError = e.message
            } catch (e: Exception) {
                saveError = "Couldn't save. Your last change was not stored."
            }
        }
        if (debounce) textJob = job
    }

    fun today(): LocalDate = container.time.today()
    fun zone(): ZoneId = container.time.zone()
    suspend fun delete(): TaskEntity? = todo.deleteTask(taskId)
    suspend fun restore(snapshot: TaskEntity) = todo.restore(snapshot)
    fun flush() { textJob?.let { if (it.isActive) viewModelScope.launch { it.join() } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(viewModel: TaskDetailViewModel, onClose: () -> Unit, showBack: Boolean = true) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val task = state.task
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Task") },
            navigationIcon = {
                IconButton(onClick = { viewModel.flush(); onClose() }) {
                    Icon(if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, contentDescription = if (showBack) "Back" else "Close")
                }
            },
            actions = {
                if (task != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete task") }
            },
        )
        if (task == null) {
            if (!state.loading) EmptyState(Icons.Outlined.Description, "Task not found", "It may have been deleted.")
            return@Column
        }
        TaskDetailBody(viewModel, task, state.lists)
    }

    if (confirmDelete && task != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete task?") },
            text = { Text("\"${task.title}\" will be deleted. You can undo right after.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.Main) {
                        val snapshot = viewModel.delete()
                        onClose()
                        if (snapshot != null) snackbar.showUndo("Task deleted") { viewModel.restore(snapshot) }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TaskDetailBody(viewModel: TaskDetailViewModel, task: TaskEntity, lists: List<TaskListEntity>) {
    val context = LocalContext.current
    val is24 = is24Hour()
    val today = viewModel.today()
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var notes by rememberSaveable(task.id) { mutableStateOf(task.notes) }
    var pickDue by remember { mutableStateOf(false) }
    var pickDueTime by remember { mutableStateOf(false) }
    var pickReminderDate by remember { mutableStateOf(false) }
    var reminderDate by remember { mutableStateOf<LocalDate?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) pickReminderDate = true
    }
    LaunchedEffect(task.id) { if (title != task.title && title.isBlank()) title = task.title }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(TaskRules.TITLE_MAX); viewModel.edit(debounce = true) { t -> t.copy(title = title) } },
                label = { Text("Title") },
                isError = title.isBlank(),
                supportingText = { if (title.isBlank()) Text("A task needs a title") else viewModel.saveError?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).testTag("taskTitle"),
            )
            IconButton(onClick = { viewModel.edit { it.copy(important = !it.important) } }) {
                Icon(if (task.important) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (task.important) "Remove importance" else "Mark as important",
                    tint = if (task.important) LocalExtra.current.star else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                ListItem(
                    headlineContent = { Text("Completed") },
                    trailingContent = {
                        Switch(checked = task.completed, onCheckedChange = { done ->
                            viewModel.edit { it.copy(completed = done, completedUtc = if (done) java.time.Instant.now() else null) }
                        })
                    },
                )
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Filled.WbSunny, contentDescription = null) },
                    headlineContent = { Text(if (task.myDayDate == today) "In My Day" else "Add to My Day") },
                    trailingContent = {
                        Switch(checked = task.myDayDate == today, onCheckedChange = { add -> viewModel.edit { it.copy(myDayDate = if (add) today else null) } })
                    },
                )
                HorizontalDivider()
                Box {
                    ListItem(
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        headlineContent = { Text("List") },
                        supportingContent = { Text(lists.firstOrNull { it.id == task.listId }?.name ?: "Tasks") },
                        modifier = Modifier.heightIn(min = 56.dp).clickableRow { listMenu = true },
                    )
                    DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                        lists.forEach { list ->
                            DropdownMenuItem(text = { Text(list.name) }, onClick = { listMenu = false; viewModel.edit { it.copy(listId = list.id) } })
                        }
                    }
                }
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                    headlineContent = { Text(task.dueDate?.let { "Due " + Format.relativeDate(it, today) } ?: "Add due date") },
                    trailingContent = {
                        if (task.dueDate != null) IconButton(onClick = { viewModel.edit { it.copy(dueDate = null, dueTime = null) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove due date")
                        }
                    },
                    modifier = Modifier.clickableRow { pickDue = true },
                )
                if (task.dueDate != null) {
                    HorizontalDivider()
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                        headlineContent = { Text(task.dueTime?.let { "At " + Format.time(it, is24) } ?: "Add a time") },
                        trailingContent = {
                            if (task.dueTime != null) IconButton(onClick = { viewModel.edit { it.copy(dueTime = null) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove time")
                            }
                        },
                        modifier = Modifier.clickableRow { pickDueTime = true },
                    )
                }
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    headlineContent = { Text(task.reminderAt?.let { "Remind me " + Format.dateTime(it, today, is24) } ?: "Remind me") },
                    supportingContent = {
                        when {
                            permissionDenied -> Text("Notifications are off for Alondra1, so the reminder can't be shown. You can allow them in Android settings; the task is saved either way.")
                            task.reminderAt != null && !Notifications.canPost(context) -> Text("Notifications are off for Alondra1. The reminder is saved but won't appear.")
                        }
                    },
                    trailingContent = {
                        if (task.reminderAt != null) IconButton(onClick = { viewModel.edit { it.copy(reminderAt = null) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove reminder")
                        }
                    },
                    modifier = Modifier.clickableRow {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(context)) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            pickReminderDate = true
                        }
                    },
                )
            }
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; viewModel.edit(debounce = true) { t -> t.copy(notes = notes) } },
            label = { Text("Notes") },
            minLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().testTag("taskNotes"),
        )

        val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        Text(
            "Created " + task.createdUtc.atZone(viewModel.zone()).format(fmt) + "\nUpdated " + task.modifiedUtc.atZone(viewModel.zone()).format(fmt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Spacer(Modifier.width(1.dp).heightIn(min = 24.dp))
    }

    if (pickDue) DatePickerDialogFor(task.dueDate ?: today, onDismiss = { pickDue = false }, onPicked = { d -> viewModel.edit { it.copy(dueDate = d) } }, title = "Due date")
    if (pickDueTime) TimePickerDialogFor(task.dueTime, onDismiss = { pickDueTime = false }, onPicked = { t -> viewModel.edit { it.copy(dueTime = t) } })
    if (pickReminderDate) {
        DatePickerDialogFor(task.reminderAt?.toLocalDate() ?: task.dueDate ?: today, onDismiss = { pickReminderDate = false },
            onPicked = { reminderDate = it }, title = "Reminder date")
    }
    reminderDate?.let { date ->
        TimePickerDialogFor(task.reminderAt?.toLocalTime() ?: task.dueTime ?: LocalTime.of(9, 0), onDismiss = { reminderDate = null },
            onPicked = { t -> viewModel.edit { it.copy(reminderAt = LocalDateTime.of(date, t)) } })
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
