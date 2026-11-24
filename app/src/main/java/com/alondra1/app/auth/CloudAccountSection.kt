package com.alondra1.app.auth

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alondra1.app.AppContainer
import com.alondra1.app.data.sync.MergePreview
import com.alondra1.app.data.sync.SyncState
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings → Account & Cloud when the build has a cloud URL. Every status shown comes from the server. */
@Composable
fun CloudAccountSection(container: AppContainer) {
    val cloud = container.cloud ?: return LocalOnlyAccountSection()
    val session by cloud.session.state.collectAsStateWithLifecycle()
    val status by cloud.engine.status.collectAsStateWithLifecycle()
    val pending by remember { container.db.sync().observePending() }.collectAsStateWithLifecycle(initialValue = 0)
    val issues by remember { container.db.sync().observeIssues() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val openConflict = com.alondra1.app.features.notes.LocalOpenNoteConflict.current
    var signingIn by rememberSaveable { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Entitlements?>(null) }
    var planError by remember { mutableStateOf<String?>(null) }
    var space by remember { mutableStateOf<Space?>(null) }
    var preview by remember { mutableStateOf<MergePreview?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }

    suspend fun refreshPlan() {
        planError = null
        try {
            val s = session.tenantId?.let { id -> cloud.account.spaces().firstOrNull { it.id == id } } ?: cloud.account.personalSpace()
            space = s
            plan = cloud.account.entitlements(s.id)
        } catch (e: ApiException) {
            planError = if (e.isNetwork) "Connection unavailable. Your local apps keep working." else e.message
        }
    }
    LaunchedEffect(session.signedIn) { if (session.signedIn) refreshPlan() else { plan = null; space = null } }

    if (!session.signedIn) {
        ListItem(
            headlineContent = { Text("Using Alondra1 locally") },
            supportingContent = { Text("No account is needed. To-do, Notes and Calendar are free and work offline. An account is only for optional Cloud Sync.") },
        )
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { signingIn = true }, modifier = Modifier.testTag("signIn")) { Text("Log in or register") }
        }
        if (session.boundUser != null) {
            Text("Cloud Sync is stopped until you sign in again. Nothing on this device was deleted.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    } else {
        ListItem(
            headlineContent = { Text(session.email ?: "Signed in") },
            supportingContent = { Text("Signed in with an email code.") },
            trailingContent = { TextButton(onClick = { confirmLogout = true }) { Text("Log out") } },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Plan: " + (plan?.planName ?: "…")) },
            supportingContent = {
                Text(planError ?: when (plan?.state) {
                    "active" -> "Cloud Sync and Archive are included."
                    "grace" -> "The plan has ended; everything keeps working until " + (plan?.effectiveUntil ?: "soon") + "."
                    "read_only" -> "The plan has ended: cloud data is read-only. New changes stay on this device."
                    "inactive" -> "Free. Cloud Sync is a paid feature. Plans can't be bought in the app."
                    else -> "Checking…"
                })
            },
            modifier = Modifier.testTag("planStatus"),
        )
        HorizontalDivider()
        val syncAllowed = plan?.sync == true
        ListItem(
            headlineContent = { Text("Cloud Sync · " + syncLabel(status.state, session.syncEnabled)) },
            supportingContent = {
                Column {
                    Text(
                        when {
                            !session.syncEnabled && !syncAllowed -> "Tasks, lists, notes and events can sync between your devices with a paid plan. Nothing is uploaded until you turn it on."
                            !session.syncEnabled -> "Off. Turn it on to sync tasks, lists, notes and events with your other devices. Mail and contacts are not synced."
                            else -> status.message ?: "Tasks, lists, notes and events."
                        },
                    )
                    status.lastSynced?.let { Text("Last synced " + it.atZone(ZoneId.systemDefault()).format(fmt), style = MaterialTheme.typography.bodySmall) }
                    if (pending > 0) Text("$pending changes not yet in the cloud (saved on this device)", style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier.testTag("syncStatus"),
        )
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!session.syncEnabled) {
                Button(enabled = syncAllowed && !busy && plan?.syncWrite == true, modifier = Modifier.testTag("enableSync"), onClick = {
                    scope.launch { preview = cloud.engine.preview(null) }
                }) { Text("Turn on Cloud Sync") }
            } else {
                Button(enabled = !busy, modifier = Modifier.testTag("syncNow"), onClick = {
                    scope.launch { busy = true; cloud.engine.syncNow(); busy = false; refreshPlan() }
                }) { Text("Sync now") }
                OutlinedButton(onClick = { cloud.engine.pause(); cloud.onSyncConfigured(false) }) { Text("Pause") }
            }
        }
        if (issues.isNotEmpty()) {
            HorizontalDivider()
            Text("Sync issues", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            issues.take(20).forEach { issue ->
                ListItem(
                    headlineContent = { Text(issue.title) },
                    supportingContent = { Text(issue.message) },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            // A note conflict: compare both versions and choose (Notes spec 10.2).
                            if (issue.copyId != null) TextButton(onClick = { openConflict(issue.id) }, modifier = Modifier.testTag("compareIssue")) { Text("Compare") }
                            TextButton(onClick = { scope.launch { container.db.sync().dismissIssue(issue.id) } }) { Text("OK") }
                        }
                    },
                    modifier = Modifier.testTag("syncIssue"),
                )
            }
        }
    }
    HorizontalDivider()
    ListItem(
        headlineContent = { Text("Archive · Paid") },
        supportingContent = { Text("Cloud copies of selected emails, with search. Available in the Windows app; not in the Android app yet.") },
    )

    if (signingIn) SignInDialog(cloud.account, onDone = { signingIn = false })
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Turn on Cloud Sync?") },
            text = {
                Text("This device has ${p.localTasks} tasks in ${p.localLists} lists, ${p.localNotes} notes and ${p.localEvents} events. " +
                    "They will be merged with what is already in ${space?.name?.ifBlank { null } ?: "your personal space"} in the cloud: nothing is replaced or deleted. " +
                    "A backup of this device's data is made first. Mail and contacts are not synced.")
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("confirmMerge"), onClick = {
                    val target = space ?: return@TextButton
                    preview = null
                    scope.launch {
                        busy = true
                        cloud.engine.enable(target.id)
                        cloud.onSyncConfigured(true)
                        busy = false
                    }
                }) { Text("Merge and sync") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Keep local only") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("Cloud Sync stops on this device. Your tasks, notes and events stay here.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; scope.launch { cloud.onSyncConfigured(false); cloud.account.logout() } }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

fun syncLabel(state: SyncState, enabled: Boolean): String = when {
    !enabled && state != SyncState.PAUSED -> "Off"
    else -> when (state) {
        SyncState.SYNCED -> "Synced"
        SyncState.SYNCING, SyncState.BOOTSTRAPPING -> "Syncing…"
        SyncState.OFFLINE -> "Offline"
        SyncState.PAUSED -> "Paused"
        SyncState.PLAN_REQUIRED -> "Plan needed"
        SyncState.READ_ONLY_PLAN -> "Read-only"
        SyncState.AUTH_REQUIRED -> "Sign in again"
        SyncState.CONFLICT -> "Needs attention"
        SyncState.ERROR_RETRYABLE -> "Will retry"
        SyncState.ERROR_PERMANENT -> "Stopped"
        else -> "On"
    }
}

@Composable
private fun SignInDialog(account: AccountRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var challenge by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (challenge == null) "Log in or register" else "Enter the code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (challenge == null) {
                    Text("We email you a one-time code. There is no password.")
                    OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("signInEmail"))
                } else {
                    Text("If $email can sign in, a code is on its way. It is valid for 10 minutes.")
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6); error = null }, label = { Text("Code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().testTag("signInCode"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.testTag("signInContinue"), onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        if (challenge == null) challenge = account.start(email)
                        else { account.verify(email, challenge!!, code, Build.MODEL ?: "Android"); onDone() }
                    } catch (e: ApiException) {
                        error = if (e.isNetwork) "No connection. You can keep using Alondra1 locally." else e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (challenge == null) "Send code" else "Continue") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
