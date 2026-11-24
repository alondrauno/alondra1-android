package com.alondra1.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs instrumented tests against [InstrumentedTestApp] (in-memory database, no real reminders). */
class InstrumentedTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, InstrumentedTestApp::class.java.name, context)
}
