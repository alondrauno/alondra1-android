package com.alondra1.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.AppContainer
import com.alondra1.app.TestSupport
import com.alondra1.app.core.FixedTimeSource
import com.alondra1.app.data.repository.NoteAssetException
import com.alondra1.app.data.repository.NoteAssets
import com.alondra1.app.data.repository.NoteLink
import com.alondra1.app.data.repository.NoteSort
import com.alondra1.app.data.repository.NoteStorageException
import com.alondra1.app.data.repository.NoteView
import com.alondra1.app.data.repository.NotesRepository
import com.alondra1.app.data.sync.WireMapper
import com.alondra1.app.domain.DiffSide
import com.alondra1.app.domain.MeetingNotes
import com.alondra1.app.domain.NoteConflictChoice
import com.alondra1.app.domain.NoteMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** The Notes module on Android: same rules and document format as Windows (Notes spec). */
private fun FixedTimeSource.advance(by: Duration) { clock = java.time.Clock.offset(clock, by) }

@RunWith(AndroidJUnit4::class)
class RichNotesTest {
    private val time = FixedTimeSource(TestSupport.clockAt("2026-11-24T10:00:00"))
    private val c: AppContainer = TestSupport.container(time)

    /** A document using every feature, written by the shared editor (also checked by the Windows and editor tests). */
    private val doc = """{"schemaVersion":1,"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Plan"}]},""" +
        """{"type":"paragraph","attrs":{"textAlign":"center","indent":2},"content":[{"type":"text","text":"Blue","marks":[{"type":"bold"},{"type":"textStyle","attrs":{"color":"#2563EB","fontSize":"18pt"}}]}]},""" +
        """{"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"Done"}]}]}]},""" +
        """{"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","attrs":{"colspan":1,"rowspan":1,"backgroundColor":"#FEF3C7"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Cell"}]}]}]}]},""" +
        """{"type":"futureNode","attrs":{"x":1}}]}"""

    @Test fun richContentIsStoredUnchangedAndEmptyNotesAreNot() = runBlocking<Unit> {
        assertNull(c.notes.saveContent("new", "", """{"schemaVersion":1,"type":"doc","content":[{"type":"paragraph"}]}""", "", emptyList()))
        val saved = c.notes.saveContent("n1", " Plan ", doc, "Plan\nBlue\n[x] Done", listOf("a".repeat(64)))!!
        assertEquals("Plan", saved.title)
        assertEquals(doc, c.notes.getNote("n1")!!.docJson)
        assertEquals(listOf("a".repeat(64)), NotesRepository.decodeIds(c.notes.getNote("n1")!!.assetsJson))
        try { c.notes.saveContent("n1", "Big", "{" + "x".repeat(NotesRepository.MAX_DOCUMENT_BYTES) + "}", "", emptyList()); fail() } catch (_: NoteStorageException) {}
        assertEquals(doc, c.notes.getNote("n1")!!.docJson)
    }

    @Test fun viewsFoldersFavoritesArchiveAndTrash() = runBlocking<Unit> {
        val work = c.notes.addFolder("Work")
        val a = c.notes.create("A", folderId = work.id)
        val b = c.notes.create("B")
        val d = c.notes.create("D")
        c.notes.setFavorite(listOf(a.id), true)
        c.notes.archive(listOf(b.id), true)
        c.notes.trash(listOf(d.id))
        val all = c.notes.observeSummaries().first()
        assertEquals(listOf("A"), NotesRepository.query(all, NoteView.ALL).map { it.title })
        assertEquals(listOf("A"), NotesRepository.query(all, NoteView.FAVORITES).map { it.title })
        assertEquals(listOf("A"), NotesRepository.query(all, NoteView.FOLDER, work.id).map { it.title })
        assertEquals(listOf("B"), NotesRepository.query(all, NoteView.ARCHIVE).map { it.title })
        assertEquals(listOf("D"), NotesRepository.query(all, NoteView.TRASH).map { it.title })
        c.notes.restoreFromTrash(listOf(d.id))
        assertEquals(2, NotesRepository.query(c.notes.observeSummaries().first(), NoteView.ALL).size)
        // Deleting a folder keeps its notes.
        c.notes.deleteFolder(work.id)
        assertNull(c.notes.getNote(a.id)!!.folderId)
        assertNull(c.notes.getNote(a.id)!!.deletedUtc)
    }

    @Test fun trashRetentionDeletesOnlyOldNotes() = runBlocking<Unit> {
        val old = c.notes.create("Old")
        c.notes.trash(listOf(old.id))
        time.advance(Duration.ofDays(31))
        val recent = c.notes.create("Recent")
        c.notes.trash(listOf(recent.id))
        assertEquals(1, c.notes.purgeTrash())
        assertNotNull(c.notes.getNote(old.id)!!.deletedUtc)
        assertNull(c.notes.getNote(recent.id)!!.deletedUtc)
    }

