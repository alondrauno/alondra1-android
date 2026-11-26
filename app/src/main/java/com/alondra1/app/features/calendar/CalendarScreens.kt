package com.alondra1.app.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.alondra1.app.AppContainer
import com.alondra1.app.core.Ids
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.repository.InvalidEventException
import com.alondra1.app.data.repository.InvalidTitleException
import com.alondra1.app.design.LocalExtra
import com.alondra1.app.domain.CalendarFilter
import com.alondra1.app.domain.EventRules
import com.alondra1.app.ui.DatePickerDialogFor
import com.alondra1.app.ui.EmptyState
import com.alondra1.app.ui.Format
import com.alondra1.app.ui.LocalSnackbar
import com.alondra1.app.ui.TimePickerDialogFor
import com.alondra1.app.ui.is24Hour
import com.alondra1.app.ui.showUndo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

enum class CalendarMode { MONTH, AGENDA }

data class DayItems(val events: List<EventEntity>, val tasks: List<TaskEntity>) {
    val isEmpty get() = events.isEmpty() && tasks.isEmpty()
}

data class CalendarState(
    val month: YearMonth,
    val selected: LocalDate,
    val today: LocalDate,
    val filter: CalendarFilter = CalendarFilter.BOTH,
    val mode: CalendarMode = CalendarMode.MONTH,
    val firstDay: DayOfWeek,
    val events: List<EventEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val zone: ZoneId,
) {
    /** Events and dated open tasks on [date], respecting the filter. Tasks are shown, never copied as events. */
    fun itemsOn(date: LocalDate): DayItems = DayItems(
        events = if (filter == CalendarFilter.TASKS) emptyList()
        else events.filter { EventRules.occursOn(it, date, zone) }.sortedBy { EventRules.sortKey(it, zone) },
        tasks = if (filter == CalendarFilter.EVENTS) emptyList()
        else tasks.filter { it.deletedUtc == null && it.dueDate == date }.sortedWith(compareBy({ it.completed }, { it.dueTime })),
    )
}

class CalendarViewModel(private val container: AppContainer) : ViewModel() {
    private val today = container.time.today()
    private val month = MutableStateFlow(YearMonth.from(today))
    private val selected = MutableStateFlow(today)
    private val filter = MutableStateFlow(CalendarFilter.BOTH)
    private val mode = MutableStateFlow(CalendarMode.MONTH)

