package com.alondra1.app.data.repository

import androidx.room.withTransaction
import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.CompletionRecordEntity
import com.alondra1.app.data.local.ListGroupEntity
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.local.TaskListEntity
import com.alondra1.app.data.notifications.ReminderScheduler
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.data.sync.EntityType
import com.alondra1.app.domain.RecurrenceRule
import com.alondra1.app.domain.SmartView
import com.alondra1.app.domain.TaskCompletion
import com.alondra1.app.domain.TaskJson
import com.alondra1.app.domain.TaskReminder
import com.alondra1.app.domain.TaskRules
import com.alondra1.app.domain.TaskStep
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
    fun observeGroups(): Flow<List<ListGroupEntity>> = db.listGroups().observeActive()
    fun observeHistory(): Flow<List<CompletionRecordEntity>> = db.completions().observeAll()
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
        attachments: List<com.alondra1.app.domain.TaskAttachment> = emptyList(),
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
                attachmentsJson = com.alondra1.app.domain.TaskAttachments.add("[]", attachments),
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

    /**
     * Completes or reopens a task; returns it as it was before, for Undo. Completing appends one completion record in
     * the same transaction; a repeating task with a due date moves to its next occurrence instead of being marked done
     * (TaskCompletion, Windows semantics), so a double tap cannot advance it twice: the second call sees the new state.
     */
    suspend fun setCompleted(id: String, completed: Boolean): TaskEntity? {
        val before = tasks.get(id) ?: return null
        if (before.deletedUtc != null) return before
        if (!completed) {
            if (before.completed) mutate(id) { it.copy(completed = false, completedUtc = null) }
            return before
        }
        if (before.completed) return before
        val saved = db.withTransaction {
            val current = tasks.get(id) ?: return@withTransaction null
            if (current.completed || current.deletedUtc != null) return@withTransaction null
            val now = time.now()
            val (updated, record) = TaskCompletion.complete(current, now, time.today())
            val next = updated.copy(modifiedUtc = now)
            tasks.update(next)
            db.completions().insert(CompletionRecordEntity(record.id, record.taskId, record.title, record.listId, record.completedUtc, record.occurrenceDate))
            next
        } ?: return before
        reminders.syncTask(saved)
        changes.recorded(EntityType.TASK, id)
        return before
    }

    // ------------------------------------------------------------------------------------------------ steps

    fun steps(task: TaskEntity): List<TaskStep> = TaskJson.steps(task.stepsJson)

    private suspend fun editSteps(taskId: String, change: (List<TaskStep>) -> List<TaskStep>) =
        mutate(taskId) { it.copy(stepsJson = TaskJson.stepsJson(change(TaskJson.steps(it.stepsJson)), it.stepsJson)) }

    suspend fun addStep(taskId: String, rawTitle: String) {
        val title = rawTitle.trim().take(2000).ifEmpty { return }
        editSteps(taskId) { it + TaskStep(Ids.newId(), title) }
    }

    suspend fun toggleStep(taskId: String, stepId: String) = editSteps(taskId) { steps ->
        steps.map { if (it.id == stepId) it.copy(completed = !it.completed, completedUtc = if (it.completed) null else time.now()) else it }
    }

    suspend fun renameStep(taskId: String, stepId: String, rawTitle: String) {
        val title = rawTitle.trim().take(2000).ifEmpty { return }
        editSteps(taskId) { steps -> steps.map { if (it.id == stepId) it.copy(title = title) else it } }
    }

    suspend fun deleteStep(taskId: String, stepId: String) = editSteps(taskId) { steps -> steps.filterNot { it.id == stepId } }

    suspend fun moveStep(taskId: String, stepId: String, delta: Int) = editSteps(taskId) { steps ->
        val i = steps.indexOfFirst { it.id == stepId }
        val j = i + delta
        if (i < 0 || j !in steps.indices) steps else steps.toMutableList().apply { add(j, removeAt(i)) }
    }

    // ------------------------------------------------------------------------------------------------ repeat, dates, reminders

    /**
     * Sets or clears the repeat rule. The series counts from its anchor (the due date, or today when the task has no
     * due date yet, which then becomes the due date), so month-end repeats do not drift.
     */
    suspend fun setRecurrence(taskId: String, rule: RecurrenceRule?) = mutate(taskId) { task ->
        if (rule == null) return@mutate task.copy(recurrenceJson = null)
        val due = task.dueDate ?: time.today()
        task.copy(dueDate = due, recurrenceJson = TaskJson.recurrenceJson(rule.copy(anchor = rule.anchor ?: due)))
    }

    suspend fun setStartDate(taskId: String, date: LocalDate?) = mutate(taskId) { it.copy(startDate = date) }

    fun reminders(task: TaskEntity): List<TaskReminder> = TaskJson.reminders(task.remindersJson)

    suspend fun addReminder(taskId: String, at: LocalDateTime) = mutate(taskId) { task ->
        val list = TaskJson.reminders(task.remindersJson)
        if (list.any { it.at == at }) task else task.copy(remindersJson = TaskJson.remindersJson((list + TaskReminder(Ids.newId(), at)).sortedBy { it.at }))
    }

    suspend fun removeReminder(taskId: String, reminderId: String) = mutate(taskId) { task ->
        task.copy(remindersJson = TaskJson.remindersJson(TaskJson.reminders(task.remindersJson).filterNot { it.id == reminderId }))
    }

    suspend fun history(taskId: String): List<CompletionRecordEntity> = db.completions().forTask(taskId)

    fun attachments(task: TaskEntity): List<com.alondra1.app.domain.TaskAttachment> = com.alondra1.app.domain.TaskAttachments.parse(task.attachmentsJson)

    /** Adds imported files to a task (metadata; the bytes are already in the task-file store). */
    suspend fun addAttachments(taskId: String, added: List<com.alondra1.app.domain.TaskAttachment>) =
        if (added.isEmpty()) tasks.get(taskId) else mutate(taskId) { it.copy(attachmentsJson = com.alondra1.app.domain.TaskAttachments.add(it.attachmentsJson, added)) }

    /** Removes an attachment from the task; its file is released later (Undo and other devices can still use it). */
    suspend fun removeAttachment(taskId: String, attachmentId: String) =
        mutate(taskId) { it.copy(attachmentsJson = com.alondra1.app.domain.TaskAttachments.remove(it.attachmentsJson, attachmentId)) }

    suspend fun setImportant(id: String, important: Boolean) = mutate(id) { it.copy(important = important) }

    suspend fun setMyDay(id: String, inMyDay: Boolean) = mutate(id) { it.copy(myDayDate = if (inMyDay) time.today() else null) }


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

    /**
     * Undo for complete/delete/edit: writes the earlier version back. A completion made since the snapshot that never
     * reached the cloud is removed too, so Undo leaves no history entry behind (one that already synced stays: it was
     * a real completion other devices have seen).
     */
    suspend fun restore(snapshot: TaskEntity) {
        val restored = snapshot.copy(modifiedUtc = time.now())
        db.completions().forTask(snapshot.id).filter { !it.sent && it.completedUtc.isAfter(snapshot.modifiedUtc) }.forEach { db.completions().delete(it.id) }
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

    // ------------------------------------------------------------------------------------------------ list groups

    suspend fun createGroup(rawName: String): ListGroupEntity {
        val name = rawName.trim().take(200).ifEmpty { throw IllegalArgumentException("A group needs a name") }
        val now = time.now()
        val group = ListGroupEntity(id = Ids.newId(), name = name, position = db.listGroups().maxPosition() + 1, createdUtc = now, modifiedUtc = now)
        db.listGroups().put(group)
        changes.recorded(EntityType.LIST_GROUP, group.id)
        return group
    }

    suspend fun renameGroup(id: String, rawName: String) {
        val name = rawName.trim().take(200).ifEmpty { return }
        val group = db.listGroups().get(id) ?: return
        db.listGroups().put(group.copy(name = name, modifiedUtc = time.now()))
        changes.recorded(EntityType.LIST_GROUP, id)
    }

    /** Removing a group ungroups its lists; lists and tasks are never deleted with it. */
    suspend fun deleteGroup(id: String) {
        val ungrouped = mutableListOf<String>()
        db.withTransaction {
            val group = db.listGroups().get(id) ?: return@withTransaction
            val now = time.now()
            lists.active().filter { it.groupId == id }.forEach { lists.update(it.copy(groupId = null, modifiedUtc = now)); ungrouped += it.id }
            db.listGroups().put(group.copy(deletedUtc = now, modifiedUtc = now))
        }
        changes.recorded(EntityType.LIST_GROUP, id)
        ungrouped.forEach { changes.recorded(EntityType.TASK_LIST, it) }
    }

    suspend fun setListGroup(listId: String, groupId: String?) {
        val list = lists.get(listId) ?: return
        if (groupId != null && db.listGroups().get(groupId)?.deletedUtc != null) return
        lists.update(list.copy(groupId = groupId, modifiedUtc = time.now()))
        changes.recorded(EntityType.TASK_LIST, listId)
    }

    /** Collapsed or expanded in the drawer: a device-local preference, never synced. */
    suspend fun setGroupCollapsed(id: String, collapsed: Boolean) {
        db.listGroups().get(id)?.let { db.listGroups().put(it.copy(collapsed = collapsed)) }
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
