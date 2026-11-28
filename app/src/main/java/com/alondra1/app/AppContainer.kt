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
    /** API endpoints (signed discovery and failover, or a fixed development address); null without cloud. */
    val endpoints: com.alondra1.app.cloud.discovery.CloudEndpoints? = null,
    /** Where the user's own background picture is stored (files/backgrounds in the app). */
    backgroundsDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "alondra1-backgrounds"),
    /** Where files attached to tasks are stored (files/task-files in the app). */
    taskFilesDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "alondra1-task-files-" + java.util.UUID.randomUUID()),
) {
    class Cloud(val session: SessionStore, val api: ApiClient, val account: AccountRepository, val engine: SyncEngine, val onSyncConfigured: (Boolean) -> Unit)

    val syncEngine: SyncEngine? get() = cloud?.engine

    val todo = TodoRepository(db, time, reminders, changes)
    val notes = NotesRepository(db, time, changes)
    val noteAssets = com.alondra1.app.data.repository.NoteAssets(db, assetsDir, time)
    val calendar = CalendarRepository(db, time, reminders, changes)
    val tags = com.alondra1.app.data.repository.TagRepository(db, time, changes)
    val search = SearchRepository(db)
    val backgrounds = com.alondra1.app.data.settings.BackgroundStore(backgroundsDir)
    val taskFiles = com.alondra1.app.data.repository.TaskFiles(db, taskFilesDir, time)

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
            db.tags().clearLinks()
            db.tags().clearTags()
        }
        settings.clear()
        backgrounds.deleteAll()
        taskFiles.deleteAll()
        todo.ensureDefaultList()
    }

    companion object {
        fun create(context: Context): AppContainer {
            val time = SystemTimeSource()
            val db = AppDatabase.build(context)
            val reminders = WorkManagerReminderScheduler(context, time)
            val assetsDir = java.io.File(context.filesDir, "notes-assets")
            val backgroundsDir = java.io.File(context.filesDir, "backgrounds")
            val taskFilesDir = java.io.File(context.filesDir, "task-files")
            // A build whose fixed address belongs to the other environment, or whose bundled endpoint list has expired
            // with nothing verified since, has no cloud: local features are unaffected.
            val endpoints = com.alondra1.app.cloud.discovery.CloudEndpoints.create(context)
                ?: return AppContainer(db = db, time = time, reminders = reminders, settings = SettingsRepository(context), assetsDir = assetsDir, backgroundsDir = backgroundsDir,
                    taskFilesDir = taskFilesDir)
            val session = SessionStore.create(context)
            val api = ApiClient(endpoints.baseUrl, session, endpoints.http)
            val engine = SyncEngine(db, api, session, time, reminders, java.io.File(context.filesDir, "backups"),
                com.alondra1.app.data.repository.NoteAssets(db, assetsDir, time), com.alondra1.app.data.repository.TaskFiles(db, taskFilesDir, time))
            val recorder = OutboxRecorder(session, { engine }, { SyncWorker.requestSoon(context) })
            val cloud = Cloud(session, api, AccountRepository(api, session), engine) { enabled ->
                if (enabled) { SyncWorker.schedulePeriodic(context); SyncWorker.requestSoon(context) } else SyncWorker.cancel(context)
            }
            return AppContainer(db = db, time = time, reminders = reminders, settings = SettingsRepository(context), changes = recorder, cloud = cloud,
                assetsDir = assetsDir, endpoints = endpoints, backgroundsDir = backgroundsDir, taskFilesDir = taskFilesDir)
        }
    }
}
