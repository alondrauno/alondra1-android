package com.alondra1.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.TestSupport
import com.alondra1.app.data.repository.TaskFileException
import com.alondra1.app.data.repository.TaskFiles
import com.alondra1.app.data.sync.WireMapper
import com.alondra1.app.domain.AttachmentState
import com.alondra1.app.domain.TaskAttachments
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Task attachments on Android (Android UI and task attachments spec §7-§10). */
@RunWith(AndroidJUnit4::class)
class TaskAttachmentsTest {
    private val c = TestSupport.container()
    private val pdf = "%PDF-1.7\n% test\n".toByteArray()

    @Test fun aFileIsCopiedIntoTheStoreOnceAndTypedFromItsBytes() = runBlocking<Unit> {
        val a = c.taskFiles.store(pdf.inputStream(), "Tax return.pdf", "application/octet-stream")
        assertEquals(TaskFiles.hash(pdf), a.sha256)
        assertEquals("application/pdf", a.mediaType)
        assertEquals(pdf.size.toLong(), a.size)
        assertTrue(c.taskFiles.hasFile(a.sha256!!))
        val again = c.taskFiles.store(pdf.inputStream(), "copy.pdf", null)
        assertEquals("the same bytes are stored once", a.sha256, again.sha256)
        assertEquals(1, c.db.taskFiles().all().size)
        assertEquals("a new attachment id each time", false, a.id == again.id)
        // A Word document is a ZIP: the declared type is kept for it.
        val docx = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 1, 2, 3)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            c.taskFiles.store(docx.inputStream(), "a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").mediaType)
    }

    @Test fun emptyAndOversizeFilesAreRefusedWithoutLeavingAnything() = runBlocking<Unit> {
        try { c.taskFiles.store(ByteArray(0).inputStream(), "empty.txt", "text/plain"); fail() } catch (e: TaskFileException) { assertEquals("The file is empty.", e.message) }
        val saved = TaskFiles.limitBytes
        TaskFiles.limitBytes = 10
        try {
            c.taskFiles.store(ByteArray(11).inputStream(), "big.bin", null); fail()
        } catch (e: TaskFileException) {
            assertTrue(e.message!!.contains("at most"))
        } finally { TaskFiles.limitBytes = saved }
        assertEquals(0, c.db.taskFiles().all().size)
        assertTrue("no partial files are left", c.taskFiles.directory.walkTopDown().none { it.isFile })
    }

    @Test fun appsInstallersAndScriptsAreNeverOpenedOnlyShared() {
        listOf("app.apk", "Bundle.APKS", "game.xapk", "lib.so", "run.sh", "setup.exe", "photo.jpg.apk", "notes.apk.txt", "x.js")
            .forEach { assertFalse(it, TaskAttachments.canView(it, "application/octet-stream")) }
        assertFalse(TaskAttachments.canView("ticket.pdf", "application/vnd.android.package-archive"))
        assertFalse(TaskAttachments.canView("readme", "application/x-executable"))
        assertTrue(TaskAttachments.canView("ticket.pdf", "application/pdf"))
        assertTrue(TaskAttachments.canView("photo.JPG", "image/jpeg"))
        assertTrue(TaskAttachments.canView("plan.xlsx", null))
    }

    @Test fun namesAreCleanedForDisplayAndSaving() {
        assertEquals("passwd", TaskAttachments.safeName("../../etc/passwd"))
        assertEquals("report.pdf", TaskAttachments.safeName("C:\\Users\\me\\report.pdf"))
        assertEquals("ab.txt", TaskAttachments.safeName("a<>b.txt"))
        assertEquals("file", TaskAttachments.safeName("..."))
        assertEquals(260, TaskAttachments.safeName("x".repeat(400)).length)
    }

    @Test fun tasksKeepAttachmentsOfOtherClientsExactly() = runBlocking<Unit> {
        val fromWindows = """[{"id":"w1","fileName":"plan.xlsx","relativePath":"t1/abc.xlsx","size":5,"addedUtc":"2026-11-20T10:00:00+00:00","futureKey":1}]"""
        val task = c.todo.addTask("Budget")
        c.todo.updateTask(c.todo.getTask(task.id)!!.copy(attachmentsJson = fromWindows))
        val added = c.taskFiles.store(pdf.inputStream(), "a.pdf", null)
        c.todo.addAttachments(task.id, listOf(added))
        val wire = WireMapper.task(c.todo.getTask(task.id)!!)
        val sent = wire["attachments"]!!.jsonArray
        assertEquals(2, sent.size)
        assertEquals("t1/abc.xlsx", sent[0].jsonObject["relativePath"]!!.jsonPrimitive.content)
        assertEquals("1", sent[0].jsonObject["futureKey"]!!.jsonPrimitive.content)
        assertEquals(added.sha256, sent[1].jsonObject["sha256"]!!.jsonPrimitive.content)
        assertNull("no bytes in task JSON", sent[1].jsonObject["data"])

        // And back from the server: the column follows the server copy.
        val back = WireMapper.taskFrom(wire, 3, null)
        assertEquals(2, TaskAttachments.count(back.attachmentsJson))
        c.todo.removeAttachment(task.id, "w1")
        assertEquals(listOf(added.id), c.todo.attachments(c.todo.getTask(task.id)!!).map { it.id })
    }

    @Test fun addingATaskWithStagedFiles() = runBlocking<Unit> {
        val a = c.taskFiles.store(pdf.inputStream(), "ticket.pdf", null)
        val task = c.todo.addTask("Trip", attachments = listOf(a))
        val stored = c.todo.attachments(c.todo.getTask(task.id)!!).single()
        assertEquals("ticket.pdf", stored.fileName)
        assertEquals(AttachmentState.LOCAL_ONLY, c.taskFiles.state(stored, c.db.taskFiles().get(a.sha256!!)))
    }

    @Test fun statesTellWhereTheBytesAre() = runBlocking<Unit> {
        val a = c.taskFiles.store(pdf.inputStream(), "a.pdf", null)
        val sha = a.sha256!!
        c.db.taskFiles().markUploaded(sha)
        assertEquals(AttachmentState.SYNCED, c.taskFiles.state(a, c.db.taskFiles().get(sha)))
        val remote = a.copy(sha256 = "0".repeat(64))
        assertEquals("added elsewhere, fetched on demand", AttachmentState.IN_CLOUD, c.taskFiles.state(remote, null))
        assertEquals(AttachmentState.ON_OTHER_DEVICE, c.taskFiles.state(a.copy(sha256 = null, relativePath = "x/y"), null))
        c.db.taskFiles().put(c.db.taskFiles().get(sha)!!.copy(uploaded = false, lastError = "too large"))
        assertEquals(AttachmentState.FAILED, c.taskFiles.state(a, c.db.taskFiles().get(sha)))
        assertTrue(c.taskFiles.addDownloaded(sha, pdf, "application/pdf"))
        assertFalse("bytes that do not match the hash are dropped", c.taskFiles.addDownloaded("1".repeat(64), pdf, null))
    }

    @Test fun cleanupKeepsFilesAnyTaskListsIncludingDeletedOnes() = runBlocking<Unit> {
        val kept = c.taskFiles.store(pdf.inputStream(), "kept.pdf", null)
        val orphan = c.taskFiles.store("orphan".byteInputStream(), "orphan.txt", "text/plain")
        val task = c.todo.addTask("Keep", attachments = listOf(kept))
        c.todo.deleteTask(task.id) // still undoable: its file stays
        // Make both files old enough.
        listOf(kept, orphan).forEach { a -> c.db.taskFiles().put(c.db.taskFiles().get(a.sha256!!)!!.copy(createdUtc = java.time.Instant.parse("2026-01-01T00:00:00Z"))) }
        assertEquals(1, c.taskFiles.removeUnreferenced())
        assertNotNull(c.db.taskFiles().get(kept.sha256!!))
        assertNull(c.db.taskFiles().get(orphan.sha256!!))
        assertFalse(c.taskFiles.hasFile(orphan.sha256!!))
    }
}
