package com.alondra1.app

import android.content.Context
import androidx.room.withTransaction
import com.alondra1.app.core.SystemTimeSource
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.notifications.ReminderScheduler
import com.alondra1.app.data.notifications.WorkManagerReminderScheduler
import com.alondra1.app.data.repository.CalendarRepository
import com.alondra1.app.data.repository.NotesRepository
import com.alondra1.app.data.repository.SearchRepository
import com.alondra1.app.data.repository.TodoRepository
import com.alondra1.app.data.settings.SettingsRepository
import com.alondra1.app.auth.AccountRepository
import com.alondra1.app.auth.ApiClient
import com.alondra1.app.auth.SessionStore
import com.alondra1.app.data.sync.ChangeRecorder
import com.alondra1.app.data.sync.OutboxRecorder
import com.alondra1.app.data.sync.SyncEngine
import com.alondra1.app.data.sync.SyncWorker

/** Manual dependency wiring. Tests build their own container with an in-memory database and fakes. */
class AppContainer(
    val db: AppDatabase,
    val time: TimeSource,
    val reminders: ReminderScheduler,
    val settings: SettingsRepository,
    val changes: ChangeRecorder = ChangeRecorder.LocalOnly,
    /** Optional cloud (null in local-only builds and in most tests). */
    val cloud: Cloud? = null,
    /** Where note pictures are stored (files/notes-assets in the app). */
    assetsDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "alondra1-notes-assets"),
) {
    class Cloud(val session: SessionStore, val api: ApiClient, val account: AccountRepository, val engine: SyncEngine, val onSyncConfigured: (Boolean) -> Unit)

    val syncEngine: SyncEngine? get() = cloud?.engine

    val todo = TodoRepository(db, time, reminders, changes)
    val notes = NotesRepository(db, time, changes)
    val noteAssets = com.alondra1.app.data.repository.NoteAssets(db, assetsDir, time)
    val calendar = CalendarRepository(db, time, reminders, changes)
    val search = SearchRepository(db)

    suspend fun rescheduleAllReminders() {
        db.tasks().withReminders().forEach(reminders::syncTask)
        db.events().withReminders().forEach(reminders::syncEvent)
    }

    /** Settings → Privacy & data → Delete local data. Local only; never touches a cloud account. */
    suspend fun deleteAllLocalData() {
        reminders.cancelAll()
        db.withTransaction {
            db.maintenance().clearTasks()
            db.maintenance().clearLists()
            db.maintenance().clearNotes()
            db.maintenance().clearEvents()
        }
        settings.clear()
        todo.ensureDefaultList()
    }

    companion object {
        fun create(context: Context): AppContainer {
            val time = SystemTimeSource()
            val db = AppDatabase.build(context)
            val reminders = WorkManagerReminderScheduler(context, time)
            val assetsDir = java.io.File(context.filesDir, "notes-assets")
            if (BuildConfig.API_BASE_URL.isBlank()) {
                return AppContainer(db = db, time = time, reminders = reminders, settings = SettingsRepository(context), assetsDir = assetsDir)
            }
            val session = SessionStore.create(context)
            val api = ApiClient(BuildConfig.API_BASE_URL, session)
            val engine = SyncEngine(db, api, session, time, reminders, java.io.File(context.filesDir, "backups"),
                com.alondra1.app.data.repository.NoteAssets(db, assetsDir, time))
            val recorder = OutboxRecorder(session, { engine }, { SyncWorker.requestSoon(context) })
            val cloud = Cloud(session, api, AccountRepository(api, session), engine) { enabled ->
                if (enabled) { SyncWorker.schedulePeriodic(context); SyncWorker.requestSoon(context) } else SyncWorker.cancel(context)
            }
            return AppContainer(db = db, time = time, reminders = reminders, settings = SettingsRepository(context), changes = recorder, cloud = cloud, assetsDir = assetsDir)
        }
    }
}
