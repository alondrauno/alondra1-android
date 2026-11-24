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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