    @Test fun sortPinDuplicateMoveAndLinks() = runBlocking<Unit> {
        val banana = c.notes.create("banana")
        time.advance(Duration.ofMinutes(1))
        val apple = c.notes.create("apple")
        var all = c.notes.observeSummaries().first()
        assertEquals(listOf("apple", "banana"), NotesRepository.query(all, NoteView.ALL).map { it.title })
        assertEquals(listOf("apple", "banana"), NotesRepository.query(all, NoteView.ALL, sort = NoteSort.TITLE, descending = false).map { it.title })
        c.notes.setPinned(banana.id, true)
        all = c.notes.observeSummaries().first()
        assertEquals("banana", NotesRepository.query(all, NoteView.ALL).first().title)
        assertEquals("apple (copy)", c.notes.duplicate(apple.id)!!.title)
        c.notes.addLink(apple.id, NoteLink(NoteLink.TASK, "t1", "Call"))
        c.notes.addLink(apple.id, NoteLink(NoteLink.TASK, "t1", "Call"))
        assertEquals(1, NotesRepository.decodeLinks(c.notes.getNote(apple.id)!!.linksJson).size)
    }

    @Test fun fullTextSearchUsesTheIndexAndTreatsOperatorsLiterally() = runBlocking<Unit> {
        val rent = c.notes.create("Rent", text = "Pay the landlord on Friday")
        c.notes.create("Groceries", text = "milk")
        assertEquals(setOf(rent.id), c.notes.search("landl"))
        assertEquals(setOf(rent.id), c.notes.search("rent friday"))
        assertTrue(c.notes.search("NEAR \" *").isEmpty())
        c.notes.delete(rent.id)
        assertTrue(c.notes.search("landlord").isEmpty())
    }

    @Test fun picturesAreCheckedFromTheirBytesAndStoredOnce() = runBlocking<Unit> {
        val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC", android.util.Base64.DEFAULT)
        val asset = c.noteAssets.add(png)
        assertEquals("image/png", asset.mimeType)
        assertEquals(1, asset.width)
        assertEquals(NoteAssets.hash(png), asset.sha256)
        assertEquals(asset.sha256, c.noteAssets.add(png).sha256)
        try { c.noteAssets.add("<script>".toByteArray()); fail() } catch (e: NoteAssetException) { assertTrue(e.message!!.contains("PNG")) }
        assertFalse(c.noteAssets.addDownloaded("0".repeat(64), png))

        // Per-note total: a picture already in the note never counts twice; a new one may not exceed the limit.
        val perNote = NoteAssets(c.db, c.noteAssets.directory, time, maxBytesPerNote = png.size + 10L)
        val other = png + ByteArray(20)
        assertEquals(asset.sha256, perNote.add(png, listOf(asset.sha256)).sha256)
        try { perNote.add(other, listOf(asset.sha256)); fail() } catch (e: NoteAssetException) { assertTrue(e.message!!.contains("one note")) }
        assertEquals("image/png", perNote.add(png + ByteArray(5), emptyList()).mimeType)
    }

    @Test fun wireFormatCarriesEveryRichFieldAndKeepsUnknownServerFields() = runBlocking<Unit> {
        val folder = c.notes.addFolder("Work")
        c.notes.saveContent("n1", "Plan", doc, "Plan", listOf("b".repeat(64)), folder.id)
        c.notes.setFavorite(listOf("n1"), true)
        c.notes.addLink("n1", NoteLink(NoteLink.EVENT, "evt", "Standup"))
        val wire = WireMapper.note(c.notes.getNote("n1")!!)
        assertEquals(WireMapper.json.parseToJsonElement(doc), wire["doc"])
        assertEquals("true", wire["favorite"].toString())
        val back = WireMapper.noteFrom(wire, 3)
        assertEquals(WireMapper.json.parseToJsonElement(doc), WireMapper.json.parseToJsonElement(back.docJson!!))
        assertEquals(folder.id, back.folderId)
        assertEquals("Standup", NotesRepository.decodeLinks(back.linksJson).single().label)
        // A field from a newer server/client survives a local edit.
        val withExtra = WireMapper.json.parseToJsonElement(wire.toString().dropLast(1) + ""","futureField":42}""").jsonObject
        val local = WireMapper.noteFrom(withExtra, 4).copy(title = "Edited")
        assertEquals("42", WireMapper.note(local)["futureField"].toString())
    }

    // ---------------------------------------------------------------- conflicts and meeting notes (same rules as Windows)

    private fun paragraphs(vararg lines: String) = NoteMerge.paragraphDocument(lines.joinToString("\n"))

