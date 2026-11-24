package com.alondra1.app.data.notifications

import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.TaskEntity

/**
 * Plans local reminders: one pending job per task reminder (task id + reminder id) and per event, with unique names,
 * so edits replace and never duplicate. Delivery state stays on the device. Implementations must not need network or
 * exact-alarm permission.
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
        cancelTask(task.id)
        if (task.completed || task.deletedUtc != null) return
        com.alondra1.app.domain.TaskJson.reminders(task.remindersJson).forEach { scheduled["task:${task.id}:${it.id}"] = it.at.toString() }
    }
    override fun cancelTask(taskId: String) { scheduled.keys.removeAll { it.startsWith("task:$taskId:") } }
    override fun syncEvent(event: EventEntity) {
        val key = "event:${event.id}"
        if (event.reminderMinutes != null && event.deletedUtc == null) scheduled[key] = event.reminderMinutes.toString() else scheduled.remove(key)
    }
    override fun cancelEvent(eventId: String) { scheduled.remove("event:$eventId") }
    override fun cancelAll() { scheduled.clear() }
}
