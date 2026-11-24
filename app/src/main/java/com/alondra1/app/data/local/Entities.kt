package com.alondra1.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Field names and meanings follow the shared task contract (todo-v1): date-only due dates, local wall-clock
 * times without offset, UTC instants for timestamps. `remoteJson` keeps fields another client wrote that this
 * app does not edit yet (recurrence, steps, attachments) so they survive a round trip through Cloud Sync.
 */
@Entity(tableName = "task_lists", indices = [Index("position"), Index("deletedUtc")])
data class TaskListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String? = null,
    val position: Double,
    val isDefault: Boolean = false,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val remoteJson: String? = null,
    val serverVersion: Long? = null,
)

@Entity(
    tableName = "tasks",
    indices = [Index("listId"), Index("dueDate"), Index("completed"), Index("myDayDate"), Index("deletedUtc")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val title: String,
    val notes: String = "",
    val completed: Boolean = false,
    val important: Boolean = false,
    val position: Double,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val completedUtc: Instant? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val myDayDate: LocalDate? = null,
    /** One reminder in Phase 1 (contract: reminders[0].at), local wall-clock time. */
    val reminderAt: LocalDateTime? = null,
    /** Device-local link back to the note a task was created from. */
    val sourceNoteId: String? = null,
    val deletedUtc: Instant? = null,
    val remoteJson: String? = null,
    val serverVersion: Long? = null,
)

/**
 * A note. [docJson] is the shared rich-text document (note-document-v1, edited by the shared notes editor in alondra1-backend contract/notes-editor); [body] is
 * its plain text for search, previews and older clients. [trashedUtc] = in Trash (restorable); [deletedUtc] = gone
 * for good (a tombstone kept for sync).
 */
@Entity(tableName = "notes", indices = [Index("modifiedUtc"), Index("deletedUtc"), Index("folderId")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",
    val pinned: Boolean = false,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val serverVersion: Long? = null,
    val docJson: String? = null,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val archivedUtc: Instant? = null,
    val trashedUtc: Instant? = null,
    /** JSON array of picture ids (SHA-256) the document references. */
    val assetsJson: String = "[]",
    /** JSON array of links to events, contacts, mail and tasks. */
    val linksJson: String = "[]",
    /** Last server copy, so fields this app does not edit are kept when it sends a change. */
    val remoteJson: String? = null,
)

/** Full-text index over notes (title and plain text), kept in step by Room. */
@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "notes_fts")
data class NoteFts(val title: String, val body: String)

@Entity(tableName = "note_folders", indices = [Index("deletedUtc")])
data class NoteFolderEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val position: Double = 0.0,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val serverVersion: Long? = null,
    val remoteJson: String? = null,
)

/** A picture used in notes, stored once by content hash in files/notes-assets. present=false: still to download. */
@Entity(tableName = "note_assets")
data class NoteAssetEntity(
    @PrimaryKey val sha256: String,
    val mimeType: String,
    val bytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val createdUtc: Instant,
    val uploaded: Boolean = false,
    val present: Boolean = true,
)

/**
 * Timed events store UTC instants plus the zone they were created in; all-day events store dates only
 * (endDate inclusive) so they never shift across time zones.
 */
@Entity(tableName = "events", indices = [Index("startUtc"), Index("startDate"), Index("deletedUtc")])
data class EventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val allDay: Boolean,
    val startUtc: Instant? = null,
    val endUtc: Instant? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    @ColumnInfo(name = "timeZoneId") val timeZoneId: String,
    /** Minutes before the start (timed) or before 09:00 on the start date (all-day). Null = no reminder. */
    val reminderMinutes: Int? = null,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val serverVersion: Long? = null,
)
