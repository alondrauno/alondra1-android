package com.alondra1.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alondra1.app.TestSupport
import com.alondra1.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades keep every task, list, note and event (no destructive migration). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun version1To2KeepsAllData() = runBlocking<Unit> {
        val name = "migration-1-2.db"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO task_lists (id, name, color, position, isDefault, createdUtc, modifiedUtc, deletedUtc, remoteJson, serverVersion) VALUES ('l1', 'Tasks', NULL, 0, 1, 1, 1, NULL, NULL, NULL)")
            execSQL("INSERT INTO tasks (id, listId, title, notes, completed, important, position, createdUtc, modifiedUtc, completedUtc, dueDate, dueTime, myDayDate, reminderAt, sourceNoteId, deletedUtc, remoteJson, serverVersion) " +
                "VALUES ('t1', 'l1', 'Kept across upgrades', '', 0, 1, 1, 1, 1, NULL, '2026-11-24', NULL, NULL, '2026-11-24T09:00', NULL, NULL, NULL, NULL)")
            execSQL("INSERT INTO notes (id, title, body, pinned, createdUtc, modifiedUtc, deletedUtc, serverVersion) VALUES ('n1', 'Note', 'body', 1, 1, 1, NULL, NULL)")
            execSQL("INSERT INTO events (id, title, description, allDay, startUtc, endUtc, startDate, endDate, timeZoneId, reminderMinutes, createdUtc, modifiedUtc, deletedUtc, serverVersion) " +
                "VALUES ('e1', 'Trip', '', 1, NULL, NULL, '2026-12-20', '2026-12-27', 'Europe/Berlin', NULL, 1, 1, NULL, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, *AppDatabase.MIGRATIONS)
        val db = Room.databaseBuilder(TestSupport.context(), AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val task = db.tasks().get("t1")!!
        assertEquals("Kept across upgrades", task.title)
        // Schema 5 turned the single reminder into the reminder list, keeping its synced id.
        assertEquals("""[{"id":"t1-r","at":"2026-11-24T09:00:00"}]""", task.remindersJson)
        assertEquals(null, task.reminderAt)
        assertEquals("body", db.notes().get("n1")!!.body)
        assertEquals(java.time.LocalDate.parse("2026-12-27"), db.events().get("e1")!!.endDate)
        assertEquals(0, db.sync().outbox().size)
        db.close()
    }

    @Test fun version2To3AddsRichNotesAndIndexesExistingText() = runBlocking<Unit> {
        val name = "migration-2-3.db"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO notes (id, title, body, pinned, createdUtc, modifiedUtc, deletedUtc, serverVersion) VALUES ('n1', 'Packing', 'passport and charger', 0, 1, 1, NULL, 4)")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, *AppDatabase.MIGRATIONS)
        val db = Room.databaseBuilder(TestSupport.context(), AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val note = db.notes().get("n1")!!
        assertEquals("passport and charger", note.body)
        assertEquals(null, note.docJson)
        assertEquals("[]", note.assetsJson)
        assertEquals(4L, note.serverVersion)
        assertEquals(listOf("n1"), db.notes().searchIds(com.alondra1.app.data.repository.NotesRepository.ftsQuery("charg")!!, 10))
        db.close()
    }

    @Test fun version3To4LetsSyncIssuesRememberAConflictCopy() = runBlocking<Unit> {
        val name = "migration-3-4.db"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO sync_issues (entityType, entityId, title, message, createdUtc) VALUES ('task', 't1', 'Old', 'Kept', 1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, *AppDatabase.MIGRATIONS)
        val db = Room.databaseBuilder(TestSupport.context(), AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val old = db.sync().issue(1)!!
        assertEquals("Old", old.title)
        assertEquals(null, old.copyId)
        db.close()
    }

    @Test fun version4To5MovesTheReminderIntoTheListAndAddsGroupsAndHistory() = runBlocking<Unit> {
        val name = "migration-4-5.db"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO task_lists (id, name, color, position, isDefault, createdUtc, modifiedUtc, deletedUtc, remoteJson, serverVersion) VALUES ('l1', 'Tasks', NULL, 0, 1, 1, 1, NULL, NULL, 3)")
            execSQL("INSERT INTO tasks (id, listId, title, notes, completed, important, position, createdUtc, modifiedUtc, completedUtc, dueDate, dueTime, myDayDate, reminderAt, sourceNoteId, deletedUtc, remoteJson, serverVersion) " +
                "VALUES ('t1', 'l1', 'Dentist', '', 0, 0, 1, 1, 1, NULL, '2026-11-24', NULL, NULL, '2026-11-24T09:00', NULL, NULL, NULL, 7)")
            execSQL("INSERT INTO tasks (id, listId, title, notes, completed, important, position, createdUtc, modifiedUtc, completedUtc, dueDate, dueTime, myDayDate, reminderAt, sourceNoteId, deletedUtc, remoteJson, serverVersion) " +
                "VALUES ('t2', 'l1', 'No reminder', '', 0, 0, 2, 1, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, *AppDatabase.MIGRATIONS)
        val db = Room.databaseBuilder(TestSupport.context(), AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val t1 = db.tasks().get("t1")!!
        assertEquals(listOf(com.alondra1.app.domain.TaskReminder("t1-r", java.time.LocalDateTime.parse("2026-11-24T09:00"))),
            com.alondra1.app.domain.TaskJson.reminders(t1.remindersJson))
        assertEquals("[]", t1.stepsJson)
        assertEquals(7L, t1.serverVersion)
        assertEquals("[]", db.tasks().get("t2")!!.remindersJson)
        assertEquals(null, db.taskLists().get("l1")!!.groupId)
        assertEquals(0, db.listGroups().active().size)
        assertEquals(0, db.completions().forTask("t1").size)
        db.close()
    }

    @Test fun version5To6MovesSyncedAttachmentsIntoTheirColumn() = runBlocking<Unit> {
        val name = "migration-5-6.db"
        val remote = """{"id":"t1","listId":"l1","title":"Budget","attachments":[{"id":"w1","fileName":"plan.xlsx","relativePath":"t1/abc.xlsx","size":5,"addedUtc":"2026-11-20T10:00:00+00:00"}]}"""
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO task_lists (id, name, color, position, isDefault, createdUtc, modifiedUtc, deletedUtc, remoteJson, serverVersion, groupId) VALUES ('l1', 'Tasks', NULL, 0, 1, 1, 1, NULL, NULL, 3, NULL)")
            execSQL("INSERT INTO tasks (id, listId, title, notes, completed, important, position, createdUtc, modifiedUtc, completedUtc, dueDate, dueTime, myDayDate, reminderAt, sourceNoteId, deletedUtc, remoteJson, serverVersion, startDate, recurrenceJson, stepsJson, remindersJson) " +
                "VALUES ('t1', 'l1', 'Budget', '', 0, 0, 1, 1, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?, 4, NULL, NULL, '[]', '[]')", arrayOf<Any>(remote))
            execSQL("INSERT INTO tasks (id, listId, title, notes, completed, important, position, createdUtc, modifiedUtc, completedUtc, dueDate, dueTime, myDayDate, reminderAt, sourceNoteId, deletedUtc, remoteJson, serverVersion, startDate, recurrenceJson, stepsJson, remindersJson) " +
                "VALUES ('t2', 'l1', 'Plain', '', 0, 0, 2, 1, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '[]', '[]')")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, *AppDatabase.MIGRATIONS)
        val db = Room.databaseBuilder(TestSupport.context(), AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val attachments = com.alondra1.app.domain.TaskAttachments.parse(db.tasks().get("t1")!!.attachmentsJson)
        assertEquals(listOf("plan.xlsx"), attachments.map { it.fileName })
        assertEquals("t1/abc.xlsx", attachments.single().relativePath)
        assertEquals("[]", db.tasks().get("t2")!!.attachmentsJson)
        assertEquals(0, db.taskFiles().all().size)
        db.close()
    }
}
