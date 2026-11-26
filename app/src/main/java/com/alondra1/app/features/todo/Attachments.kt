package com.alondra1.app.features.todo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alondra1.app.AppContainer
import com.alondra1.app.auth.ApiException
import com.alondra1.app.data.local.TaskFileEntity
import com.alondra1.app.data.repository.TaskFileException
import com.alondra1.app.domain.AttachmentState
import com.alondra1.app.domain.TaskAttachment
import com.alondra1.app.domain.TaskAttachments
import com.alondra1.app.ui.LocalContainer
import com.alondra1.app.ui.LocalSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Picks files with the system document picker (Storage Access Framework: no storage permission, several files at
 * once) and copies each into the app's task-file store before returning, so the task never depends on the picked
 * URI staying readable. [onImported] gets the attachments; problems are shown in the snackbar.
 */
@Composable
fun rememberAttachmentPicker(currentCount: () -> Int, onImported: (List<TaskAttachment>) -> Unit): () -> Unit {
    val container = LocalContainer.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val room = container.taskFiles.maxPerTask - currentCount()
            val imported = mutableListOf<TaskAttachment>()
            val problems = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                if (i >= room) { problems += "A task can have at most ${container.taskFiles.maxPerTask} files."; return@forEachIndexed }
                try {
                    imported += container.taskFiles.import(context.contentResolver, uri)
                } catch (e: TaskFileException) {
                    problems += e.message ?: "A file could not be attached."
                } catch (e: Exception) {
                    problems += "A file could not be read."
                }
            }
            if (imported.isNotEmpty()) onImported(imported)
            problems.distinct().firstOrNull()?.let { snackbar.showSnackbar(it) }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Task detail: the files attached to [taskId], with status, open, share and remove. */
