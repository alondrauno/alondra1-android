package com.alondra1.app.data.repository

import androidx.room.withTransaction
import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.TagEntity
import com.alondra1.app.data.local.TagLinkEntity
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.data.sync.EntityType
import com.alondra1.app.domain.TagException
import com.alondra1.app.domain.TagRules
import kotlinx.coroutines.flow.Flow

/**
 * Tags and their links (FEATURES_EXTENSION Part B §2), with the rules of the cloud, Windows and the Web app: unique
 * names ignoring case, at most 500 tags and 50 per item. Deleting a tag deletes its links here (the cloud does that
 * itself, so only the tag is sent); tagged items are never deleted. A link to a deleted item is simply not shown (the
 * cloud deletes it with the item; Undo of the delete brings it back here).
 */
class TagRepository(
    private val db: AppDatabase,
    private val time: TimeSource,
    private val changes: ChangeRecorder = ChangeRecorder.LocalOnly,
) {
    private val tags = db.tags()

    fun observeTags(): Flow<List<TagEntity>> = tags.observeActive()
    fun observeLinks(): Flow<List<TagLinkEntity>> = tags.observeAssigned()
    suspend fun tags(): List<TagEntity> = tags.active()
    suspend fun links(): List<TagLinkEntity> = tags.assigned()

    suspend fun create(rawName: String, color: String? = null): TagEntity {
        val name = TagRules.cleanName(rawName)
        checkColor(color)
        val all = tags.active()
        all.firstOrNull { it.nameKey == TagRules.nameKey(name) }?.let { throw TagException("You already have a tag named “${it.name}”.") }
        if (all.size >= TagRules.MAX_TAGS) throw TagException("You can have at most ${TagRules.MAX_TAGS} tags.")
        val now = time.now()
        val tag = TagEntity(id = Ids.newId(), name = name, nameKey = TagRules.nameKey(name), color = color, createdUtc = now, modifiedUtc = now)
        tags.put(tag)
        changes.recorded(EntityType.TAG, tag.id)
        return tag
    }

    /** An existing tag with this name (ignoring case), or a new one. */
    suspend fun findOrCreate(rawName: String): TagEntity {
        val key = TagRules.nameKey(TagRules.cleanName(rawName))
        return tags.active().firstOrNull { it.nameKey == key } ?: create(rawName)
    }

    suspend fun rename(id: String, rawName: String) {
        val name = TagRules.cleanName(rawName)
        val tag = tags.get(id)?.takeIf { it.deletedUtc == null } ?: return
        if (tag.name == name) return
        tags.active().firstOrNull { it.id != id && it.nameKey == TagRules.nameKey(name) }?.let { throw TagException("You already have a tag named “${it.name}”.") }
        tags.put(tag.copy(name = name, nameKey = TagRules.nameKey(name), modifiedUtc = time.now()))
        changes.recorded(EntityType.TAG, id)
    }

    suspend fun setColor(id: String, color: String?) {
        checkColor(color)
        val tag = tags.get(id)?.takeIf { it.deletedUtc == null } ?: return
        if (tag.color == color) return
        tags.put(tag.copy(color = color, modifiedUtc = time.now()))
        changes.recorded(EntityType.TAG, id)
    }

    /** Deletes the tag and, here, its links; the items keep everything else. */
    suspend fun delete(id: String) {
        val now = time.now()
        val tag = tags.get(id)?.takeIf { it.deletedUtc == null } ?: return
        db.withTransaction {
            tags.linksOf(id).forEach { tags.putLink(it.copy(deletedUtc = now)) }
            tags.put(tag.copy(deletedUtc = now, modifiedUtc = now))
        }
        changes.recorded(EntityType.TAG, id)
    }

    /** Adds or removes one tag on several items of one type. */
    suspend fun setTag(type: String, refs: List<String>, tagId: String, on: Boolean) {
        tags.get(tagId)?.takeIf { it.deletedUtc == null } ?: throw TagException("This tag no longer exists.")
        val now = time.now()
        val changed = mutableListOf<String>()
        db.withTransaction {
            for (ref in refs) {
                val id = TagRules.linkId(tagId, type, ref)
                val current = tags.link(id)
                val alive = current != null && current.deletedUtc == null
                if (alive && !current!!.removed == on) continue
                if (!alive && !on) continue
                if (on && tags.countOn(type, ref) >= TagRules.MAX_PER_ITEM) throw TagException("An item can have at most ${TagRules.MAX_PER_ITEM} tags.")
                tags.putLink(
                    if (alive) current!!.copy(removed = !on, modifiedUtc = now)
                    // New, or a link whose tag or item was deleted before: starts over as a new record.
                    else TagLinkEntity(id = id, tagId = tagId, objectType = type, objectRef = ref, createdUtc = now, modifiedUtc = now)
                )
                changed += id
            }
        }
        changed.forEach { changes.recorded(EntityType.TAG_LINK, it) }
    }

    /** Sets exactly these tags on one item. */
    suspend fun setTags(type: String, ref: String, tagIds: Collection<String>) {
        val current = tags.assigned().filter { it.objectType == type && it.objectRef == ref }.map { it.tagId }.toSet()
        (current - tagIds.toSet()).forEach { setTag(type, listOf(ref), it, false) }
        (tagIds.toSet() - current).forEach { setTag(type, listOf(ref), it, true) }
    }

    private fun checkColor(color: String?) {
        if (color != null && color !in TagRules.COLORS) throw TagException("Choose one of the tag colors.")
    }
}