    @Test fun combiningKeepsEveryBlockOnceAndDifferencesFromBothSides() {
        val texts = NoteMerge.combineDocuments(paragraphs("Title", "phone line", "Shared end"), "", paragraphs("Title", "PC line", "Shared end", "PC tail"), "")["content"]!!
            .jsonArray.map { it.jsonObject["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content }
        assertEquals(listOf("Title", "phone line", "PC line", "Shared end", "PC tail"), texts)
        assertEquals(listOf(DiffSide.BOTH, DiffSide.ONLY_OTHER, DiffSide.ONLY_MINE, DiffSide.BOTH), NoteMerge.diffLines("a\nb\nc", "a\nB\nc").map { it.side })
        assertEquals("a\nb\nB\nc", NoteMerge.combineText("a\nb\nc", "a\nB\nc"))
        val future = kotlinx.serialization.json.JsonObject(paragraphs("x") + ("schemaVersion" to kotlinx.serialization.json.JsonPrimitive(2)))
        val plain = NoteMerge.combineDocuments(null, "old text", future, "")
        assertEquals("2", plain["schemaVersion"].toString())
        assertEquals(2, plain["content"]!!.jsonArray.size)
        assertEquals("Shared", NoteMerge.originalTitle("Shared (conflict copy)"))
    }

    @Test fun resolvingAConflictKeepsTheChosenVersion() = runBlocking<Unit> {
        val picture = "b".repeat(64)
        suspend fun pair(): Pair<String, String> {
            val original = c.notes.create("Plan", docJson = paragraphs("theirs").toString(), text = "theirs", links = listOf(NoteLink(NoteLink.TASK, "t1")))
            val copy = c.notes.create("Plan (conflict copy)", docJson = paragraphs("mine").toString(), text = "mine", links = listOf(NoteLink(NoteLink.EVENT, "e1")), assets = listOf(picture))
            return original.id to copy.id
        }

        val (o1, c1) = pair()
        assertEquals(o1, c.notes.resolveConflict(o1, c1, NoteConflictChoice.KEEP_OTHER))
        assertEquals("theirs", c.notes.getNote(o1)!!.body)
        assertNotNull(c.notes.getNote(c1)!!.trashedUtc)

        val (o2, c2) = pair()
        c.notes.resolveConflict(o2, c2, NoteConflictChoice.KEEP_MINE)
        val kept = c.notes.getNote(o2)!!
        assertEquals("Plan" to "mine", kept.title to kept.body)
        assertEquals(listOf(picture), NotesRepository.decodeIds(kept.assetsJson))
        assertEquals(listOf("e1"), NotesRepository.decodeLinks(kept.linksJson).map { it.id })
        assertNotNull(c.notes.getNote(c2)!!.trashedUtc)

        val (o3, c3) = pair()
        c.notes.resolveConflict(o3, c3, NoteConflictChoice.COMBINE)
        val both = c.notes.getNote(o3)!!
        assertEquals("theirs\nmine", both.body)
        assertEquals(listOf("t1", "e1"), NotesRepository.decodeLinks(both.linksJson).map { it.id })
        assertTrue(both.docJson!!.contains("theirs") && both.docJson!!.contains("mine"))

        val (o4, c4) = pair()
        assertEquals(c4, c.notes.resolveConflict(o4, c4, NoteConflictChoice.KEEP_BOTH))
        assertNull(c.notes.getNote(c4)!!.trashedUtc)

        // Deleted on the other device: keeping mine gives the copy its title back.
        val (o5, c5) = pair()
        c.notes.delete(o5)
        assertEquals(c5, c.notes.resolveConflict(o5, c5, NoteConflictChoice.KEEP_MINE))
        assertEquals("Plan", c.notes.getNote(c5)!!.title)
    }

    @Test fun meetingNotesAreCreatedOncePerEventAndLinkedToIt() = runBlocking<Unit> {
        val (doc, text) = MeetingNotes.document("Tuesday, 24 November 2026 · 10:00–11:00", "Room 4\n\nBring the plan")
        assertEquals(listOf("paragraph", "paragraph", "paragraph", "heading", "bulletList", "heading", "paragraph", "heading", "taskList"),
            doc["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(text.startsWith("Tuesday, 24 November 2026 · 10:00–11:00\nRoom 4\nBring the plan\nAgenda"))

        val id = c.notes.meetingNotes("evt-9", "Design review", "Tuesday", null)
        val note = c.notes.getNote(id)!!
        assertEquals("Design review – meeting notes", note.title)
        assertEquals(listOf(NoteLink(NoteLink.EVENT, "evt-9", "Design review")), NotesRepository.decodeLinks(note.linksJson))
        assertEquals("the same event opens the same note", id, c.notes.meetingNotes("evt-9", "Design review", "Tuesday", null))
        assertNull(c.notes.findLinked(NoteLink.EVENT, "evt-other"))
        c.notes.trash(listOf(id))
        assertNotEquals("a note in Trash is not reused", id, c.notes.meetingNotes("evt-9", "Design review", "Tuesday", null))
    }
}
