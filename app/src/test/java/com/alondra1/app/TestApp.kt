package com.alondra1.app

import kotlinx.coroutines.runBlocking

/** Robolectric application: in-memory database, recorded reminders, first run already completed. */
class TestApp : Alondra1App() {
    override fun onCreate() {
        container = TestSupport.container()
        runBlocking { container.settings.setOnboardingComplete() }
        super.onCreate()
    }
}
