package com.alondra1.app.features.settings

import android.os.Build
import com.alondra1.app.AppContainer
import com.alondra1.app.BuildConfig
import java.time.Instant

/**
 * Version, environment and diagnostics for support (Dev-only spec §1.1 item 8). Never includes the email address,
 * tokens, ids, or task, note or file contents: only what helps tell an environment or a sync problem apart.
 */
object Diagnostics {
    val feedbackUrl: String get() = BuildConfig.FEEDBACK_URL

    fun environmentLabel(environment: String = BuildConfig.ENVIRONMENT) =
        if (environment == "dev") "Development and test version (dev)" else environment

    data class Facts(
        val version: String, val environment: String, val android: String, val signedIn: Boolean, val syncEnabled: Boolean,
        val syncState: String, val lastSynced: Instant?, val waitingChanges: Int, val filesWaiting: Int, val filesFailed: Int,
    )

    fun text(f: Facts): String = listOf(
        "Alondra1 for Android ${f.version} (${f.environment})",
        "Android: ${f.android}",
        "Signed in: ${if (f.signedIn) "yes" else "no"}",
        "Cloud Sync: ${if (f.syncEnabled) "on" else "off"}, state ${f.syncState}" + (f.lastSynced?.let { ", last synced $it" } ?: ""),
        "Changes waiting to sync: ${f.waitingChanges}",
        "Files waiting to upload: ${f.filesWaiting}, failed: ${f.filesFailed}",
    ).joinToString("\n")

    suspend fun collect(container: AppContainer): String {
        val session = container.cloud?.session?.state?.value
        val status = container.syncEngine?.status?.value
        val files = container.db.taskFiles().all()
        return text(Facts(
            version = BuildConfig.VERSION_NAME,
            environment = BuildConfig.ENVIRONMENT,
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            signedIn = session?.signedIn == true,
            syncEnabled = session?.syncEnabled == true,
            syncState = status?.state?.name ?: "LOCAL_ONLY",
            lastSynced = status?.lastSynced,
            waitingChanges = container.db.sync().outbox().size,
            filesWaiting = files.count { !it.uploaded && it.lastError == null },
            filesFailed = files.count { it.lastError != null },
        ))
    }
}
