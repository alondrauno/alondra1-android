package com.alondra1.app.domain

import com.alondra1.app.data.local.TaskEntity
import java.time.ZonedDateTime

enum class SmartView(val title: String) {
    MY_DAY("My Day"),
    IMPORTANT("Important"),
    PLANNED("Planned"),
    UPCOMING("Upcoming"),
    OVERDUE("Overdue"),
    ALL("All Tasks"),
    COMPLETED("Completed"),
    UNSCHEDULED("Unscheduled"),
}

enum class TaskSort(val label: String) { DUE_DATE("Due date"), IMPORTANCE("Importance"), CREATED("Created"), TITLE("Title") }

enum class StatusFilter(val label: String) { OPEN("Open"), ALL("All"), COMPLETED("Completed") }

/**
 * Smart-view semantics (documented in README → To-do rules, same as the Windows app):
 * - My Day: open tasks whose My Day date is today. Explicit only: nothing is added automatically and the view
 *   starts empty each day.
 * - Important: open, starred.            - Planned: open, with a due date.
 * - Upcoming: open, due after today.     - Unscheduled: open, without a due date.
 * - Overdue: open, due before today, or due today at a time that has passed. A date-only task becomes overdue
 *   when its local due date has ended.
 * - All Tasks: everything not deleted, filtered by status.   - Completed: completed, newest first.
 */
object TaskRules {
    fun isOverdue(task: TaskEntity, now: ZonedDateTime): Boolean {
        if (task.completed) return false
        val due = task.dueDate ?: return false
        val today = now.toLocalDate()
        if (due.isBefore(today)) return true
        val time = task.dueTime
        return due == today && time != null && time.isBefore(now.toLocalTime())
    }

    fun matches(view: SmartView, task: TaskEntity, now: ZonedDateTime, status: StatusFilter = StatusFilter.OPEN): Boolean {
        if (task.deletedUtc != null) return false
        val today = now.toLocalDate()
        return when (view) {
            SmartView.MY_DAY -> !task.completed && task.myDayDate == today
            SmartView.IMPORTANT -> !task.completed && task.important
            SmartView.PLANNED -> !task.completed && task.dueDate != null
            SmartView.UPCOMING -> !task.completed && task.dueDate?.isAfter(today) == true
            SmartView.OVERDUE -> isOverdue(task, now)
            SmartView.UNSCHEDULED -> !task.completed && task.dueDate == null
            SmartView.COMPLETED -> task.completed
            SmartView.ALL -> when (status) {
                StatusFilter.OPEN -> !task.completed
                StatusFilter.COMPLETED -> task.completed
                StatusFilter.ALL -> true
            }
        }
    }

    fun inList(task: TaskEntity, listId: String, status: StatusFilter): Boolean =
        task.deletedUtc == null && task.listId == listId && when (status) {
            StatusFilter.OPEN -> !task.completed
            StatusFilter.COMPLETED -> task.completed
            StatusFilter.ALL -> true
        }

    fun select(view: SmartView, tasks: List<TaskEntity>, now: ZonedDateTime, sort: TaskSort, status: StatusFilter = StatusFilter.OPEN): List<TaskEntity> {
        val selected = tasks.filter { matches(view, it, now, status) }
        return if (view == SmartView.COMPLETED) selected.sortedByDescending { it.completedUtc } else sort(selected, sort)
    }

    fun sort(tasks: List<TaskEntity>, sort: TaskSort): List<TaskEntity> {
        val open = compareBy<TaskEntity> { it.completed }
        val comparator = when (sort) {
            TaskSort.DUE_DATE -> open.thenBy(nullsLast()) { it.dueDate }.thenBy(nullsLast()) { it.dueTime }.thenBy { it.createdUtc }
            TaskSort.IMPORTANCE -> open.thenByDescending { it.important }.thenBy(nullsLast()) { it.dueDate }.thenBy { it.createdUtc }
            TaskSort.CREATED -> open.thenByDescending { it.createdUtc }
            TaskSort.TITLE -> open.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        }
        return tasks.sortedWith(comparator.thenBy { it.id })
    }

    /** Counts shown next to each destination; computed from the same rules as the lists, so they always agree. */
    fun counts(tasks: List<TaskEntity>, now: ZonedDateTime): Map<SmartView, Int> =
        SmartView.entries.associateWith { view -> tasks.count { matches(view, it, now) } }

    fun listCounts(tasks: List<TaskEntity>): Map<String, Int> =
        tasks.filter { it.deletedUtc == null && !it.completed }.groupingBy { it.listId }.eachCount()

    const val TITLE_MAX = 500

    /** Trimmed title, or null when invalid (empty or longer than 500 characters). */
    fun cleanTitle(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= TITLE_MAX }
}
