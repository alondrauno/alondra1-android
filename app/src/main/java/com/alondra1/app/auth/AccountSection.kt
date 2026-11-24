package com.alondra1.app.auth

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Account & Cloud in Settings while no cloud account is connected. Accurate status only: Cloud Sync and Archive
 * are optional paid features and are shown as not active.
 */
@Composable
fun LocalOnlyAccountSection() {
    ListItem(
        headlineContent = { Text("Using Alondra1 locally") },
        supportingContent = { Text("No account is needed. To-do, Notes and Calendar are free and work offline.") },
    )
    HorizontalDivider()
    ListItem(
        headlineContent = { Text("Cloud Sync · Paid") },
        supportingContent = { Text("Not active. Your data stays on this device and is not backed up to the cloud.") },
    )
    HorizontalDivider()
    ListItem(
        headlineContent = { Text("Archive · Paid") },
        supportingContent = { Text("Mail archive and search. Not available in the Android app yet.") },
    )
}
