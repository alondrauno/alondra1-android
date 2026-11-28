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
    val groups: List<com.alondra1.app.data.local.ListGroupEntity> = emptyList(),
    val counts: Map<SmartView, Int> = emptyMap(),
    val listCounts: Map<String, Int> = emptyMap(),
    val sort: TaskSort = TaskSort.DUE_DATE,
    val status: StatusFilter = StatusFilter.OPEN,
    val now: ZonedDateTime = ZonedDateTime.now(),
    /** All tags (for the filter), the tag names per task id, and the tag filter (null: all). */
    val tags: List<com.alondra1.app.data.local.TagEntity> = emptyList(),
    val taskTags: Map<String, List<String>> = emptyMap(),
    val tagFilter: String? = null,
) {
    val today: LocalDate get() = now.toLocalDate()
    val listNames: Map<String, String> get() = lists.associate { it.id to it.name }
    val showsStatusFilter: Boolean get() = selection is TodoSelection.List || selection == TodoSelection.View(SmartView.ALL)
}

class TodoViewModel(private val container: AppContainer, initial: TodoSelection) : ViewModel() {
    private val todo = container.todo
    private val selection = MutableStateFlow(initial)
    private val status = MutableStateFlow(StatusFilter.OPEN)
    private val tagFilter = MutableStateFlow<String?>(null)

    /** Re-evaluates date rules every minute (Overdue at a due time, My Day after midnight). */
    private val clock = flow {
        while (true) {
            emit(container.time.zonedNow())
            delay(60_000)
        }
    }

    val state: StateFlow<TodoUiState> = combine(
        todo.observeTasks(),
        combine(todo.observeLists(), todo.observeGroups()) { l, g -> l to g },
        container.settings.settings.map { it.taskSort },
        combine(selection, status) { s, f -> s to f },
        combine(clock, container.tags.observeTags(), container.tags.observeLinks(), tagFilter) { now, t, l, tf -> TagView(now, t, l, tf) },
    ) { tasks, (lists, groups), sort, (sel, filter), tagView ->
        val now = tagView.now
        val names = tagView.tags.associate { it.id to it.name }
        val taskTags = tagView.links.filter { it.objectType == com.alondra1.app.domain.TagRules.TASK && it.tagId in names }
            .groupBy({ it.objectRef }, { names.getValue(it.tagId) }).mapValues { (_, n) -> n.sorted() }
        val filterId = tagView.filter?.takeIf { it in names }
        val tagged = filterId?.let { id -> tagView.links.filter { it.tagId == id && it.objectType == com.alondra1.app.domain.TagRules.TASK }.map { it.objectRef }.toSet() }
        val visible = when (sel) {
            is TodoSelection.View -> TaskRules.select(sel.view, tasks, now, sort, filter)
            is TodoSelection.List -> TaskRules.sort(tasks.filter { TaskRules.inList(it, sel.id, filter) }, sort)
        }.filter { tagged == null || it.id in tagged }
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
            groups = groups,
            counts = TaskRules.counts(tasks, now),
            listCounts = TaskRules.listCounts(tasks),
            sort = sort,
            status = filter,
            now = now,
            tags = tagView.tags,
            taskTags = taskTags,
            tagFilter = filterId,
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

    fun setTagFilter(tagId: String?) { tagFilter.value = tagId }

    private data class TagView(val now: ZonedDateTime, val tags: List<com.alondra1.app.data.local.TagEntity>,
        val links: List<com.alondra1.app.data.local.TagLinkEntity>, val filter: String?)

    fun setSort(sort: TaskSort) { viewModelScope.launch { container.settings.setTaskSort(sort) } }

    /** Returns an error message, or null when the task was added. */
    suspend fun quickAdd(title: String, dueDate: LocalDate?, important: Boolean): String? = add(NewTask(title, dueDate, important, null, emptyList()))

    /**
     * Adds a task from the composer with the screen's rules (My Day joins My Day, Important is starred, a task added
     * in Planned without a date is due today) and the composer's choices on top. Returns an error message or null.
     */
    suspend fun add(new: NewTask): String? = try {
        when (val sel = selection.value) {
            is TodoSelection.View -> todo.addTask(
                new.title, listId = new.listId ?: container.settings.settings.first().defaultListId, view = sel.view,
                dueDate = new.due ?: if (sel.view == SmartView.PLANNED) container.time.today() else null, important = new.important,
                attachments = new.attachments,
            )
            is TodoSelection.List -> todo.addTask(new.title, listId = new.listId ?: sel.id, dueDate = new.due, important = new.important, attachments = new.attachments)
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
    fun createGroup(name: String) { viewModelScope.launch { runCatching { todo.createGroup(name) } } }
    fun renameGroup(id: String, name: String) { viewModelScope.launch { todo.renameGroup(id, name) } }
    /** Removes the group; its lists become ungrouped and keep their tasks. */
    fun deleteGroup(id: String) { viewModelScope.launch { todo.deleteGroup(id) } }
    fun setListGroup(listId: String, groupId: String?) { viewModelScope.launch { todo.setListGroup(listId, groupId) } }
    fun toggleGroup(id: String, collapsed: Boolean) { viewModelScope.launch { todo.setGroupCollapsed(id, collapsed) } }
    fun moveList(id: String, delta: Int) { viewModelScope.launch { todo.moveListPosition(id, delta) } }
    fun deleteList(id: String, moveTasks: Boolean) {
        viewModelScope.launch {
            todo.deleteList(id, moveTasks)
            if (selection.value == TodoSelection.List(id)) select(TodoSelection.View(SmartView.MY_DAY))
        }
    }
}