@Composable
fun AttachmentsSection(taskId: String, attachmentsJson: String, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val attachments = remember(attachmentsJson) { TaskAttachments.parse(attachmentsJson) }
    val shas = attachments.mapNotNull { it.sha256 }
    val files by remember(shas) { if (shas.isEmpty()) flowOf(emptyList()) else container.db.taskFiles().observe(shas) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val byHash = files.associateBy { it.sha256 }
    var busy by remember { mutableStateOf<String?>(null) }
    val pick = rememberAttachmentPicker({ attachments.size }) { added -> scope.launch { container.todo.addAttachments(taskId, added) } }
    val session = remember(container) { container.cloud?.session?.state ?: kotlinx.coroutines.flow.MutableStateFlow(null) }
    val sessionState by session.collectAsStateWithLifecycle()
    val cloudOn = sessionState?.let { it.signedIn && it.syncEnabled } == true

    fun act(a: TaskAttachment, share: Boolean) {
        scope.launch {
            busy = a.id
            val message = AttachmentOpener.open(context, container, a, share)
            busy = null
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    Column(modifier.fillMaxWidth().testTag("attachments"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Attachments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { this.contentDescription = "Attachments, ${attachments.size}" })
            TextButton(onClick = pick, enabled = attachments.size < container.taskFiles.maxPerTask, modifier = Modifier.testTag("addAttachment")) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Add attachment")
            }
        }
        if (attachments.isEmpty()) {
            Text(
                if (cloudOn) "Attach files from your device. They sync to your other devices."
                else "Attach files from your device. Without Cloud Sync they stay on this device only and are not backed up.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                attachments.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider()
                    val file = a.sha256?.let { byHash[it] }
                    AttachmentRow(
                        attachment = a, file = file, state = container.taskFiles.state(a, file), cloudOn = cloudOn, busy = busy == a.id,
                        onOpen = { act(a, share = false) }, onShare = { act(a, share = true) },
                        onRemove = {
                            scope.launch {
                                val before = container.todo.getTask(taskId)
                                container.todo.removeAttachment(taskId, a.id)
                                val result = snackbar.showSnackbar("Removed ${a.fileName}", actionLabel = "Undo", withDismissAction = true)
                                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed && before != null) {
                                    container.todo.getTask(taskId)?.let { now -> container.todo.updateTask(now.copy(attachmentsJson = before.attachmentsJson)) }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (!cloudOn) {
            Text("These files are on this device only: not backed up and not on your other devices until Cloud Sync is on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: TaskAttachment,
    file: TaskFileEntity?,
    state: AttachmentState,
    cloudOn: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
) {
    val container = LocalContainer.current
    var menu by remember { mutableStateOf(false) }
    val status = when (state) {
        AttachmentState.SYNCED -> "Synced"
        AttachmentState.LOCAL_ONLY -> if (cloudOn) "Waiting to sync" else "On this device only"
        AttachmentState.IN_CLOUD -> "In the cloud · tap to download"
        AttachmentState.ON_OTHER_DEVICE -> "Not uploaded yet from another device"
        AttachmentState.FAILED -> "Not synced: " + (file?.lastError ?: "upload failed")
    }
    val type = attachment.mediaType ?: file?.mimeType
    var thumb by remember(attachment.sha256, state) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.sha256, state) {
        val sha = attachment.sha256
        if (sha != null && attachment.isPicture && container.taskFiles.hasFile(sha)) {
            thumb = withContext(Dispatchers.IO) { runCatching { Thumbnails.decode(container.taskFiles.fileOf(sha), 96) }.getOrNull() }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = "Open ${attachment.fileName}", onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .semantics { stateDescription = status }
            .testTag("attachment:${attachment.fileName}"),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val image = thumb
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Icon(iconFor(type, state), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(attachment.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(TaskAttachments.sizeLabel(attachment.size) + " · " + status, style = MaterialTheme.typography.bodySmall,
                color = if (state == AttachmentState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Opening" }, strokeWidth = 2.dp)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${attachment.fileName}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Share or save…") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Remove from task", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

private fun iconFor(type: String?, state: AttachmentState): ImageVector = when {
    state == AttachmentState.IN_CLOUD -> Icons.Outlined.CloudDownload
    type?.startsWith("image/") == true -> Icons.Outlined.Image
    type == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** Opens or shares attachments through a FileProvider copy named for the user, with a temporary read grant. */
object AttachmentOpener {
    /** Returns a message for the user when it could not be opened, null when an app was started. */
    suspend fun open(context: Context, container: AppContainer, a: TaskAttachment, share: Boolean): String? {
        val sha = a.sha256 ?: return "This file is only on the device that added it."
        if (!container.taskFiles.hasFile(sha)) {
            val engine = container.syncEngine ?: return "This file is on another device. Turn on Cloud Sync to get it here."
            val got = try { engine.downloadTaskFile(sha, a.mediaType) } catch (e: ApiException) {
                return if (e.isNetwork) "You're offline. The file downloads when you're back online." else "The file could not be downloaded."
            }
            if (!got) return "This file hasn't been uploaded from the other device yet."
        }
        val outgoing = withContext(Dispatchers.IO) { copyOut(context, container.taskFiles.fileOf(sha), a) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", outgoing)
        val type = a.mediaType ?: container.db.taskFiles().get(sha)?.mimeType ?: "application/octet-stream"
        val intent = if (share) {
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), a.fileName)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: ActivityNotFoundException) {
            "No app on this device opens this type of file. Use Share or save instead."
        }
    }

    /** cache/outgoing/<attachment id>/<safe name>: the only shareable location; old copies are removed. */
    private fun copyOut(context: Context, source: File, a: TaskAttachment): File {
        val root = File(context.cacheDir, "outgoing")
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.deleteRecursively() }
        val dir = File(root, a.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "file" })
        dir.mkdirs()
        val target = File(dir, TaskAttachments.safeName(a.fileName))
        if (!target.isFile || target.length() != source.length()) source.copyTo(target, overwrite = true)
        return target
    }

    @Suppress("unused")
    private fun Uri.debug() = toString()
}

object Thumbnails {
    /** A small preview, decoded at about [px] pixels so a large photo never loads whole. */
    fun decode(file: File, px: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}
