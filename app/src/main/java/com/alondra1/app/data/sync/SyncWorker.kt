package com.alondra1.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alondra1.app.Alondra1App
import java.util.concurrent.TimeUnit

/** Background sync: only with a network, with exponential backoff, never a tight loop. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = (applicationContext as Alondra1App).container.syncEngine ?: return Result.success()
        return when (engine.syncNow().state) {
            SyncState.OFFLINE, SyncState.ERROR_RETRYABLE -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val SOON = "alondra1-sync-soon"
        private const val PERIODIC = "alondra1-sync-periodic"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** A few seconds after the last edit (each edit replaces the pending run: a debounce). */
        fun requestSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(5, TimeUnit.SECONDS).setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
