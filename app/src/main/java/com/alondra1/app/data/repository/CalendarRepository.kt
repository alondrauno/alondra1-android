package com.alondra1.app.data.repository

import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.notifications.ReminderScheduler
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.data.sync.EntityType
import com.alondra1.app.domain.EventError
import com.alondra1.app.domain.EventRules
import kotlinx.coroutines.flow.Flow

class InvalidEventException(val error: EventError) : IllegalArgumentException(error.message)

/** Local Alondra1 events. No system calendar access and no calendar permission. */
class CalendarRepository(
    private val db: AppDatabase,
    private val time: TimeSource,
    private val reminders: ReminderScheduler,
    private val changes: ChangeRecorder = ChangeRecorder.LocalOnly,
) {
    private val events = db.events()

    fun observeEvents(): Flow<List<EventEntity>> = events.observeActive()
    fun observeEvent(id: String): Flow<EventEntity?> = events.observe(id)
    suspend fun getEvent(id: String): EventEntity? = events.get(id)

    /** Creates or updates; [draft] with an unknown id is created. Validation runs here, not only in the UI. */
    suspend fun save(draft: EventEntity): EventEntity {
        val title = draft.title.trim()
        EventRules.validate(title, draft.allDay, draft.startDate, draft.endDate, draft.startUtc, draft.endUtc)
            ?.let { throw InvalidEventException(it) }
        val now = time.now()
        val normalized = if (draft.allDay) {
            draft.copy(title = title, startUtc = null, endUtc = null)
        } else {
            draft.copy(title = title, startDate = null, endDate = null)
        }
        val existing = events.get(draft.id)
        val saved = if (existing == null) {
            normalized.copy(id = draft.id.ifBlank { Ids.newId() }, createdUtc = now, modifiedUtc = now).also { events.insert(it) }
        } else {
            normalized.copy(createdUtc = existing.createdUtc, modifiedUtc = now, serverVersion = existing.serverVersion)
                .also { events.update(it) }
        }
        reminders.syncEvent(saved)
        changes.recorded(EntityType.EVENT, saved.id)
        return saved
    }

    suspend fun delete(id: String): EventEntity? {
        val event = events.get(id) ?: return null
        events.update(event.copy(deletedUtc = time.now(), modifiedUtc = time.now()))
        reminders.cancelEvent(id)
        changes.recorded(EntityType.EVENT, id)
        return event
    }

    suspend fun restore(snapshot: EventEntity) {
        val restored = snapshot.copy(modifiedUtc = time.now())
        events.update(restored)
        reminders.syncEvent(restored)
        changes.recorded(EntityType.EVENT, snapshot.id)
    }
}
