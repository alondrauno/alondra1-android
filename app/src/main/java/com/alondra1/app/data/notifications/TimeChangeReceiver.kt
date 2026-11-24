package com.alondra1.app.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alondra1.app.Alondra1App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Time zone or clock changed: re-plan every reminder from the database (wall-clock times stay the same). */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        val container = (context.applicationContext as Alondra1App).container
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.rescheduleAllReminders()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)
    }
}
