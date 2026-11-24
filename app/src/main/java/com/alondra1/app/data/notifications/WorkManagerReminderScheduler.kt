package com.alondra1.app.data.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alondra1.app.Alondra1App
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.domain.EventRules
import com.alondra1.app.domain.NoteRules
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/**
 * Reminders as unique one-time WorkManager jobs ("reminder-task-<id>", "reminder-event-<id>"): an edit replaces
 * the pending job, so there are never duplicates. WorkManager keeps jobs across reboots; delivery is
 * approximate (no exact-alarm permission). Reminder times are local wall-clock values, so jobs are re-planned
 * when the time zone or the clock changes ([TimeChangeReceiver]).
 */
class WorkManagerReminderScheduler(private val context: Context, private val time: TimeSource) : ReminderScheduler {
    private val work get() = WorkManager.getInstance(context)

    override fun syncTask(task: TaskEntity) {
        val at = task.reminderAt
        if (at == null || task.completed || task.deletedUtc != null) return cancelTask(task.id)
        enqueue(taskWork(task.id), at, KIND_TASK, task.id)
    }

    override fun cancelTask(taskId: String) { work.cancelUniqueWork(taskWork(taskId)) }

    override fun syncEvent(event: EventEntity) {
        val at = EventRules.reminderTime(event, time.zone())
        if (at == null || event.deletedUtc != null) return cancelEvent(event.id)
        enqueue(eventWork(event.id), at, KIND_EVENT, event.id)
    }

    override fun cancelEvent(eventId: String) { work.cancelUniqueWork(eventWork(eventId)) }

    override fun cancelAll() { work.cancelAllWorkByTag(TAG) }

    private fun enqueue(name: String, at: LocalDateTime, kind: String, id: String) {
        val fireAt = at.atZone(time.zone()).toInstant()
        val delay = Duration.between(time.now(), fireAt)
        if (delay.isNegative && delay.abs() > Duration.ofMinutes(1)) {
            // A reminder in the past is not shown late; it is simply not scheduled.
            work.cancelUniqueWork(name)
            return
        }
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_KIND to kind, KEY_ID to id, KEY_AT to at.toString()))
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val TAG = "alondra1-reminder"
        const val KIND_TASK = "task"
        const val KIND_EVENT = "event"
        const val KEY_KIND = "kind"
        const val KEY_ID = "id"
        const val KEY_AT = "at"
        fun taskWork(id: String) = "reminder-task-$id"
        fun eventWork(id: String) = "reminder-event-$id"
    }
}

/** Reads the current record when it fires, so a changed or deleted item never shows a stale reminder. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as Alondra1App).container
        val id = inputData.getString(WorkManagerReminderScheduler.KEY_ID) ?: return Result.success()
        val at = inputData.getString(WorkManagerReminderScheduler.KEY_AT)
        val private = container.settings.settings.first().privateNotifications
        when (inputData.getString(WorkManagerReminderScheduler.KEY_KIND)) {
            WorkManagerReminderScheduler.KIND_TASK -> {
                val task = container.db.tasks().get(id) ?: return Result.success()
                if (task.completed || task.deletedUtc != null || task.reminderAt?.toString() != at) return Result.success()
                Notifications.show(applicationContext, Notifications.CHANNEL_TASKS, id.hashCode(), task.title,
                    task.dueDate?.let { "Due " + it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) },
                    "alondra1://task/$id", private)
            }
            WorkManagerReminderScheduler.KIND_EVENT -> {
                val event = container.db.events().get(id) ?: return Result.success()
                if (event.deletedUtc != null) return Result.success()
                val start = if (event.allDay) "All day" else event.startUtc?.atZone(container.time.zone())
                    ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                Notifications.show(applicationContext, Notifications.CHANNEL_EVENTS, ("e$id").hashCode(), event.title.ifBlank { NoteRules.displayTitle(event.title, event.description) },
                    start, "alondra1://event/$id", private)
            }
        }
        return Result.success()
    }
}
