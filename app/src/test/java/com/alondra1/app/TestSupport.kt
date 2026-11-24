package com.alondra1.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alondra1.app.core.FixedTimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.notifications.RecordingReminderScheduler
import com.alondra1.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object TestSupport {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    fun clockAt(local: String, zone: ZoneId = ZONE): Clock =
        Clock.fixed(java.time.LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

    fun context(): Context = ApplicationProvider.getApplicationContext()

    fun memoryDb(): AppDatabase = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java).allowMainThreadQueries().build()

    fun settings(): SettingsRepository {
        val file = File(context().cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), produceFile = { file }))
    }

    fun container(
        time: FixedTimeSource = FixedTimeSource(clockAt("2026-11-23T10:00:00")),
        db: AppDatabase = memoryDb(),
    ) = AppContainer(db = db, time = time, reminders = RecordingReminderScheduler(), settings = settings())

    fun task(
        id: String = UUID.randomUUID().toString(),
        title: String = "Task",
        completed: Boolean = false,
        important: Boolean = false,
        due: LocalDate? = null,
        dueTime: java.time.LocalTime? = null,
        myDay: LocalDate? = null,
        listId: String = "list",
        created: Instant = Instant.parse("2026-11-01T00:00:00Z"),
        completedAt: Instant? = null,
    ) = TaskEntity(
        id = id, listId = listId, title = title, completed = completed, important = important, position = 0.0,
        createdUtc = created, modifiedUtc = created, completedUtc = completedAt, dueDate = due, dueTime = dueTime, myDayDate = myDay,
    )
}
