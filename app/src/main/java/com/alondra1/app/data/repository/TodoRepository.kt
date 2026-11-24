package com.alondra1.app.data.repository

import androidx.room.withTransaction
import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.local.TaskListEntity
import com.alondra1.app.data.notifications.ReminderScheduler
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.data.sync.EntityType
import com.alondra1.app.domain.SmartView
import com.alondra1.app.domain.TaskRules
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class InvalidTitleException : IllegalArgumentException("A task needs a title of 1 to ${TaskRules.TITLE_MAX} characters")

/** Offline-first task storage. Every change commits locally first; nothing here touches the network. */
class TodoRepository(
    private val db: AppDatabase,
    private val time: TimeSource,
    private val reminders: ReminderScheduler,
    private val changes: ChangeRecorder = ChangeRecorder.LocalOnly,
) {
    private val lists = db.taskLists()
    private val tasks = db.tasks()

    fun observeLists(): Flow<List<TaskListEntity>> = lists.observeActive()
    fun observeTasks(): Flow<List<TaskEntity>> = tasks.observeAll()
    fun observeTask(id: String): Flow<TaskEntity?> = tasks.observe(id)
    suspend fun getTask(id: String): TaskEntity? = tasks.get(id)

    /** The undeletable default "Tasks" list (contract: isDefault). Created on first use. */
    suspend fun ensureDefaultList(): TaskListEntity = db.withTransaction {
        lists.defaultList() ?: TaskListEntity(
            id = Ids.newId(), name = "Tasks", position = 0.0, isDefault = true,
            createdUtc = time.now(), modifiedUtc = time.now(),
        ).also { lists.insert(it); changes.recorded(EntityType.TASK_LIST, it.id) }
    }

    /**
     * Quick add, with the Windows rules: in My Day the task joins My Day, in Important it is starred, and with a
     * [dueDate] (e.g. from the calendar) it is due that day. Other views add to the chosen or default list.
     */
    suspend fun addTask(
        rawTitle: String,
        listId: String? = null,
        view: SmartView? = null,
        dueDate: LocalDate? = null,
        dueTime: LocalTime? = null,
        important: Boolean = false,
        sourceNoteId: String? = null,
        notes: String = "",
    ): TaskEntity {
        val title = TaskRules.cleanTitle(rawTitle) ?: throw InvalidTitleException()
        val task = db.withTransaction {
            val target = listId?.let { lists.get(it)?.takeIf { l -> l.deletedUtc == null } } ?: ensureDefaultList()
            val now = time.now()
            TaskEntity(
                id = Ids.newId(),
                listId = target.id,
                title = title,
                notes = notes,
                important = important || view == SmartView.IMPORTANT,
                position = tasks.maxPosition(target.id) + 1,
                createdUtc = now,
                modifiedUtc = now,
                dueDate = dueDate,
                dueTime = dueTime?.takeIf { dueDate != null },
                myDayDate = if (view == SmartView.MY_DAY) time.today() else null,
                sourceNoteId = sourceNoteId,
            ).also { tasks.insert(it) }
        }
        changes.recorded(EntityType.TASK, task.id)
        return task
    }

    /** Saves edits from the detail screen. Title rules are enforced here, not only in the UI. */
    suspend fun updateTask(updated: TaskEntity): TaskEntity {
        val title = TaskRules.cleanTitle(updated.title) ?: throw InvalidTitleException()
        val saved = updated.copy(
            title = title,
            dueTime = updated.dueTime?.takeIf { updated.dueDate != null },
            modifiedUtc = time.now(),
        )
        tasks.update(saved)
        reminders.syncTask(saved)
        changes.recorded(EntityType.TASK, saved.id)
        return saved
    }

    private suspend fun mutate(id: String, change: (TaskEntity) -> TaskEntity): TaskEntity? {
        val current = tasks.get(id) ?: return null
        if (current.deletedUtc != null) return null
        val next = change(current).copy(modifiedUtc = time.now())
        tasks.update(next)
        reminders.syncTask(next)
        changes.recorded(EntityType.TASK, id)
        return next
    }

    /** Returns the task as it was before, for Undo. */
    suspend fun setCompleted(id: String, completed: Boolean): TaskEntity? {
        val before = tasks.get(id)
        mutate(id) { it.copy(completed = completed, completedUtc = if (completed) time.now() else null) }
        return before
    }

    suspend fun setImportant(id: String, important: Boolean) = mutate(id) { it.copy(important = important) }

    suspend fun setMyDay(id: String, inMyDay: Boolean) = mutate(id) { it.copy(myDayDate = if (inMyDay) time.today() else null) }

    suspend fun setReminder(id: String, at: LocalDateTime?) = mutate(id) { it.copy(reminderAt = at) }

    suspend fun moveTask(id: String, listId: String): TaskEntity? {
        val position = tasks.maxPosition(listId) + 1
        return mutate(id) { it.copy(listId = listId, position = position) }
    }

    /** Soft delete (tombstone, so a future sync cannot resurrect it). Returns the task for Undo. */
    suspend fun deleteTask(id: String): TaskEntity? {
        val before = tasks.get(id) ?: return null
        mutate(id) { it.copy(deletedUtc = time.now()) }
        reminders.cancelTask(id)
        return before
    }

    /** Undo for complete/delete/edit: writes the earlier version back. */
    suspend fun restore(snapshot: TaskEntity) {
        val restored = snapshot.copy(modifiedUtc = time.now())
        tasks.update(restored)
        reminders.syncTask(restored)
        changes.recorded(EntityType.TASK, snapshot.id)
    }

    suspend fun createList(rawName: String): TaskListEntity {
        val name = rawName.trim().take(200).ifEmpty { throw IllegalArgumentException("A list needs a name") }
        val list = TaskListEntity(
            id = Ids.newId(), name = name, position = lists.maxPosition() + 1,
            createdUtc = time.now(), modifiedUtc = time.now(),
        )
        lists.insert(list)
        changes.recorded(EntityType.TASK_LIST, list.id)
        return list
    }

    suspend fun renameList(id: String, rawName: String) {
        val name = rawName.trim().take(200).ifEmpty { return }
        val list = lists.get(id) ?: return
        lists.update(list.copy(name = name, modifiedUtc = time.now()))
        changes.recorded(EntityType.TASK_LIST, id)
    }

    suspend fun moveListPosition(id: String, delta: Int) {
        val ordered = lists.active().filter { !it.isDefault }
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return
        val a = ordered[index]
        val b = ordered[target]
        db.withTransaction {
            lists.update(a.copy(position = b.position, modifiedUtc = time.now()))
            lists.update(b.copy(position = a.position, modifiedUtc = time.now()))
        }
        changes.recorded(EntityType.TASK_LIST, a.id)
        changes.recorded(EntityType.TASK_LIST, b.id)
    }

    /**
     * Deletes a list in one transaction. The default list cannot be deleted. With [moveTasksToDefault] (the
     * safe default) its tasks move to Tasks; otherwise they are deleted with it.
     */
    suspend fun deleteList(id: String, moveTasksToDefault: Boolean = true) {
        val affected = mutableListOf<String>()
        db.withTransaction {
            val list = lists.get(id) ?: return@withTransaction
            require(!list.isDefault) { "The default list cannot be deleted" }
            val fallback = ensureDefaultList()
            val now = time.now()
            val inList = tasks.inList(id)
            val updated = if (moveTasksToDefault) {
                var position = tasks.maxPosition(fallback.id)
                inList.map { position += 1; it.copy(listId = fallback.id, position = position, modifiedUtc = now) }
            } else {
                inList.map { it.copy(deletedUtc = now, modifiedUtc = now) }
            }
            tasks.updateAll(updated)
            lists.update(list.copy(deletedUtc = now, modifiedUtc = now))
            affected += updated.map { it.id }
            if (!moveTasksToDefault) updated.forEach { reminders.cancelTask(it.id) }
        }
        changes.recorded(EntityType.TASK_LIST, id)
        affected.forEach { changes.recorded(EntityType.TASK, it) }
    }

    /** Optional first-run examples; only created when the user asks for them. */
    suspend fun addExamples() {
        val today = time.today()
        addTask("Buy groceries", view = SmartView.MY_DAY)
        addTask("Call family", view = SmartView.MY_DAY, important = true)
        addTask("Finish the online course", dueDate = today.plusDays(3))
        addTask("Water the plants", dueDate = today)
    }
}