    val state: StateFlow<CalendarState> = combine(
        container.calendar.observeEvents(),
        container.todo.observeTasks(),
        combine(month, selected) { m, s -> m to s },
        combine(filter, mode) { f, m -> f to m },
        container.settings.settings,
    ) { events, tasks, (m, s), (f, md), settings ->
        CalendarState(
            month = m, selected = s, today = container.time.today(), filter = f, mode = md,
            firstDay = settings.firstDayOfWeek ?: WeekFields.of(Locale.getDefault()).firstDayOfWeek,
            events = events, tasks = tasks, zone = container.time.zone(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        CalendarState(YearMonth.from(today), today, today, firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek, zone = container.time.zone()))

    fun select(date: LocalDate) { selected.value = date; month.value = YearMonth.from(date) }
    fun shiftMonth(delta: Long) { month.value = month.value.plusMonths(delta) }
    fun goToday() = select(container.time.today())
    fun setFilter(f: CalendarFilter) { filter.value = f }
    fun setMode(m: CalendarMode) { mode.value = m }
    suspend fun addTask(title: String, date: LocalDate): String? = try { container.todo.addTask(title, dueDate = date); null } catch (e: InvalidTitleException) { e.message }
    fun toggleTask(task: TaskEntity) { viewModelScope.launch { container.todo.setCompleted(task.id, !task.completed) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onOpenEvent: (String) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    onOpenTask: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    /** Inside Planned: the screen header is Planned's, so only the month controls are shown here. */
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addingTask by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    Column(Modifier.fillMaxSize()) {
        if (embedded) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp)) {
            Text(Format.monthTitle(state.month.year, state.month.month), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag("monthTitle"))
            IconButton(onClick = { viewModel.shiftMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
            TextButton(onClick = { viewModel.goToday() }) { Text("Today") }
            IconButton(onClick = { viewModel.shiftMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
            IconButton(onClick = { viewModel.setMode(if (state.mode == CalendarMode.MONTH) CalendarMode.AGENDA else CalendarMode.MONTH) }) {
                Icon(if (state.mode == CalendarMode.MONTH) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.CalendarViewMonth,
                    contentDescription = if (state.mode == CalendarMode.MONTH) "Agenda view" else "Month view")
            }
        } else TopAppBar(
            title = { Text(Format.monthTitle(state.month.year, state.month.month), modifier = Modifier.testTag("monthTitle")) },
            actions = {
                IconButton(onClick = { viewModel.shiftMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
                TextButton(onClick = { viewModel.goToday() }) { Text("Today") }
                IconButton(onClick = { viewModel.shiftMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
                IconButton(onClick = { viewModel.setMode(if (state.mode == CalendarMode.MONTH) CalendarMode.AGENDA else CalendarMode.MONTH) }) {
                    Icon(if (state.mode == CalendarMode.MONTH) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.CalendarViewMonth,
                        contentDescription = if (state.mode == CalendarMode.MONTH) "Agenda view" else "Month view")
                }
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            },
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarFilter.entries.forEach { f ->
                FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) })
            }
        }
        if (state.mode == CalendarMode.AGENDA) {
            Agenda(state, onOpenEvent, onOpenTask, viewModel::toggleTask, Modifier.weight(1f))
        } else if (wide) {
            Row(Modifier.weight(1f)) {
                MonthGrid(state, viewModel::select, Modifier.weight(1.2f).padding(12.dp))
                DayPanel(state, onOpenEvent, onOpenTask, viewModel::toggleTask, { onNewEvent(state.selected) }, { addingTask = true }, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.weight(1f)) {
                MonthGrid(state, viewModel::select, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                DayPanel(state, onOpenEvent, onOpenTask, viewModel::toggleTask, { onNewEvent(state.selected) }, { addingTask = true }, Modifier.weight(1f))
            }
        }
    }
    if (addingTask) {
        val scope = rememberCoroutineScope()
        var title by rememberSaveable { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addingTask = false },
            title = { Text("Task due " + Format.relativeDate(state.selected, state.today)) },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it; error = null }, label = { Text("Title") }, singleLine = true,
                    isError = error != null, supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.testTag("calendarTaskTitle"))
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch { error = viewModel.addTask(title, state.selected); if (error == null) { title = ""; addingTask = false } }
                }) { Text("Add task") }
            },
            dismissButton = { TextButton(onClick = { addingTask = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MonthGrid(state: CalendarState, onSelect: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val extra = LocalExtra.current
    val first = state.month.atDay(1)
    val offset = ((first.dayOfWeek.value - state.firstDay.value) + 7) % 7
    val start = first.minusDays(offset.toLong())
    val weeks = ((offset + state.month.lengthOfMonth()) + 6) / 7
    Column(modifier.testTag("monthGrid")) {
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                val day = state.firstDay.plus(i.toLong())
                Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        (0 until weeks).forEach { w ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { d ->
                    val date = start.plusDays((w * 7 + d).toLong())
                    val inMonth = date.month == state.month.month
                    val items = state.itemsOn(date)
                    val selected = date == state.selected
                    val isToday = date == state.today
                    Box(
                        Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = false).heightIn(min = 44.dp).padding(2.dp)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, MaterialTheme.shapes.small)
                            .then(if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                            .clickable { onSelect(date) }
                            .semantics {
                                contentDescription = buildString {
                                    append(date.format(Format.longDate))
                                    if (items.events.isNotEmpty()) append(", ${items.events.size} events")
                                    if (items.tasks.isNotEmpty()) append(", ${items.tasks.size} tasks")
                                }
                            }
                            .testTag("day:$date"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday) FontWeight.Bold else null,
                                color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(6.dp)) {
                                if (items.events.isNotEmpty()) Box(Modifier.size(6.dp).background(extra.event, CircleShape))
                                if (items.tasks.isNotEmpty()) Box(Modifier.size(6.dp).background(extra.task, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayPanel(
    state: CalendarState,
    onOpenEvent: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onToggleTask: (TaskEntity) -> Unit,
    onAddEvent: () -> Unit,
    onAddTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = state.itemsOn(state.selected)
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.relativeDate(state.selected, state.today), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onAddTask, modifier = Modifier.testTag("addTaskOnDay")) { Icon(Icons.Filled.AddTask, null); Spacer(Modifier.width(4.dp)); Text("Task") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onAddEvent, modifier = Modifier.testTag("addEventOnDay")) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Event") }
        }
        if (items.isEmpty) {
            Text("Nothing on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items.events, key = { "e" + it.id }) { EventRow(it, state, onOpenEvent) }
                items(items.tasks, key = { "t" + it.id }) { TaskLine(it, onOpenTask, onToggleTask) }
            }
        }
    }
}

@Composable
private fun EventRow(event: EventEntity, state: CalendarState, onOpen: (String) -> Unit) {
    val is24 = is24Hour()
    val time = if (event.allDay) "All day" else {
        val start = event.startUtc!!.atZone(state.zone).toLocalDateTime()
        val end = event.endUtc!!.atZone(state.zone).toLocalDateTime()
        Format.time(start.toLocalTime(), is24) + " – " + Format.time(end.toLocalTime(), is24) +
            if (end.toLocalDate() != start.toLocalDate()) " (" + Format.relativeDate(end.toLocalDate(), state.today) + ")" else ""
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open event") { onOpen(event.id) }.testTag("event:${event.title}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 36.dp).background(LocalExtra.current.event, MaterialTheme.shapes.small))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Event, contentDescription = "Event", tint = LocalExtra.current.event, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(event.title, style = MaterialTheme.typography.bodyLarge)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskLine(task: TaskEntity, onOpen: (String) -> Unit, onToggle: (TaskEntity) -> Unit) {
    val is24 = is24Hour()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open task") { onOpen(task.id) }.testTag("calTask:${task.title}")) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onToggle(task) }) {
                Icon(if (task.completed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (task.completed) "Mark ${task.title} as not completed" else "Complete ${task.title}",
                    tint = LocalExtra.current.task)
            }
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, textDecoration = if (task.completed) TextDecoration.LineThrough else null)
                Text("Task" + (task.dueTime?.let { " · due " + Format.time(it, is24) } ?: " · due this day"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Agenda(state: CalendarState, onOpenEvent: (String) -> Unit, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, modifier: Modifier) {
    val days = (0L until 60L).map { state.selected.plusDays(it) }.map { it to state.itemsOn(it) }.filter { !it.second.isEmpty }
    if (days.isEmpty()) {
        EmptyState(Icons.Outlined.EventNote, "Nothing in the next 60 days", "Add an event, or give a task a due date.", modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("agenda"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { (date, items) ->
            item(key = "h$date") {
                Text(Format.relativeDate(date, state.today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            items(items.events, key = { "e$date" + it.id }) { EventRow(it, state, onOpenEvent) }
            items(items.tasks, key = { "t$date" + it.id }) { TaskLine(it, onOpenTask, onToggle) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Event editor

val REMINDER_CHOICES = listOf<Pair<Int?, String>>(
    null to "No reminder", 0 to "At start", 10 to "10 minutes before", 30 to "30 minutes before", 60 to "1 hour before", 1440 to "1 day before",
)

class EventEditorViewModel(private val container: AppContainer, requestedId: String?, date: LocalDate?) : ViewModel() {
    val zone: ZoneId = container.time.zone()
    var isNew by mutableStateOf(requestedId == null)
        private set
    var id by mutableStateOf(requestedId ?: Ids.newId())
        private set
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var allDay by mutableStateOf(false)
    private val day = date ?: container.time.today()
    /** Today: the next full hour (at most 23:00). Another day: 09:00. One hour long by default. */
    private val defaultStart: LocalDateTime = run {
        val now = container.time.zonedNow().toLocalDateTime()
        if (day == now.toLocalDate()) {
            val next = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            if (next.toLocalDate() == day) next else day.atTime(23, 0)
        } else day.atTime(9, 0)
    }
    private val defaultEnd = defaultStart.plusHours(1)
    var startDate by mutableStateOf(defaultStart.toLocalDate())
    var startTime by mutableStateOf(defaultStart.toLocalTime())
    var endDate by mutableStateOf(defaultEnd.toLocalDate())
    var endTime by mutableStateOf(defaultEnd.toLocalTime())
    var reminderMinutes by mutableStateOf<Int?>(null)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(requestedId == null)
        private set
    var createdTimeZone: String = zone.id
        private set

    init {
        if (requestedId != null) viewModelScope.launch {
            container.calendar.getEvent(requestedId)?.takeIf { it.deletedUtc == null }?.let { e ->
                title = e.title; description = e.description; allDay = e.allDay; reminderMinutes = e.reminderMinutes; createdTimeZone = e.timeZoneId
                if (e.allDay) {
                    startDate = e.startDate!!; endDate = e.endDate ?: e.startDate
                } else {
                    val s = e.startUtc!!.atZone(zone).toLocalDateTime(); val en = e.endUtc!!.atZone(zone).toLocalDateTime()
                    startDate = s.toLocalDate(); startTime = s.toLocalTime(); endDate = en.toLocalDate(); endTime = en.toLocalTime()
                }
            }
            loaded = true
        }
    }

    /** Keeps the duration when the start moves past the end. */
    fun moveStart(date: LocalDate = startDate, time: LocalTime = startTime) {
        val oldStart = LocalDateTime.of(startDate, startTime)
        val oldEnd = LocalDateTime.of(endDate, endTime)
        val duration = java.time.Duration.between(oldStart, oldEnd).let { if (it.isNegative) java.time.Duration.ofHours(1) else it }
        startDate = date; startTime = time
        val newEnd = LocalDateTime.of(date, time).plus(duration)
        if (newEnd.isAfter(LocalDateTime.of(endDate, endTime)) || allDay && endDate.isBefore(date)) {
            endDate = if (allDay) maxOf(endDate, date) else newEnd.toLocalDate(); if (!allDay) endTime = newEnd.toLocalTime()
        }
        error = null
    }

    suspend fun save(): Boolean {
        val draft = EventEntity(
            id = id, title = title, description = description, allDay = allDay,
            startUtc = if (allDay) null else LocalDateTime.of(startDate, startTime).atZone(zone).toInstant(),
            endUtc = if (allDay) null else LocalDateTime.of(endDate, endTime).atZone(zone).toInstant(),
            startDate = if (allDay) startDate else null, endDate = if (allDay) endDate else null,
            timeZoneId = zone.id, reminderMinutes = reminderMinutes,
            createdUtc = java.time.Instant.EPOCH, modifiedUtc = java.time.Instant.EPOCH,
        )
        return try {
            container.calendar.save(draft); error = null; isNew = false; true
        } catch (e: InvalidEventException) {
            error = e.error.message; false
        }
    }

    suspend fun delete(): EventEntity? = container.calendar.delete(id)
    suspend fun restore(e: EventEntity) = container.calendar.restore(e)

    /** Date and time as written at the top of the meeting notes. */
    fun meetingWhen(locale: Locale = Locale.getDefault()): String {
        val day = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)
        val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale)
        return if (allDay) startDate.format(day) + (if (endDate != startDate) " – " + endDate.format(day) else "") + " · All day"
        else startDate.format(day) + " · " + startTime.format(hm) + "–" + (if (endDate != startDate) endDate.format(day) + " " else "") + endTime.format(hm)
    }

    /** Notes spec §11: the event's meeting notes (the linked note, or a new one from the meeting template). Returns the note id. */
    suspend fun meetingNotes(): String = container.notes.meetingNotes(id, title, meetingWhen(), description)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(viewModel: EventEditorViewModel, onClose: () -> Unit, onMeetingNotes: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val is24 = is24Hour()
    val today = LocalDate.now()
    var pick by remember { mutableStateOf<String?>(null) }
    var reminderMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (viewModel.isNew) "New event" else "Edit event") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving") } },
            actions = {
                if (!viewModel.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete event") }
                TextButton(onClick = { scope.launch(Dispatchers.Main) { if (viewModel.save()) onClose() } }, modifier = Modifier.testTag("saveEvent")) { Text("Save") }
            },
        )
        if (!viewModel.loaded) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = viewModel.title, onValueChange = { viewModel.title = it.take(500); viewModel.error = null }, label = { Text("Title") },
                singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("eventTitle"))
            if (!viewModel.isNew) {
                OutlinedButton(onClick = { scope.launch(Dispatchers.Main) { val note = viewModel.meetingNotes(); withContext(Dispatchers.Main) { onMeetingNotes(note) } } }, modifier = Modifier.testTag("meetingNotes")) {
                    Icon(Icons.Outlined.EventNote, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Meeting notes")
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    ListItem(headlineContent = { Text("All day") }, trailingContent = {
                        Switch(checked = viewModel.allDay, onCheckedChange = { viewModel.allDay = it; viewModel.error = null }, modifier = Modifier.testTag("allDay"))
                    })
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Starts") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { pick = "startDate" }) { Text(Format.relativeDate(viewModel.startDate, today)) }
                                if (!viewModel.allDay) TextButton(onClick = { pick = "startTime" }) { Text(Format.time(viewModel.startTime, is24)) }
                            }
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Ends") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { pick = "endDate" }, modifier = Modifier.testTag("endDate")) { Text(Format.relativeDate(viewModel.endDate, today)) }
                                if (!viewModel.allDay) TextButton(onClick = { pick = "endTime" }) { Text(Format.time(viewModel.endTime, is24)) }
                            }
                        },
                    )
                    HorizontalDivider()
                    ListItem(headlineContent = { Text("Time zone") }, supportingContent = {
                        Text(if (viewModel.allDay) "All-day events keep their dates in every time zone" else viewModel.zone.id)
                    })
                    HorizontalDivider()
                    Box {
                        ListItem(headlineContent = { Text("Reminder") },
                            supportingContent = { Text(REMINDER_CHOICES.first { it.first == viewModel.reminderMinutes }.second + if (viewModel.allDay && viewModel.reminderMinutes != null) " (from 9:00 on the start day)" else "") },
                            modifier = Modifier.clickable { reminderMenu = true })
                        DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                            REMINDER_CHOICES.forEach { (minutes, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.reminderMinutes = minutes; reminderMenu = false })
                            }
                        }
                    }
                }
            }
            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("eventError")) }
            OutlinedTextField(value = viewModel.description, onValueChange = { viewModel.description = it }, label = { Text("Description") }, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
            Text("Events are stored on this device only. Alondra1 does not read or change your phone's system calendar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
    when (pick) {
        "startDate" -> DatePickerDialogFor(viewModel.startDate, { pick = null }, { viewModel.moveStart(date = it) }, "Start date")
        "endDate" -> DatePickerDialogFor(viewModel.endDate, { pick = null }, { viewModel.endDate = it; viewModel.error = null }, "End date")
        "startTime" -> TimePickerDialogFor(viewModel.startTime, { pick = null }, { viewModel.moveStart(time = it) })
        "endTime" -> TimePickerDialogFor(viewModel.endTime, { pick = null }, { viewModel.endTime = it; viewModel.error = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.Main) { val snap = viewModel.delete(); onClose(); if (snap != null) snackbar.showUndo("Event deleted") { viewModel.restore(snap) } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
