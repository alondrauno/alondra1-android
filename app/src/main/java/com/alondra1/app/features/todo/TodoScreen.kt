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

/** How a task screen titles itself. */
fun TodoSelection.headerIcon(lists: List<com.alondra1.app.data.local.TaskListEntity>): ImageVector = when (this) {
    is TodoSelection.View -> view.icon
    is TodoSelection.List -> Icons.AutoMirrored.Filled.List
}

/**
 * A task screen: My Day, Planned (grouped by date) and every list or smart list opened from Lists. Header, cards and
 * composer follow the Android UI spec (§5); [onBack] is set for a list opened from Lists. [belowHeader] holds screen
 * extras such as Planned's List/Calendar switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    viewModel: TodoViewModel,
    onOpenTask: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    selectedTaskId: String? = null,
    onBack: (() -> Unit)? = null,
    belowHeader: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deletingList by remember { mutableStateOf(false) }
    var choosingGroup by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selection = state.selection
    val customList = (selection as? TodoSelection.List)?.let { sel -> state.lists.firstOrNull { it.id == sel.id } }
    val open = state.tasks.count { !it.completed }

    val menu = buildList {
        TaskSort.entries.forEach { sort -> add(com.alondra1.app.design.MenuAction("Sort by " + sort.label.lowercase(), checked = sort == state.sort) { viewModel.setSort(sort) }) }
        if (customList != null && !customList.isDefault) {
            add(com.alondra1.app.design.MenuAction("Rename list") { renaming = true })
            add(com.alondra1.app.design.MenuAction("Move to group…") { choosingGroup = true })
            add(com.alondra1.app.design.MenuAction("Move list up") { viewModel.moveList(customList.id, -1) })
            add(com.alondra1.app.design.MenuAction("Move list down") { viewModel.moveList(customList.id, 1) })
            add(com.alondra1.app.design.MenuAction("Delete list", destructive = true) { deletingList = true })
        }
    }

    Column(Modifier.fillMaxSize()) {
        com.alondra1.app.design.AppHeader(
            title = state.title,
            icon = selection.headerIcon(state.lists),
            subtitle = if (selection == TodoSelection.View(SmartView.MY_DAY)) state.today.format(Format.longDate) else null,
            count = if (open > 0) "$open open" else null,
            onBack = onBack,
            onSearch = onSearch,
            onAccount = if (onBack == null) onSettings else null,
            menu = menu,
            titleModifier = Modifier.semantics(mergeDescendants = true) { }.testTag("viewSelector"),
        )
        belowHeader()

        if (state.showsStatusFilter) {
            Row(Modifier.padding(horizontal = com.alondra1.app.design.Space.screen), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { filter ->
                    FilterChip(selected = state.status == filter, onClick = { viewModel.setStatus(filter) }, label = { Text(filter.label) })
                }
            }
        }

        Box(Modifier.weight(1f)) {
            val toggle: (TaskEntity) -> Unit = { task ->
                scope.launch {
                    val before = viewModel.setCompleted(task, !task.completed)
                    if (before != null) snackbar.showUndo(if (!task.completed) "Completed" else "Marked as not completed") { viewModel.restore(before) }
                }
            }
            when {
                state.error != null -> EmptyState(Icons.Filled.WarningAmber, "Something went wrong", state.error!!)
                !state.loading && state.tasks.isEmpty() -> TodoEmpty(state)
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = com.alondra1.app.design.Space.l, end = com.alondra1.app.design.Space.l, top = 2.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(com.alondra1.app.design.Space.cardGap),
                    modifier = Modifier.fillMaxSize().testTag("taskList"),
                ) {
                    val groups = if (selection == TodoSelection.View(SmartView.PLANNED)) plannedGroups(state.tasks, state.today) else listOf(null to state.tasks)
                    groups.forEach { (label, tasks) ->
                        if (label != null) item(key = "group:$label") {
                            com.alondra1.app.design.SectionLabel(label, trailing = tasks.size.toString(), modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        items(tasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                listName = state.listNames[task.listId],
                                showList = state.selection is TodoSelection.View,
                                inMyDay = selection == TodoSelection.View(SmartView.MY_DAY),
                                // Planned groups by day: the card shows only the time, not the day again.
                                dayInGroup = label != null && label != "Overdue",
                                today = state.today,
                                overdue = TaskRules.isOverdue(task, state.now),
                                selected = task.id == selectedTaskId,
                                onOpen = { onOpenTask(task.id) },
                                onToggleComplete = { toggle(task) },
                                onToggleImportant = { viewModel.setImportant(task, !task.important) },
                            )
                        }
                    }
                }
            }
        }

        TaskComposer(
            hint = when (selection) {
                TodoSelection.View(SmartView.MY_DAY) -> "Add a task to My Day"
                TodoSelection.View(SmartView.IMPORTANT) -> "Add an important task"
                TodoSelection.View(SmartView.PLANNED) -> "Add a task for today"
                is TodoSelection.List -> "Add a task to ${state.title}"
                else -> "Add a task"
            },
            lists = state.lists,
            chooseList = selection is TodoSelection.View,
            onAdd = { viewModel.add(it) },
            modifier = Modifier.imePadding(),
        )
    }

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
                    if (state.groups.isEmpty()) Text("Create a group with New group on the Lists screen.", style = MaterialTheme.typography.bodySmall)
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
            confirmButton = { TextButton(onClick = { viewModel.deleteList(selection.id, moveTasks = true); deletingList = false; onBack?.invoke() }) { Text("Move tasks and delete") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.deleteList(selection.id, moveTasks = false); deletingList = false; onBack?.invoke() }) {
                        Text("Delete tasks too", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { deletingList = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/** Planned: overdue first, then one group per due day (Today, Tomorrow, dates), in date order. */
