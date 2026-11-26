package com.alondra1.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.AppContainer
import com.alondra1.app.TestSupport
import com.alondra1.app.core.FixedTimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.notifications.RecordingReminderScheduler
import com.alondra1.app.data.repository.InvalidEventException
import com.alondra1.app.data.repository.InvalidTitleException
import com.alondra1.app.data.repository.SearchRepository
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.domain.EventError
import com.alondra1.app.domain.SmartView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class RepositoriesTest {
    private val time = FixedTimeSource(TestSupport.clockAt("2026-11-23T10:00:00"))
    private val c: AppContainer = TestSupport.container(time)
    private val reminders get() = c.reminders as RecordingReminderScheduler
    private val today = LocalDate.of(2026, 11, 23)

    @Test fun quickAddFollowsTheView() = runBlocking<Unit> {
        val myDay = c.todo.addTask("Buy groceries", view = SmartView.MY_DAY)
        assertEquals(today, myDay.myDayDate)
        val important = c.todo.addTask("Call family", view = SmartView.IMPORTANT)
        assertTrue(important.important)
        val dated = c.todo.addTask("Course", dueDate = today.plusDays(3))
        assertEquals(today.plusDays(3), dated.dueDate)
        val defaultList = c.todo.ensureDefaultList()
        assertTrue(listOf(myDay, important, dated).all { it.listId == defaultList.id })
        assertEquals(32, myDay.id.length)
        assertTrue(myDay.id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun titleValidatedInTheRepository() = runBlocking<Unit> {
        try { c.todo.addTask("   "); fail() } catch (_: InvalidTitleException) {}
        try { c.todo.addTask("x".repeat(501)); fail() } catch (_: InvalidTitleException) {}
        val t = c.todo.addTask("ok")
        try { c.todo.updateTask(t.copy(title = "")); fail() } catch (_: InvalidTitleException) {}
        assertEquals("ok", c.todo.getTask(t.id)!!.title)
    }

    @Test fun completeAndUndo() = runBlocking<Unit> {
        val t = c.todo.addTask("Task")
        val before = c.todo.setCompleted(t.id, true)!!
        assertTrue(c.todo.getTask(t.id)!!.completed)
        assertNotNull(c.todo.getTask(t.id)!!.completedUtc)
        c.todo.restore(before)
        val restored = c.todo.getTask(t.id)!!
        assertFalse(restored.completed)
        assertNull(restored.completedUtc)
    }

    @Test fun deleteIsATombstoneAndUndoable() = runBlocking<Unit> {
        val t = c.todo.addTask("Task")
        val snapshot = c.todo.deleteTask(t.id)!!
        assertNotNull(c.db.tasks().get(t.id)!!.deletedUtc)
        assertTrue(c.todo.observeTasks().first().none { it.id == t.id })
        c.todo.restore(snapshot)
        assertNull(c.db.tasks().get(t.id)!!.deletedUtc)
    }

    @Test fun deletingAListMovesTasksToTasksByDefault() = runBlocking<Unit> {
        val home = c.todo.createList("Home")
        val a = c.todo.addTask("A", listId = home.id)
        val b = c.todo.addTask("B", listId = home.id)
        c.todo.deleteList(home.id)
        val default = c.todo.ensureDefaultList()
        assertEquals(default.id, c.todo.getTask(a.id)!!.listId)
        assertEquals(default.id, c.todo.getTask(b.id)!!.listId)
        assertNull(c.todo.getTask(a.id)!!.deletedUtc)
        assertTrue(c.todo.observeLists().first().none { it.id == home.id })
    }

    @Test fun deletingAListWithItsTasks() = runBlocking<Unit> {
        val home = c.todo.createList("Home")
        val a = c.todo.addTask("A", listId = home.id)
        c.todo.addReminder(a.id, LocalDateTime.of(today, java.time.LocalTime.NOON))
        c.todo.deleteList(home.id, moveTasksToDefault = false)
        assertNotNull(c.db.tasks().get(a.id)!!.deletedUtc)
        assertFalse(reminders.scheduled.keys.any { it.startsWith("task:${a.id}:") })
    }

    @Test fun defaultListCannotBeDeleted() = runBlocking<Unit> {
        val default = c.todo.ensureDefaultList()
        try { c.todo.deleteList(default.id); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(default.id, c.todo.ensureDefaultList().id)
    }

    @Test fun remindersFollowEditsWithoutDuplicates() = runBlocking<Unit> {
        val t = c.todo.addTask("Dentist")
        c.todo.addReminder(t.id, LocalDateTime.parse("2026-11-24T09:00"))
        c.todo.addReminder(t.id, LocalDateTime.parse("2026-11-24T10:00"))
        c.todo.addReminder(t.id, LocalDateTime.parse("2026-11-24T10:00")) // same time twice: one reminder
        assertEquals(setOf("2026-11-24T09:00", "2026-11-24T10:00"), reminders.scheduled.values.toSet())
        assertEquals(2, reminders.scheduled.size)
        val first = c.todo.reminders(c.todo.getTask(t.id)!!).first()
        c.todo.removeReminder(t.id, first.id)
        assertEquals(listOf("2026-11-24T10:00"), reminders.scheduled.values.toList())
        c.todo.setCompleted(t.id, true)
        assertTrue("completing cancels the reminders", reminders.scheduled.isEmpty())
        c.todo.setCompleted(t.id, false)
        assertEquals(1, reminders.scheduled.size)
        c.todo.deleteTask(t.id)
        assertTrue(reminders.scheduled.isEmpty())
    }

    @Test fun notesAutosaveRulesAndEmptyDrafts() = runBlocking<Unit> {
        assertNull("empty drafts are never stored", c.notes.save("n1", " ", "\n"))
        assertTrue(c.notes.observeNotes().first().isEmpty())
        val saved = c.notes.save("n1", "", "Groceries\nmilk")!!
        assertEquals("Groceries\nmilk", saved.body)
        c.notes.save("n1", "", "")
        assertTrue("clearing a note removes it", c.notes.observeNotes().first().isEmpty())
        c.notes.save("n2", "B", "b"); c.notes.save("n3", "C", "c")
        c.notes.setPinned("n2", true)
        assertEquals("pinned first", "n2", c.notes.observeNotes().first().first().id)
    }

    @Test fun eventValidationAndReminders() = runBlocking<Unit> {
        val base = EventEntity(id = "", title = "Lunch", allDay = false,
            startUtc = Instant.parse("2026-11-24T11:00:00Z"), endUtc = Instant.parse("2026-11-24T10:00:00Z"),
            timeZoneId = "Europe/Berlin", reminderMinutes = 10, createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH)
        try { c.calendar.save(base); fail() } catch (e: InvalidEventException) { assertEquals(EventError.END_BEFORE_START, e.error) }
        val ok = c.calendar.save(base.copy(endUtc = Instant.parse("2026-11-24T12:00:00Z")))
        assertEquals(32, ok.id.length)
        assertTrue(reminders.scheduled.containsKey("event:${ok.id}"))
        c.calendar.delete(ok.id)
        assertFalse(reminders.scheduled.containsKey("event:${ok.id}"))
    }

    @Test fun allDayEventsDropTimes() = runBlocking<Unit> {
        val e = c.calendar.save(EventEntity(id = "", title = "Trip", allDay = true, startDate = today, endDate = today.plusDays(2),
            startUtc = Instant.EPOCH, endUtc = Instant.EPOCH, timeZoneId = "Europe/Berlin", createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH))
        val stored = c.calendar.getEvent(e.id)!!
        assertNull(stored.startUtc); assertEquals(today.plusDays(2), stored.endDate)
    }

    @Test fun searchAcrossModulesTreatsWildcardsLiterally() = runBlocking<Unit> {
        c.todo.addTask("Pay 100% of rent")
        c.todo.addTask("Pay rent")
        c.notes.save("n", "Rent notes", "landlord_name")
        val t = c.todo.addTask("Hidden rent"); c.todo.deleteTask(t.id)
        val results = c.search.search("100%")
        assertEquals(listOf("Pay 100% of rent"), results.tasks.map { it.title })
        assertEquals(1, c.search.search("_name").notes.size)
        val rent = c.search.search("rent")
        assertEquals(2, rent.tasks.size)
        assertEquals(1, rent.notes.size)
        assertEquals("a\\%b\\_c\\\\", SearchRepository.escapeLike("a%b_c\\"))
    }

    @Test fun freeModeRecordsNothingForSync() {
        assertTrue(c.changes === ChangeRecorder.LocalOnly)
    }

    @Test fun dataSurvivesARestart() = runBlocking<Unit> {
        val ctx = TestSupport.context()
        val name = "restart-test.db"
        ctx.deleteDatabase(name)
        val first = Room.databaseBuilder(ctx, AppDatabase::class.java, name).allowMainThreadQueries().build()
        val c1 = AppContainer(first, time, RecordingReminderScheduler(), TestSupport.settings())
        val t = c1.todo.addTask("Persist me", view = SmartView.MY_DAY)
        c1.notes.save("note", "Kept", "body")
        first.close()
        val second = Room.databaseBuilder(ctx, AppDatabase::class.java, name).allowMainThreadQueries().build()
        val c2 = AppContainer(second, time, RecordingReminderScheduler(), TestSupport.settings())
        assertEquals("Persist me", c2.todo.getTask(t.id)!!.title)
        assertEquals("Kept", c2.notes.getNote("note")!!.title)
        second.close()
        ctx.deleteDatabase(name)
    }

    /**
     * The committed schema (app/schemas/.../<version>.json) must describe exactly the compiled entities: an entity
     * change without a new schema version and migration fails here.
     */
    @Test fun committedSchemaMatchesTheEntities() = runBlocking<Unit> {
        val version = 6
        val json = File("schemas/com.alondra1.app.data.local.AppDatabase/$version.json").readText()
        val expected = Regex(""""identityHash"\s*:\s*"([0-9a-f]+)"""").find(json)!!.groupValues[1]
        val db = TestSupport.memoryDb()
        db.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table").use { cursor ->
            cursor.moveToFirst()
            assertEquals(expected, cursor.getString(0))
        }
        assertEquals(version, db.openHelper.writableDatabase.version)
        assertEquals("one migration per version step", version - 1, AppDatabase.MIGRATIONS.size)
        db.close()
    }
}
