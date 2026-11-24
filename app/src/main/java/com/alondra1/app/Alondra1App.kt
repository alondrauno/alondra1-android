package com.alondra1.app

import android.app.Application
import com.alondra1.app.data.notifications.Notifications
import kotlinx.coroutines.launch

open class Alondra1App : Application() {
    /** Replaceable before first use (Robolectric tests install an in-memory container). */
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        if (!::container.isInitialized) container = AppContainer.create(this)
        Notifications.createChannels(this)
        // Resume background sync after an app update or reboot, only if the user turned it on.
        container.cloud?.let { if (it.session.state.value.syncEnabled) it.onSyncConfigured(true) }
        // Notes in Trash longer than 30 days are deleted for good (Notes spec §7).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { container.notes.purgeTrash() } }
    }
}
