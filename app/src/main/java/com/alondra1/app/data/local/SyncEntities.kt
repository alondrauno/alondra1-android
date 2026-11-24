package com.alondra1.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One pending cloud change per item (version 2). The mutation id stays the same while the item is unchanged, so a
 * retried push is recognized by the server; it is renewed when the item changes after a send attempt, so an older
 * receipt can never hide a newer edit.
 */
@Entity(tableName = "sync_outbox", indices = [Index(value = ["entityType", "entityId"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entityType: String,
    val entityId: String,
    val mutationId: String,
    val attempted: Boolean = false,
    val createdUtc: Long,
)

/** Something the user should know about: a conflict copy was made, or the server rejected a change. */
@Entity(tableName = "sync_issues")
data class SyncIssueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val title: String,
    val message: String,
    val createdUtc: Long,
    /** For a note conflict: the conflict copy holding this device's version (compare and resolve it). */
    val copyId: String? = null,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_outbox ORDER BY seq")
    suspend fun outbox(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePending(): Flow<Int>

    @Query("SELECT * FROM sync_outbox WHERE entityType = :type AND entityId = :id")
    suspend fun find(type: String, id: String): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OutboxEntity)

    @Query("UPDATE sync_outbox SET attempted = 1 WHERE seq IN (:seqs)")
    suspend fun markAttempted(seqs: List<Long>)

    @Query("DELETE FROM sync_outbox WHERE entityType = :type AND entityId = :id AND mutationId = :mutationId")
    suspend fun complete(type: String, id: String, mutationId: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearOutbox()

    @Insert
    suspend fun addIssue(issue: SyncIssueEntity)

    @Query("SELECT * FROM sync_issues ORDER BY id DESC")
    fun observeIssues(): Flow<List<SyncIssueEntity>>

    @Query("DELETE FROM sync_issues WHERE id = :id")
    suspend fun dismissIssue(id: Long)

    @Query("SELECT * FROM sync_issues WHERE id = :id")
    suspend fun issue(id: Long): SyncIssueEntity?

    /** The unresolved conflict a note is part of (as the note or as its conflict copy), if any. */
    @Query("SELECT * FROM sync_issues WHERE entityType = 'note' AND copyId IS NOT NULL AND (entityId = :noteId OR copyId = :noteId) ORDER BY id DESC LIMIT 1")
    fun observeNoteConflict(noteId: String): Flow<SyncIssueEntity?>
}
