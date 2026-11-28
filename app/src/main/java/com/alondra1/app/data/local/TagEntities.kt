package com.alondra1.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** A tag (contract tags-v1). [nameKey] is the case-folded name used for uniqueness; deletions are tombstones until synced. */
@Entity(tableName = "tags", indices = [Index("deletedUtc"), Index("nameKey")])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val color: String? = null,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val serverVersion: Long? = null,
    val remoteJson: String? = null,
)

/**
 * One tag on one item (contract tag-links-v1). Unassigning sets [removed] (the link comes back with the same id);
 * [deletedUtc] is set only when the tag or the item is deleted (the cloud deletes the link then too).
 */
@Entity(tableName = "tag_links", indices = [Index("tagId"), Index(value = ["objectType", "objectRef"]), Index("deletedUtc")])
data class TagLinkEntity(
    @PrimaryKey val id: String,
    val tagId: String,
    val objectType: String,
    val objectRef: String,
    val removed: Boolean = false,
    val createdUtc: Instant,
    val modifiedUtc: Instant,
    val deletedUtc: Instant? = null,
    val serverVersion: Long? = null,
    val remoteJson: String? = null,
)

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE deletedUtc IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE deletedUtc IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun active(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun get(id: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(tag: TagEntity)

    /** Assigned links (not removed, not deleted). */
    @Query("SELECT * FROM tag_links WHERE deletedUtc IS NULL AND removed = 0")
    fun observeAssigned(): Flow<List<TagLinkEntity>>

    @Query("SELECT * FROM tag_links WHERE deletedUtc IS NULL AND removed = 0")
    suspend fun assigned(): List<TagLinkEntity>

    @Query("SELECT * FROM tag_links WHERE deletedUtc IS NULL AND removed = 0 AND tagId = :tagId")
    suspend fun assignedOf(tagId: String): List<TagLinkEntity>

    @Query("SELECT COUNT(*) FROM tag_links WHERE deletedUtc IS NULL AND removed = 0 AND objectType = :type AND objectRef = :ref")
    suspend fun countOn(type: String, ref: String): Int

    @Query("SELECT * FROM tag_links WHERE id = :id")
    suspend fun link(id: String): TagLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLink(link: TagLinkEntity)

    @Query("SELECT * FROM tag_links WHERE deletedUtc IS NULL AND tagId = :tagId")
    suspend fun linksOf(tagId: String): List<TagLinkEntity>

    @Query("DELETE FROM tags") suspend fun clearTags()
    @Query("DELETE FROM tag_links") suspend fun clearLinks()
}
