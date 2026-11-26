package com.alondra1.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface TaskListDao {
    @Query("SELECT * FROM task_lists WHERE deletedUtc IS NULL ORDER BY isDefault DESC, position, name")
    fun observeActive(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists WHERE deletedUtc IS NULL ORDER BY isDefault DESC, position, name")
    suspend fun active(): List<TaskListEntity>

    @Query("SELECT * FROM task_lists WHERE id = :id")
    suspend fun get(id: String): TaskListEntity?

    @Query("SELECT * FROM task_lists WHERE isDefault = 1 AND deletedUtc IS NULL LIMIT 1")
    suspend fun defaultList(): TaskListEntity?

    @Query("SELECT COALESCE(MAX(position), 0) FROM task_lists")
    suspend fun maxPosition(): Double

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: TaskListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(list: TaskListEntity)

    @Query("SELECT * FROM task_lists")
    suspend fun allIncludingDeleted(): List<TaskListEntity>

    @Update
    suspend fun update(list: TaskListEntity)
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE deletedUtc IS NULL")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE deletedUtc IS NULL")
    suspend fun all(): List<TaskEntity>

    /** Live and deleted (tombstoned) tasks: a deleted task can still be restored with Undo, its files must stay. */
    @Query("SELECT * FROM tasks")
    suspend fun allIncludingDeleted(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE listId = :listId AND deletedUtc IS NULL")
    suspend fun inList(listId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE deletedUtc IS NULL AND completed = 0 AND remindersJson != '[]'")
    suspend fun withReminders(): List<TaskEntity>

    @Query("SELECT COALESCE(MAX(position), 0) FROM tasks WHERE listId = :listId")
    suspend fun maxPosition(listId: String): Double

    @Query(
        "SELECT * FROM tasks WHERE deletedUtc IS NULL AND (title LIKE :pattern ESCAPE '\\' OR notes LIKE :pattern ESCAPE '\\') " +
            "ORDER BY completed, modifiedUtc DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, limit: Int): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Update
    suspend fun updateAll(tasks: List<TaskEntity>)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedUtc IS NULL")
    suspend fun all(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE deletedUtc IS NULL ORDER BY pinned DESC, modifiedUtc DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query(
        "SELECT * FROM notes WHERE deletedUtc IS NULL AND (title LIKE :pattern ESCAPE '\\' OR body LIKE :pattern ESCAPE '\\') " +
            "ORDER BY pinned DESC, modifiedUtc DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, limit: Int): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    /** List rows without documents (fast with many large notes). */
    @Query(
        "SELECT id, title, substr(body, 1, 400) AS preview, pinned, favorite, folderId, archivedUtc, trashedUtc, createdUtc, modifiedUtc, assetsJson " +
            "FROM notes WHERE deletedUtc IS NULL",
    )
    fun observeSummaries(): Flow<List<NoteSummary>>

    /** Full-text search (FTS4, prefix match on every word: "word*"); [match] is built by NotesRepository.ftsQuery. */
    @Query("SELECT notes.id FROM notes JOIN notes_fts ON notes.rowid = notes_fts.rowid WHERE notes_fts MATCH :match AND notes.deletedUtc IS NULL LIMIT :limit")
    suspend fun searchIds(match: String, limit: Int): List<String>

    /** Candidates for notes linking to [id] (the caller checks the decoded links). */
    @Query("SELECT * FROM notes WHERE deletedUtc IS NULL AND trashedUtc IS NULL AND linksJson LIKE '%' || :id || '%' ORDER BY modifiedUtc DESC")
    suspend fun linkingTo(id: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deletedUtc IS NULL AND trashedUtc IS NOT NULL AND trashedUtc < :before")
    suspend fun trashedBefore(before: java.time.Instant): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deletedUtc IS NULL AND folderId = :folderId")
    suspend fun inFolder(folderId: String): List<NoteEntity>
}

data class NoteSummary(
    val id: String,
    val title: String,
    val preview: String,
    val pinned: Boolean,
    val favorite: Boolean,
    val folderId: String?,
    val archivedUtc: java.time.Instant?,
    val trashedUtc: java.time.Instant?,
    val createdUtc: java.time.Instant,
    val modifiedUtc: java.time.Instant,
    val assetsJson: String,
)

@Dao
interface NoteFolderDao {
    @Query("SELECT * FROM note_folders WHERE deletedUtc IS NULL ORDER BY position, name COLLATE NOCASE")
    fun observeActive(): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM note_folders WHERE deletedUtc IS NULL")
    suspend fun active(): List<NoteFolderEntity>

    @Query("SELECT * FROM note_folders WHERE id = :id")
    suspend fun get(id: String): NoteFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(folder: NoteFolderEntity)
}

@Dao
interface TaskFileDao {
    @Query("SELECT * FROM task_files WHERE sha256 = :sha")
    suspend fun get(sha: String): TaskFileEntity?

    @Query("SELECT * FROM task_files WHERE sha256 IN (:shas)")
    fun observe(shas: List<String>): Flow<List<TaskFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(file: TaskFileEntity)

    @Query("UPDATE task_files SET uploaded = 1, lastError = NULL WHERE sha256 = :sha")
    suspend fun markUploaded(sha: String)

    @Query("UPDATE task_files SET lastError = :error WHERE sha256 = :sha")
    suspend fun markFailed(sha: String, error: String)

    @Query("UPDATE task_files SET lastError = NULL WHERE sha256 = :sha")
    suspend fun clearError(sha: String)

    @Query("SELECT * FROM task_files")
    suspend fun all(): List<TaskFileEntity>

    @Query("DELETE FROM task_files WHERE sha256 = :sha")
    suspend fun delete(sha: String)

    @Query("DELETE FROM task_files")
    suspend fun clear()
}

@Dao
interface NoteAssetDao {
    @Query("SELECT * FROM note_assets WHERE sha256 = :sha")
    suspend fun get(sha: String): NoteAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(asset: NoteAssetEntity)

    @Query("SELECT * FROM note_assets WHERE present = 0 LIMIT :limit")
    suspend fun missing(limit: Int): List<NoteAssetEntity>

    @Query("UPDATE note_assets SET uploaded = 1 WHERE sha256 = :sha")
    suspend fun markUploaded(sha: String)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE deletedUtc IS NULL")
    suspend fun all(): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(event: EventEntity)

    @Query("SELECT * FROM events WHERE deletedUtc IS NULL ORDER BY allDay DESC, startDate, startUtc")
    fun observeActive(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    fun observe(id: String): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE deletedUtc IS NULL AND reminderMinutes IS NOT NULL")
    suspend fun withReminders(): List<EventEntity>

    @Query(
        "SELECT * FROM events WHERE deletedUtc IS NULL AND (title LIKE :pattern ESCAPE '\\' OR description LIKE :pattern ESCAPE '\\') " +
            "ORDER BY startDate, startUtc LIMIT :limit",
    )
    suspend fun search(pattern: String, limit: Int): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EventEntity)

    @Update
    suspend fun update(event: EventEntity)
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM task_lists") suspend fun clearLists()
    @Query("DELETE FROM notes") suspend fun clearNotes()
    @Query("DELETE FROM events") suspend fun clearEvents()

    @Query("SELECT COUNT(*) FROM tasks WHERE deletedUtc IS NULL") suspend fun taskCount(): Int
    @Query("SELECT COUNT(*) FROM notes WHERE deletedUtc IS NULL") suspend fun noteCount(): Int
    @Query("SELECT COUNT(*) FROM events WHERE deletedUtc IS NULL") suspend fun eventCount(): Int

    /** Tombstones older than [before] that never left this device can be removed for good. */
    @Query("DELETE FROM tasks WHERE deletedUtc IS NOT NULL AND deletedUtc < :before AND serverVersion IS NULL")
    suspend fun purgeLocalTaskTombstones(before: Instant)
}

@Dao
interface ListGroupDao {
    @Query("SELECT * FROM list_groups WHERE deletedUtc IS NULL ORDER BY position, name")
    fun observeActive(): Flow<List<ListGroupEntity>>

    @Query("SELECT * FROM list_groups WHERE deletedUtc IS NULL ORDER BY position, name")
    suspend fun active(): List<ListGroupEntity>

    @Query("SELECT * FROM list_groups")
    suspend fun allIncludingDeleted(): List<ListGroupEntity>

    @Query("SELECT * FROM list_groups WHERE id = :id")
    suspend fun get(id: String): ListGroupEntity?

    @Query("SELECT COALESCE(MAX(position), 0) FROM list_groups")
    suspend fun maxPosition(): Double

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(group: ListGroupEntity)
}

@Dao
interface CompletionDao {
    @Query("SELECT * FROM completion_records ORDER BY completedUtc DESC")
    fun observeAll(): Flow<List<CompletionRecordEntity>>

    @Query("SELECT * FROM completion_records WHERE taskId = :taskId ORDER BY completedUtc DESC")
    suspend fun forTask(taskId: String): List<CompletionRecordEntity>

    /** The oldest completion of the task not yet known to the cloud (sent with the task's next push). */
    @Query("SELECT * FROM completion_records WHERE taskId = :taskId AND sent = 0 ORDER BY completedUtc LIMIT 1")
    suspend fun oldestUnsent(taskId: String): CompletionRecordEntity?

    @Query("SELECT COUNT(*) FROM completion_records WHERE taskId = :taskId AND sent = 0")
    suspend fun unsentCount(taskId: String): Int

    @Query("UPDATE completion_records SET sent = 1 WHERE id = :id")
    suspend fun markSent(id: String)

    @Query("DELETE FROM completion_records WHERE id = :id")
    suspend fun delete(id: String)

    /** Received from the cloud: insert or keep (ids are global, a record never changes). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: CompletionRecordEntity)
}
