package com.alondra1.app.data.repository

import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.NoteEntity
import com.alondra1.app.data.local.NoteFolderEntity
import com.alondra1.app.data.local.NoteSummary
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.data.sync.EntityType
import com.alondra1.app.domain.MeetingNotes
import com.alondra1.app.domain.NoteConflictChoice
import com.alondra1.app.domain.NoteMerge
import com.alondra1.app.domain.NoteRules
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration

class NoteStorageException(message: String) : Exception(message)

enum class NoteView { ALL, FAVORITES, FOLDER, ARCHIVE, TRASH }
enum class NoteSort { MODIFIED, CREATED, TITLE }

/** A link from a note to an event, contact, mail message or task (same shape as the Windows app and the server). */
@Serializable
data class NoteLink(val type: String, val id: String, val label: String? = null, val account: String? = null) {
    companion object {
        const val EVENT = "event"
        const val CONTACT = "contact"
        const val MAIL = "mail"
        const val TASK = "task"
    }
}

/**
 * Local notes (Notes spec): rich documents in the shared JSON format, folders, favorites, pinning, archive and a
 * Trash with retention. Works fully offline; when Cloud Sync is on each change is queued by [changes]. Empty drafts
 * are never stored.
 */
class NotesRepository(
    private val db: AppDatabase,
    private val time: TimeSource,
    private val changes: ChangeRecorder = ChangeRecorder.LocalOnly,
) {
    private val notes = db.notes()
    private val folders = db.noteFolders()

    fun observeNotes(): Flow<List<NoteEntity>> = notes.observeActive()
    fun observeSummaries(): Flow<List<NoteSummary>> = notes.observeSummaries()
    fun observeFolders(): Flow<List<NoteFolderEntity>> = folders.observeActive()
    fun observeNote(id: String): Flow<NoteEntity?> = notes.observe(id)
    suspend fun getNote(id: String): NoteEntity? = notes.get(id)

    private suspend fun write(note: NoteEntity) {
        notes.put(note)
        changes.recorded(EntityType.NOTE, note.id)
    }

    /**
     * Plain-text upsert (older screens, sharing text into the app). Returns the stored note, or null when the draft
     * is empty (an empty note that was stored before is deleted, so blank notes never pile up).
     */
    suspend fun save(id: String, title: String, body: String): NoteEntity? {
        val existing = notes.get(id)
        val now = time.now()
        if (NoteRules.isEmpty(title, body)) {
            if (existing != null && existing.deletedUtc == null) write(existing.copy(deletedUtc = now, modifiedUtc = now))
            return null
        }
        val saved = if (existing == null) {
            NoteEntity(id = id, title = title, body = body, createdUtc = now, modifiedUtc = now)
        } else {
            if (existing.title == title && existing.body == body && existing.deletedUtc == null) return existing
            existing.copy(title = title, body = body, docJson = null, modifiedUtc = now, deletedUtc = null)
        }
        write(saved)
        return saved
    }

    /**
     * The editor's autosave: title, document (note-document-v1 JSON), its plain text and picture ids. Unchanged
     * content writes nothing; an empty new note is not stored.
     */
    suspend fun saveContent(id: String, title: String, docJson: String, text: String, assets: List<String>, folderId: String? = null): NoteEntity? {
        if (docJson.toByteArray().size > MAX_DOCUMENT_BYTES) throw NoteStorageException("This note is too large to save (over 2 MB of text and tables). Split it into smaller notes; pictures do not count.")
        val existing = notes.get(id)
        val now = time.now()
        val cleanTitle = title.trim().take(500)
        if (existing == null && cleanTitle.isEmpty() && text.isBlank() && assets.isEmpty()) return null
        if (existing != null && existing.title == cleanTitle && existing.docJson == docJson && existing.deletedUtc == null) return existing
        val saved = (existing ?: NoteEntity(id = id, createdUtc = now, modifiedUtc = now, folderId = folderId)).copy(
            title = cleanTitle, body = text.take(200_000), docJson = docJson, assetsJson = encodeIds(assets.distinct()),
            modifiedUtc = now, deletedUtc = null,
        )
        write(saved)
        return saved
    }

    suspend fun create(title: String = "", folderId: String? = null, docJson: String? = null, text: String = "", links: List<NoteLink> = emptyList(), assets: List<String> = emptyList()): NoteEntity {
        val now = time.now()
        val note = NoteEntity(id = Ids.newId(), title = title.trim().take(500), body = text.take(200_000), docJson = docJson,
            folderId = folderId?.takeIf { folders.get(it)?.deletedUtc == null }, linksJson = encodeLinks(links), assetsJson = encodeIds(assets),
            createdUtc = now, modifiedUtc = now)
        write(note)
        return note
    }

    private suspend fun edit(ids: Collection<String>, change: (NoteEntity) -> NoteEntity) {
        val now = time.now()
        db.runInTransactionSuspend {
            ids.distinct().forEach { id -> notes.get(id)?.takeIf { it.deletedUtc == null }?.let { write(change(it).copy(modifiedUtc = now)) } }
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = edit(listOf(id)) { it.copy(pinned = pinned) }
    suspend fun setFavorite(ids: Collection<String>, favorite: Boolean) = edit(ids) { it.copy(favorite = favorite) }
    suspend fun archive(ids: Collection<String>, archived: Boolean) = edit(ids) { it.copy(archivedUtc = if (archived) time.now() else null) }
    suspend fun trash(ids: Collection<String>) = edit(ids) { it.copy(trashedUtc = time.now()) }
    suspend fun restoreFromTrash(ids: Collection<String>) = edit(ids) { it.copy(trashedUtc = null) }
    suspend fun move(ids: Collection<String>, folderId: String?) {
        if (folderId != null && folders.get(folderId)?.deletedUtc != null) throw NoteStorageException("That folder no longer exists.")
        edit(ids) { it.copy(folderId = folderId) }
    }

    suspend fun addLink(id: String, link: NoteLink) = edit(listOf(id)) { n ->
        val links = decodeLinks(n.linksJson)
        if (links.any { it.type == link.type && it.id == link.id }) n else n.copy(linksJson = encodeLinks(links + link))
    }

    suspend fun removeLink(id: String, link: NoteLink) = edit(listOf(id)) { n ->
        n.copy(linksJson = encodeLinks(decodeLinks(n.linksJson).filterNot { it.type == link.type && it.id == link.id }))
    }

    suspend fun duplicate(id: String): NoteEntity? {
        val source = notes.get(id)?.takeIf { it.deletedUtc == null } ?: return null
        val now = time.now()
        val copy = source.copy(id = Ids.newId(), title = (source.title.ifBlank { "Copy" } + " (copy)").take(500), pinned = false, trashedUtc = null,
            serverVersion = null, remoteJson = null, createdUtc = now, modifiedUtc = now)
        write(copy)
        return copy
    }

    /** Soft delete for good (a tombstone kept for sync); returns the note for Undo. */
    suspend fun delete(id: String): NoteEntity? {
        val note = notes.get(id) ?: return null
        write(note.copy(deletedUtc = time.now(), modifiedUtc = time.now()))
        return note
    }

    suspend fun deleteForever(ids: Collection<String>) = ids.forEach { delete(it) }

    suspend fun restore(snapshot: NoteEntity) = write(snapshot.copy(modifiedUtc = time.now()))

    /** Deletes notes that have been in Trash longer than [retention]. Returns how many. */
    suspend fun purgeTrash(retention: Duration = DEFAULT_TRASH_RETENTION): Int {
        val old = notes.trashedBefore(time.now().minus(retention))
        old.forEach { delete(it.id) }
        return old.size
    }

    // ------------------------------------------------------------------------------------------------- conflicts

    /**
     * Resolves a sync conflict between a note (the other device's version, under the original id) and its conflict
     * copy (this device's version). The original id keeps the result so every device converges; the conflict copy goes
     * to Trash, where it can still be restored. Returns the id of the note to show afterwards.
     */
    suspend fun resolveConflict(originalId: String, copyId: String, choice: NoteConflictChoice): String? {
        val copy = notes.get(copyId)?.takeIf { it.deletedUtc == null }
        val original = notes.get(originalId)?.takeIf { it.deletedUtc == null }
        when (choice) {
            NoteConflictChoice.KEEP_BOTH -> return copy?.id ?: original?.id
            NoteConflictChoice.KEEP_OTHER -> { if (copy != null) trash(listOf(copy.id)); return original?.id }
            else -> Unit
        }
        if (copy == null) return original?.id
        if (original == null) {
            // The other device deleted the note: this device's version stays, under its own title again.
            edit(listOf(copy.id)) { it.copy(title = NoteMerge.originalTitle(it.title)) }
            return copy.id
        }
        val now = time.now()
        val resolved = if (choice == NoteConflictChoice.KEEP_MINE) {
            original.copy(title = NoteMerge.originalTitle(copy.title), body = copy.body, docJson = copy.docJson, assetsJson = copy.assetsJson,
                linksJson = copy.linksJson, modifiedUtc = now)
        } else {
            val parse = { t: String? -> t?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } }
            val doc = NoteMerge.combineDocuments(parse(original.docJson), original.body, parse(copy.docJson), copy.body).toString()
            if (doc.toByteArray().size > MAX_DOCUMENT_BYTES) throw NoteStorageException("Both versions together are over 2 MB of text and tables. Keep one version, or keep both as separate notes.")
            val links = decodeLinks(original.linksJson).let { mine -> mine + decodeLinks(copy.linksJson).filter { l -> mine.none { it.type == l.type && it.id == l.id } } }
            original.copy(docJson = doc, body = NoteMerge.combineText(original.body, copy.body).take(200_000),
                assetsJson = encodeIds((decodeIds(original.assetsJson) + decodeIds(copy.assetsJson)).distinct()),
                linksJson = encodeLinks(links.take(50)), modifiedUtc = now)
        }
        db.runInTransactionSuspend {
            write(resolved)
            write(copy.copy(trashedUtc = now, modifiedUtc = now))
        }
        return original.id
    }

    /** The first note (not in Trash) linking to the object, e.g. an event's meeting notes. */
    suspend fun findLinked(type: String, id: String): NoteEntity? =
        notes.linkingTo(id).firstOrNull { n -> decodeLinks(n.linksJson).any { it.type == type && it.id == id } }

    /** An event's meeting notes: the note linked to it, or a new one from the meeting template. Returns its id. */
    suspend fun meetingNotes(eventId: String, subject: String, `when`: String, description: String?): String {
        findLinked(NoteLink.EVENT, eventId)?.let { return it.id }
        val (doc, text) = MeetingNotes.document(`when`, description)
        return create(title = MeetingNotes.title(subject), docJson = doc.toString(), text = text, links = listOf(NoteLink(NoteLink.EVENT, eventId, subject.ifBlank { null }))).id
    }

    // ------------------------------------------------------------------------------------------------- folders

    suspend fun addFolder(name: String, parentId: String? = null): NoteFolderEntity {
        val clean = name.trim().take(200).ifEmpty { throw NoteStorageException("Enter a folder name.") }
        val now = time.now()
        val position = (folders.active().maxOfOrNull { it.position } ?: -1.0) + 1
        val folder = NoteFolderEntity(id = Ids.newId(), parentId = parentId?.takeIf { folders.get(it)?.deletedUtc == null }, name = clean, position = position, createdUtc = now, modifiedUtc = now)
        folders.put(folder)
        changes.recorded(EntityType.NOTE_FOLDER, folder.id)
        return folder
    }

    suspend fun renameFolder(id: String, name: String) {
        val folder = folders.get(id)?.takeIf { it.deletedUtc == null } ?: throw NoteStorageException("That folder no longer exists.")
        folders.put(folder.copy(name = name.trim().take(200).ifEmpty { throw NoteStorageException("Enter a folder name.") }, modifiedUtc = time.now()))
        changes.recorded(EntityType.NOTE_FOLDER, id)
    }

    /** Deletes a folder; its notes move to All notes and subfolders move up a level. Nothing else is deleted. */
    suspend fun deleteFolder(id: String) {
        val folder = folders.get(id)?.takeIf { it.deletedUtc == null } ?: return
        val now = time.now()
        db.runInTransactionSuspend {
            folders.active().filter { it.parentId == id }.forEach { child ->
                folders.put(child.copy(parentId = folder.parentId, modifiedUtc = now)); changes.recorded(EntityType.NOTE_FOLDER, child.id)
            }
            notes.inFolder(id).forEach { write(it.copy(folderId = null, modifiedUtc = now)) }
            folders.put(folder.copy(deletedUtc = now, modifiedUtc = now))
            changes.recorded(EntityType.NOTE_FOLDER, id)
        }
    }

    // ------------------------------------------------------------------------------------------------- search

    /** Ids of notes matching every word (prefix match), via the full-text index. */
    suspend fun search(query: String, limit: Int = 500): Set<String> {
        val match = ftsQuery(query) ?: return emptySet()
        return notes.searchIds(match, limit).toSet()
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 2 shl 20
        val DEFAULT_TRASH_RETENTION: Duration = Duration.ofDays(30)
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun decodeIds(text: String): List<String> = runCatching { json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrDefault(emptyList())
        fun encodeIds(ids: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), ids)
        fun decodeLinks(text: String): List<NoteLink> = runCatching { json.decodeFromString(ListSerializer(NoteLink.serializer()), text) }.getOrDefault(emptyList())
        fun encodeLinks(links: List<NoteLink>): String = json.encodeToString(ListSerializer(NoteLink.serializer()), links)

        /** Words become quoted prefix terms, so FTS operators typed by the user are searched literally. */
        fun ftsQuery(query: String): String? {
            val words = query.split(Regex("\\s+")).map { it.filter { c -> c.isLetterOrDigit() || c in "-_'@." } }.filter { it.isNotBlank() }.take(12)
            return if (words.isEmpty()) null else words.joinToString(" ") { "\"${it.replace("\"", "")}*\"" }
        }

        fun inView(n: NoteSummary, view: NoteView, folderId: String?): Boolean = when (view) {
            NoteView.TRASH -> n.trashedUtc != null
            NoteView.ARCHIVE -> n.trashedUtc == null && n.archivedUtc != null
            NoteView.FAVORITES -> n.trashedUtc == null && n.archivedUtc == null && n.favorite
            NoteView.FOLDER -> n.trashedUtc == null && n.archivedUtc == null && n.folderId == folderId
            NoteView.ALL -> n.trashedUtc == null && n.archivedUtc == null
        }

        /** The rows of a view: pinned first (except in Trash), then by the chosen order. */
        fun query(all: List<NoteSummary>, view: NoteView, folderId: String? = null, sort: NoteSort = NoteSort.MODIFIED, descending: Boolean = true, onlyIds: Set<String>? = null): List<NoteSummary> {
            val items = all.filter { inView(it, view, folderId) && (onlyIds == null || it.id in onlyIds) }
            val byKey: Comparator<NoteSummary> = when (sort) {
                NoteSort.CREATED -> compareBy { it.createdUtc }
                NoteSort.TITLE -> compareBy { NoteRules.displayTitle(it.title, it.preview).lowercase() }
                NoteSort.MODIFIED -> compareBy { it.modifiedUtc }
            }
            val ordered = if (descending) byKey.reversed() else byKey
            val pinnedFirst: Comparator<NoteSummary> = if (view == NoteView.TRASH) compareBy { 0 } else compareByDescending { it.pinned }
            return items.sortedWith(pinnedFirst.then(ordered).thenBy { it.id })
        }
    }
}

/** Room's withTransaction without the extra import at every call site. */
private suspend fun AppDatabase.runInTransactionSuspend(block: suspend () -> Unit) = withTransaction { block() }
