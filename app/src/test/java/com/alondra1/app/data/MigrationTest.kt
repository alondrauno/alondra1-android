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
        assertEquals(java.time.LocalDateTime.parse("2026-11-24T09:00"), task.reminderAt)
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
}
