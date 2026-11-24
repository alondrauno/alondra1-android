package com.alondra1.app.data.notifications

import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.TaskEntity

/**
 * Plans local reminders. One pending reminder per task or event id (unique names), so edits replace and never
 * duplicate. Implementations must not need network or exact-alarm permission.
 */
interface ReminderScheduler {
    fun syncTask(task: TaskEntity)
    fun cancelTask(taskId: String)
    fun syncEvent(event: EventEntity)
    fun cancelEvent(eventId: String)
    fun cancelAll()
}

/** For tests and for builds where reminders are switched off. */
class RecordingReminderScheduler : ReminderScheduler {
    val scheduled = mutableMapOf<String, String>()
    override fun syncTask(task: TaskEntity) {
        val key = "task:${task.id}"
        if (task.reminderAt != null && !task.completed && task.deletedUtc == null) scheduled[key] = task.reminderAt.toString() else scheduled.remove(key)
    }
    override fun cancelTask(taskId: String) { scheduled.remove("task:$taskId") }
    override fun syncEvent(event: EventEntity) {
        val key = "event:${event.id}"
        if (event.reminderMinutes != null && event.deletedUtc == null) scheduled[key] = event.reminderMinutes.toString() else scheduled.remove(key)
    }
    override fun cancelEvent(eventId: String) { scheduled.remove("event:$eventId") }
    override fun cancelAll() { scheduled.clear() }
}