fun plannedGroups(tasks: List<TaskEntity>, today: LocalDate): List<Pair<String?, List<TaskEntity>>> {
    val dated = tasks.filter { it.dueDate != null }
    val overdue = dated.filter { it.dueDate!! < today && !it.completed }
    val rest = (dated - overdue.toSet()).groupBy { it.dueDate!! }.toSortedMap()
    return buildList {
        if (overdue.isNotEmpty()) add("Overdue" to overdue)
        rest.forEach { (day, items) -> add(Format.relativeDate(day, today) to items) }
        tasks.filter { it.dueDate == null }.takeIf { it.isNotEmpty() }?.let { add("No date" to it) }
    }
}

@Composable
private fun TodoEmpty(state: TodoUiState) {
    val (title, body) = when (state.selection) {
        TodoSelection.View(SmartView.MY_DAY) -> "Nothing planned for today" to "Add a task to get started."
        TodoSelection.View(SmartView.IMPORTANT) -> "No important tasks" to "Star a task to see it here."
        TodoSelection.View(SmartView.PLANNED), TodoSelection.View(SmartView.UPCOMING) -> "Nothing planned" to "Give a task a due date to plan it."
        TodoSelection.View(SmartView.OVERDUE) -> "Nothing overdue" to "You are on track."
        TodoSelection.View(SmartView.COMPLETED) -> "No completed tasks yet" to "Completed tasks appear here."
        TodoSelection.View(SmartView.UNSCHEDULED) -> "No unscheduled tasks" to "Tasks without a due date appear here."
        else -> "No tasks here" to "Add a task to get started."
    }
    com.alondra1.app.design.CompactEmptyState(state.selection.headerIcon(state.lists), title, body)
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
    inMyDay: Boolean = false,
    dayInGroup: Boolean = false,
) {
    val is24 = is24Hour()
    val steps = remember(task.stepsJson) { com.alondra1.app.domain.TaskJson.steps(task.stepsJson) }
    val meta = com.alondra1.app.design.CardMeta(
        parts = buildList {
            if (task.myDayDate == today && !task.completed && !inMyDay) add("My Day")
            if (showList && listName != null) add(listName)
        },
        due = task.dueDate?.let { d ->
            if (dayInGroup) task.dueTime?.let { t -> Format.time(t, is24) }
            else Format.relativeDate(d, today) + (task.dueTime?.let { t -> " " + Format.time(t, is24) } ?: "")
        },
        overdue = overdue,
        repeats = task.recurrenceJson != null,
        reminder = task.remindersJson != "[]" && !task.completed,
        attachments = com.alondra1.app.domain.TaskAttachments.count(task.attachmentsJson),
        stepsDone = steps.count { it.completed },
        stepsTotal = steps.size,
    )
    com.alondra1.app.design.TaskCard(
        title = task.title, completed = task.completed, important = task.important, meta = meta,
        onOpen = onOpen, onToggleComplete = onToggleComplete, onToggleImportant = onToggleImportant,
        selected = selected, modifier = Modifier.testTag("task:${task.title}"),
    )
}

