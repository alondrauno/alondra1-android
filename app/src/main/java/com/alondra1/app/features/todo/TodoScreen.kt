package com.alondra1.app.features.todo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.design.LocalExtra
import com.alondra1.app.domain.SmartView
import com.alondra1.app.domain.StatusFilter
import com.alondra1.app.domain.TaskRules
import com.alondra1.app.domain.TaskSort
import com.alondra1.app.ui.DatePickerDialogFor
import com.alondra1.app.ui.EmptyState
import com.alondra1.app.ui.Format
import com.alondra1.app.ui.LocalSnackbar
import com.alondra1.app.ui.is24Hour
import com.alondra1.app.ui.showUndo
import kotlinx.coroutines.launch
import java.time.LocalDate

val SmartView.icon: ImageVector
    get() = when (this) {
        SmartView.MY_DAY -> Icons.Filled.WbSunny
        SmartView.IMPORTANT -> Icons.Filled.Star
        SmartView.PLANNED -> Icons.Filled.CalendarMonth
        SmartView.UPCOMING -> Icons.Filled.Event
        SmartView.OVERDUE -> Icons.Filled.WarningAmber
        SmartView.ALL -> Icons.AutoMirrored.Filled.List
        SmartView.COMPLETED -> Icons.Filled.CheckCircle
        SmartView.UNSCHEDULED -> Icons.Filled.EventBusy
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    viewModel: TodoViewModel,
    onOpenTask: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    selectedTaskId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var showDestinations by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deletingList by remember { mutableStateOf(false) }
    var choosingGroup by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column(
                    Modifier
                        .clickable { showDestinations = true }
                        .semantics { contentDescription = "${state.title}. Choose a view or list" }
                        .testTag("viewSelector"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    if (state.selection == TodoSelection.View(SmartView.MY_DAY)) {
                        Text(state.today.format(Format.longDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                Box {
                    IconButton(onClick = { showSort = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        TaskSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label + if (sort == state.sort) "  ✓" else "") },
                                onClick = { viewModel.setSort(sort); showSort = false },
                            )
                        }
                    }
                }
                val selection = state.selection
                if (selection is TodoSelection.List && state.lists.firstOrNull { it.id == selection.id }?.isDefault == false) {
                    Box {
                        IconButton(onClick = { listMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "List options") }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            DropdownMenuItem(text = { Text("Rename list") }, onClick = { listMenu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Move to group…") }, onClick = { listMenu = false; choosingGroup = true })
                            DropdownMenuItem(text = { Text("Move up") }, onClick = { listMenu = false; viewModel.moveList(selection.id, -1) })
                            DropdownMenuItem(text = { Text("Move down") }, onClick = { listMenu = false; viewModel.moveList(selection.id, 1) })
                            DropdownMenuItem(text = { Text("Delete list") }, onClick = { listMenu = false; deletingList = true })
                        }
                    }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            },
        )

        if (state.showsStatusFilter) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { filter ->
                    FilterChip(selected = state.status == filter, onClick = { viewModel.setStatus(filter) }, label = { Text(filter.label) })
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.error != null -> EmptyState(Icons.Filled.WarningAmber, "Something went wrong", state.error!!)
                !state.loading && state.tasks.isEmpty() -> TodoEmpty(state)
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().testTag("taskList"),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            listName = state.listNames[task.listId],
                            showList = state.selection is TodoSelection.View,
                            today = state.today,
                            overdue = TaskRules.isOverdue(task, state.now),
                            selected = task.id == selectedTaskId,
                            onOpen = { onOpenTask(task.id) },
                            onToggleComplete = {
                                scope.launch {
                                    val before = viewModel.setCompleted(task, !task.completed)
                                    if (before != null) {
                                        snackbar.showUndo(if (!task.completed) "Completed" else "Marked as not completed") { viewModel.restore(before) }
                                    }
                                }
                            },
                            onToggleImportant = { viewModel.setImportant(task, !task.important) },
                        )
                    }
                }
            }
        }

        QuickAddBar(
            hint = when (val s = state.selection) {
                TodoSelection.View(SmartView.MY_DAY) -> "Add a task to My Day"
                TodoSelection.View(SmartView.IMPORTANT) -> "Add an important task"
                is TodoSelection.List -> "Add a task to ${state.title}"
                else -> "Add a task"
            },
            onAdd = { title, due, important -> viewModel.quickAdd(title, due, important) },
        )
    }

    if (showDestinations) {
        DestinationsSheet(
            state = state,
            onDismiss = { showDestinations = false },
            onSelect = { viewModel.select(it); showDestinations = false },
            onCreateList = { name -> scope.launch { viewModel.createList(name)?.let { viewModel.select(TodoSelection.List(it.id)) }; showDestinations = false } },
            onCreateGroup = viewModel::createGroup,
            onRenameGroup = viewModel::renameGroup,
            onDeleteGroup = viewModel::deleteGroup,
            onToggleGroup = viewModel::toggleGroup,
        )
    }
    val selection = state.selection
    if (renaming && selection is TodoSelection.List) {
        NameDialog(title = "Rename list", initial = state.title, confirm = "Rename", onDismiss = { renaming = false }) {
            viewModel.renameList(selection.id, it); renaming = false
        }
    }
    if (choosingGroup && selection is TodoSelection.List) {
        val current = state.lists.firstOrNull { it.id == selection.id }?.groupId
        AlertDialog(
            onDismissRequest = { choosingGroup = false },
            title = { Text("Move \"${state.title}\" to") },
            text = {
                Column {
                    TextButton(onClick = { viewModel.setListGroup(selection.id, null); choosingGroup = false }) { Text((if (current == null) "✓ " else "") + "No group") }
                    state.groups.forEach { g ->
                        TextButton(onClick = { viewModel.setListGroup(selection.id, g.id); choosingGroup = false }) { Text((if (current == g.id) "✓ " else "") + g.name) }
                    }
                    if (state.groups.isEmpty()) Text("Create a group with New group in the lists sheet.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { choosingGroup = false }) { Text("Close") } },
        )
    }
    if (deletingList && selection is TodoSelection.List) {
        AlertDialog(
            onDismissRequest = { deletingList = false },
            title = { Text("Delete \"${state.title}\"?") },
            text = { Text("Its tasks can move to Tasks (recommended), or be deleted with the list.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteList(selection.id, moveTasks = true); deletingList = false }) { Text("Move tasks and delete") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.deleteList(selection.id, moveTasks = false); deletingList = false }) {
                        Text("Delete tasks too", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { deletingList = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun TodoEmpty(state: TodoUiState) {
    val (title, body) = when (state.selection) {
        TodoSelection.View(SmartView.MY_DAY) -> "Nothing planned for today" to "Add a task below, or add tasks to My Day from any list."
        TodoSelection.View(SmartView.IMPORTANT) -> "No important tasks" to "Star a task to see it here."
        TodoSelection.View(SmartView.PLANNED), TodoSelection.View(SmartView.UPCOMING) -> "No dated tasks" to "Give a task a due date to plan it."
        TodoSelection.View(SmartView.OVERDUE) -> "Nothing overdue" to "You are on track."
        TodoSelection.View(SmartView.COMPLETED) -> "No completed tasks yet" to "Completed tasks appear here."
        TodoSelection.View(SmartView.UNSCHEDULED) -> "No unscheduled tasks" to "Tasks without a due date appear here."
        else -> "No tasks here" to "Add your first task below."
    }
    EmptyState(Icons.Outlined.TaskAlt, title, body)
}

@Composable
fun TaskRow(
    task: TaskEntity,
    listName: String?,
    showList: Boolean,
    today: LocalDate,
    overdue: Boolean,
    onOpen: () -> Unit,
    onToggleComplete: () -> Unit,
    onToggleImportant: () -> Unit,
    selected: Boolean = false,
) {
    val extra = LocalExtra.current
    val is24 = is24Hour()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Open task", onClick = onOpen).testTag("task:${task.title}"),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleComplete) {
                Icon(
                    if (task.completed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (task.completed) "Mark ${task.title} as not completed" else "Complete ${task.title}",
                    tint = if (task.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    if (task.myDayDate == today && !task.completed) add("My Day")
                    if (showList && listName != null) add(listName)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    task.dueDate?.let { due ->
                        if (meta.isNotEmpty()) Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(14.dp),
                            tint = if (overdue) extra.overdue else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(3.dp))
                        Text(
                            Format.relativeDate(due, today) + (task.dueTime?.let { " " + Format.time(it, is24) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) extra.overdue else MaterialTheme.colorScheme.primary,
                            fontWeight = if (overdue) FontWeight.SemiBold else null,
                        )
                    }
                    if (task.remindersJson != "[]" && !task.completed) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Notifications, contentDescription = "Has a reminder", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconToggleButton(checked = task.important, onCheckedChange = { onToggleImportant() }) {
                Icon(
                    if (task.important) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (task.important) "Remove importance" else "Mark as important",
                    tint = if (task.important) extra.star else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun QuickAddBar(hint: String, onAdd: suspend (String, LocalDate?, Boolean) -> String?) {
    var text by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var important by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    fun submit() {
        if (text.isBlank()) return
        scope.launch {
            error = onAdd(text, due, important)
            if (error == null) { text = ""; due = null; important = false }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp, modifier = Modifier.imePadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it.take(TaskRules.TITLE_MAX); error = null },
                    placeholder = { Text(hint) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).testTag("quickAdd"),
                )
                IconButton(onClick = { picking = true }) { Icon(Icons.Filled.CalendarMonth, contentDescription = "Set a due date") }
                IconToggleButton(checked = important, onCheckedChange = { important = it }) {
                    Icon(if (important) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Important",
                        tint = if (important) LocalExtra.current.star else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { submit() }, enabled = text.isNotBlank(), modifier = Modifier.testTag("quickAddButton")) { Text("Add") }
            }
            if (due != null) {
                AssistChip(
                    onClick = { due = null },
                    label = { Text("Due " + Format.relativeDate(due!!, today)) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove due date", modifier = Modifier.size(16.dp)) },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (picking) DatePickerDialogFor(due ?: today, onDismiss = { picking = false }, onPicked = { due = it })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationsSheet(
    state: TodoUiState,
    onDismiss: () -> Unit,
    onSelect: (TodoSelection) -> Unit,
    onCreateList: (String) -> Unit,
    onCreateGroup: (String) -> Unit = {},
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onToggleGroup: (String, Boolean) -> Unit = { _, _ -> },
) {
    var creating by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }
    var renamingGroup by remember { mutableStateOf<com.alondra1.app.data.local.ListGroupEntity?>(null) }
    val groupIds = state.groups.map { it.id }.toSet()
    val ungrouped = state.lists.filter { it.groupId == null || it.groupId !in groupIds }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("destinations")) {
            items(SmartView.entries) { view ->
                DestinationRow(view.icon, view.title, state.counts[view] ?: 0, state.selection == TodoSelection.View(view)) {
                    onSelect(TodoSelection.View(view))
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Lists", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            items(ungrouped, key = { it.id }) { list ->
                DestinationRow(Icons.AutoMirrored.Filled.List, list.name, state.listCounts[list.id] ?: 0, state.selection == TodoSelection.List(list.id)) {
                    onSelect(TodoSelection.List(list.id))
                }
            }
            state.groups.forEach { group ->
                item(key = "group-" + group.id) {
                    var menu by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onToggleGroup(group.id, !group.collapsed) }.padding(start = 24.dp, end = 8.dp).testTag("group")) {
                        Text((if (group.collapsed) "▸ " else "▾ ") + group.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Group options for ${group.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename group") }, onClick = { menu = false; renamingGroup = group })
                                DropdownMenuItem(text = { Text("Remove group (keeps its lists)") }, onClick = { menu = false; onDeleteGroup(group.id) })
                            }
                        }
                    }
                }
                if (!group.collapsed) items(state.lists.filter { it.groupId == group.id }, key = { it.id }) { list ->
                    DestinationRow(Icons.AutoMirrored.Filled.List, list.name, state.listCounts[list.id] ?: 0, state.selection == TodoSelection.List(list.id)) {
                        onSelect(TodoSelection.List(list.id))
                    }
                }
            }
            item {
                Row {
                    TextButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("New list")
                    }
                    TextButton(onClick = { creatingGroup = true }) { Text("New group") }
                }
            }
        }
    }
    if (creating) NameDialog(title = "New list", initial = "", confirm = "Create", onDismiss = { creating = false }) { onCreateList(it); creating = false }
    if (creatingGroup) NameDialog(title = "New group", initial = "", confirm = "Create", onDismiss = { creatingGroup = false }) { onCreateGroup(it); creatingGroup = false }
    renamingGroup?.let { g -> NameDialog(title = "Rename group", initial = g.name, confirm = "Rename", onDismiss = { renamingGroup = null }) { onRenameGroup(g.id, it); renamingGroup = null } }
}

@Composable
private fun DestinationRow(icon: ImageVector, label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (count > 0) Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, singleLine = true, label = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private val keepHeight = Modifier.height(0.dp)
