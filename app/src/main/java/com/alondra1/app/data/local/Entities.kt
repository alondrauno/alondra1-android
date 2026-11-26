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
 * times without offset, UTC instants for timestamps. Steps, the repeat rule and reminders are stored as contract JSON
 * (domain/TaskModel.kt). `remoteJson` keeps fields another client wrote that this app does not edit (attachments, and
 * unknown fields) so they survive a round trip through Cloud Sync.
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
    /** List group (contract: groupId); null = not grouped. */
    val groupId: String? = null,
)

/** A group of lists (contract ListGroup). [collapsed] is a device-local presentation preference, never synced. */
@Entity(tableName = "list_groups", indices = [Index("deletedUtc")])
data class ListGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Double,
    val collapsed: Boolean = false,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val serverVersion: Long? = null,
    val remoteJson: String? = null,
)

/**
 * One completion of a task (contract CompletionRecord), appended when a task is completed; for a repeating task one
 * per occurrence. [sent] = known to the cloud (sent with the completing task change, or received from it).
 */
@Entity(tableName = "completion_records", indices = [Index("taskId"), Index("sent")])
data class CompletionRecordEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val listId: String,
    val completedUtc: Instant,
    val occurrenceDate: LocalDate? = null,
    val sent: Boolean = false,
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
    /** Legacy single reminder of schema 4; migrated into [remindersJson] (schema 5) and no longer written. */
    val reminderAt: LocalDateTime? = null,
    /** Device-local link back to the note a task was created from. */
    val sourceNoteId: String? = null,
    val deletedUtc: Instant? = null,
    val remoteJson: String? = null,
    val serverVersion: Long? = null,
    /** Contract startDate (date only). */
    val startDate: LocalDate? = null,
    /** Contract RecurrenceRule as JSON; null = does not repeat. */
    val recurrenceJson: String? = null,
    /** Contract subtasks (steps) as a JSON array, in order. */
    val stepsJson: String = "[]",
    /** Contract reminders (definitions: id and local wall-clock time) as a JSON array. */
    val remindersJson: String = "[]",
    /**
     * Contract attachments (metadata only, domain/TaskAttachments.kt) as a JSON array. Entries keep keys other clients
     * wrote (Windows' relativePath). The bytes live in [TaskFileEntity] files, never here.
     */
    val attachmentsJson: String = "[]",
)

/**
 * A file attached to a task, stored once per SHA-256 below files/task-files (app-private). [uploaded] = known to be in
 * cloud storage; [present] = the bytes are on this device (false for a file added on another device and not
 * downloaded yet, or removed from this device to free space). [lastError]: why the last upload failed for good.
 */
@Entity(tableName = "task_files")
data class TaskFileEntity(
    @PrimaryKey val sha256: String,
    val mimeType: String,
    val bytes: Long,
    val createdUtc: Instant,
    val uploaded: Boolean = false,
    val present: Boolean = true,
    val lastError: String? = null,
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
