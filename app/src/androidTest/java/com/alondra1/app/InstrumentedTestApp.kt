package com.alondra1.app

import androidx.room.Room
import com.alondra1.app.core.SystemTimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.notifications.RecordingReminderScheduler
import com.alondra1.app.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking

class InstrumentedTestApp : Alondra1App() {
    override fun onCreate() {
        container = AppContainer(
            db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build(),
            time = SystemTimeSource(),
            reminders = RecordingReminderScheduler(),
            settings = SettingsRepository(this),
        )
        runBlocking { container.settings.clear(); container.settings.setOnboardingComplete() }
        super.onCreate()
    }
}
