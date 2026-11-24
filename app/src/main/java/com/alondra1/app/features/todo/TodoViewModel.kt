package com.alondra1.app.features.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alondra1.app.AppContainer
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.local.TaskListEntity
import com.alondra1.app.data.repository.InvalidTitleException
import com.alondra1.app.domain.SmartView
import com.alondra1.app.domain.StatusFilter
import com.alondra1.app.domain.TaskRules
import com.alondra1.app.domain.TaskSort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

/** What the To-do screen shows: a smart view or a custom list. */
sealed interface TodoSelection {
    data class View(val view: SmartView) : TodoSelection
    data class List(val id: String) : TodoSelection
}

data class TodoUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val selection: TodoSelection = TodoSelection.View(SmartView.MY_DAY),
    val title: String = SmartView.MY_DAY.title,
    val tasks: List<TaskEntity> = emptyList(),
    val lists: List<TaskListEntity> = emptyList(),
    val counts: Map<SmartView, Int> = emptyMap(),
    val listCounts: Map<String, Int> = emptyMap(),
    val sort: TaskSort = TaskSort.DUE_DATE,
    val status: StatusFilter = StatusFilter.OPEN,
    val now: ZonedDateTime = ZonedDateTime.now(),
) {
    val today: LocalDate get() = now.toLocalDate()
    val listNames: Map<String, String> get() = lists.associate { it.id to it.name }
    val showsStatusFilter: Boolean get() = selection is TodoSelection.List || selection == TodoSelection.View(SmartView.ALL)
}

class TodoViewModel(private val container: AppContainer, initial: TodoSelection) : ViewModel() {
    private val todo = container.todo
    private val selection = MutableStateFlow(initial)
    private val status = MutableStateFlow(StatusFilter.OPEN)

    /** Re-evaluates date rules every minute (Overdue at a due time, My Day after midnight). */
    private val clock = flow {
        while (true) {
            emit(container.time.zonedNow())
            delay(60_000)
        }
    }

    val state: StateFlow<TodoUiState> = combine(
        todo.observeTasks(),
        todo.observeLists(),
        container.settings.settings.map { it.taskSort },
        combine(selection, status) { s, f -> s to f },
        clock,
    ) { tasks, lists, sort, (sel, filter), now ->
        val visible = when (sel) {
            is TodoSelection.View -> TaskRules.select(sel.view, tasks, now, sort, filter)
            is TodoSelection.List -> TaskRules.sort(tasks.filter { TaskRules.inList(it, sel.id, filter) }, sort)
        }
        val title = when (sel) {
            is TodoSelection.View -> sel.view.title
            is TodoSelection.List -> lists.firstOrNull { it.id == sel.id }?.name ?: "List"
        }
        TodoUiState(
            loading = false,
            selection = sel,
            title = title,
            tasks = visible,
            lists = lists,
            counts = TaskRules.counts(tasks, now),
            listCounts = TaskRules.listCounts(tasks),
            sort = sort,
            status = filter,
            now = now,
        )
    }
        .catch { emit(TodoUiState(loading = false, error = "Your tasks could not be loaded. Restart the app; your data is kept.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState(selection = initial))

    init {
        viewModelScope.launch { todo.ensureDefaultList() }
    }

    fun select(newSelection: TodoSelection) {
        selection.value = newSelection
        status.value = StatusFilter.OPEN
    }

    fun setStatus(filter: StatusFilter) { status.value = filter }

    fun setSort(sort: TaskSort) { viewModelScope.launch { container.settings.setTaskSort(sort) } }

    /** Returns an error message, or null when the task was added. */
    suspend fun quickAdd(title: String, dueDate: LocalDate?, important: Boolean): String? = try {
        when (val sel = selection.value) {
            is TodoSelection.View -> todo.addTask(
                title, listId = container.settings.settings.first().defaultListId, view = sel.view, dueDate = dueDate, important = important,
            )
            is TodoSelection.List -> todo.addTask(title, listId = sel.id, dueDate = dueDate, important = important)
        }
        null
    } catch (e: InvalidTitleException) {
        e.message
    }

    suspend fun setCompleted(task: TaskEntity, completed: Boolean): TaskEntity? = todo.setCompleted(task.id, completed)
    fun setImportant(task: TaskEntity, important: Boolean) { viewModelScope.launch { todo.setImportant(task.id, important) } }
    suspend fun delete(task: TaskEntity): TaskEntity? = todo.deleteTask(task.id)
    suspend fun restore(snapshot: TaskEntity) = todo.restore(snapshot)

    suspend fun createList(name: String): TaskListEntity? = runCatching { todo.createList(name) }.getOrNull()
    fun renameList(id: String, name: String) { viewModelScope.launch { todo.renameList(id, name) } }
    fun moveList(id: String, delta: Int) { viewModelScope.launch { todo.moveListPosition(id, delta) } }
    fun deleteList(id: String, moveTasks: Boolean) {
        viewModelScope.launch {
            todo.deleteList(id, moveTasks)
            if (selection.value == TodoSelection.List(id)) select(TodoSelection.View(SmartView.MY_DAY))
        }
    }
}