/** Smart lists shown on the Lists screen (My Day and Planned have their own tabs). */
val LIST_SMART_VIEWS = listOf(SmartView.IMPORTANT, SmartView.UPCOMING, SmartView.OVERDUE, SmartView.UNSCHEDULED, SmartView.ALL, SmartView.COMPLETED)

/**
 * The Lists destination (Android UI spec §6): smart lists (Important first), then the user's lists and groups with
 * open counts, and one clear way to create a list or a group. Opening an entry shows it as a task screen.
 */
@Composable
fun ListsScreen(viewModel: TodoViewModel, onOpen: (TodoSelection) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }
    var renamingGroup by remember { mutableStateOf<com.alondra1.app.data.local.ListGroupEntity?>(null) }
    val groupIds = state.groups.map { it.id }.toSet()
    val ungrouped = state.lists.filter { it.groupId == null || it.groupId !in groupIds }
    val gap = com.alondra1.app.design.Space.s

    Column(Modifier.fillMaxSize()) {
        com.alondra1.app.design.AppHeader(
            title = "Lists", icon = Icons.AutoMirrored.Filled.List, onSearch = onSearch, onAccount = onSettings,
            menu = listOf(
                com.alondra1.app.design.MenuAction("New list") { creating = true },
                com.alondra1.app.design.MenuAction("New group") { creatingGroup = true },
            ),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = com.alondra1.app.design.Space.l, end = com.alondra1.app.design.Space.l, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize().testTag("destinations"),
        ) {
            items(LIST_SMART_VIEWS, key = { "view:" + it.name }) { view ->
                com.alondra1.app.design.ListRow(
                    view.icon, view.title, state.counts[view] ?: 0, onClick = { onOpen(TodoSelection.View(view)) },
                    iconTint = if (view == SmartView.IMPORTANT) LocalExtra.current.star else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("smart:${view.title}"),
                )
            }
            item(key = "my-lists") { com.alondra1.app.design.SectionLabel("My lists", modifier = Modifier.padding(horizontal = 4.dp)) }
            items(ungrouped, key = { it.id }) { list ->
                com.alondra1.app.design.ListRow(Icons.AutoMirrored.Filled.List, list.name, state.listCounts[list.id] ?: 0, onClick = { onOpen(TodoSelection.List(list.id)) },
                    modifier = Modifier.testTag("list:${list.name}"))
            }
            state.groups.forEach { group ->
                item(key = "group-" + group.id) {
                    var menu by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (group.collapsed) "Expand group" else "Collapse group") {
                            viewModel.toggleGroup(group.id, !group.collapsed)
                        }.padding(start = 4.dp).testTag("group")) {
                        Text((if (group.collapsed) "▸ " else "▾ ") + group.name, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Group options for ${group.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename group") }, onClick = { menu = false; renamingGroup = group })
                                DropdownMenuItem(text = { Text("Remove group (keeps its lists)") }, onClick = { menu = false; viewModel.deleteGroup(group.id) })
                            }
                        }
                    }
                }
                if (!group.collapsed) items(state.lists.filter { it.groupId == group.id }, key = { it.id }) { list ->
                    com.alondra1.app.design.ListRow(Icons.AutoMirrored.Filled.List, list.name, state.listCounts[list.id] ?: 0, onClick = { onOpen(TodoSelection.List(list.id)) },
                        modifier = Modifier.padding(start = 12.dp).testTag("list:${list.name}"))
                }
            }
            item(key = "create") {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(top = 4.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { creating = true }, modifier = Modifier.testTag("newList")) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("New list")
                    }
                    androidx.compose.material3.OutlinedButton(onClick = { creatingGroup = true }) { Text("New group") }
                }
            }
        }
    }
    if (creating) NameDialog(title = "New list", initial = "", confirm = "Create", onDismiss = { creating = false }) { name ->
        creating = false
        scope.launch { viewModel.createList(name)?.let { onOpen(TodoSelection.List(it.id)) } }
    }
    if (creatingGroup) NameDialog(title = "New group", initial = "", confirm = "Create", onDismiss = { creatingGroup = false }) { viewModel.createGroup(it); creatingGroup = false }
    renamingGroup?.let { g -> NameDialog(title = "Rename group", initial = g.name, confirm = "Rename", onDismiss = { renamingGroup = null }) { viewModel.renameGroup(g.id, it); renamingGroup = null } }
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
