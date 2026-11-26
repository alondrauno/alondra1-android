package com.alondra1.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.TaskFileEntity
import com.alondra1.app.domain.AttachmentState
import com.alondra1.app.domain.TaskAttachment
import com.alondra1.app.domain.TaskAttachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class TaskFileException(message: String) : Exception(message)

/**
 * Files attached to tasks (Android UI and task attachments spec §7, §10, §11). A picked file is copied into app-private
 * storage (files/task-files/<2 hex>/<sha256>) before the picker returns, so the task never depends on a content:// URI
 * staying readable. Files are stored once per SHA-256, which is also their id in cloud storage: an interrupted upload
 * is simply sent again, and the same file attached twice is kept once. Nothing here touches the network.
 */
class TaskFiles(private val db: AppDatabase, val directory: File, private val time: TimeSource) {
    /** The largest file accepted and attachments per task: the server's limits once known (shared by all instances). */
    val maxBytes: Long get() = limitBytes
    val maxPerTask: Int get() = limitPerTask

    fun fileOf(sha: String) = File(File(directory, sha.take(2)), sha)
    fun hasFile(sha: String) = TaskAttachments.isHash(sha) && fileOf(sha).isFile

    /**
     * Copies [uri] into the store and returns the attachment metadata to put on a task. The type is recognised from
     * the bytes when possible (never trusted from the name alone); the name is cleaned for display and saving.
     */
    suspend fun import(resolver: ContentResolver, uri: Uri): TaskAttachment = withContext(Dispatchers.IO) {
        var name: String? = null
        var declaredSize = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) declaredSize = c.getLong(1)
                }
            }
        }
        if (declaredSize > maxBytes) throw TaskFileException(tooLarge())
        val input = resolver.openInputStream(uri) ?: throw TaskFileException("The file could not be read.")
        input.use { store(it, TaskAttachments.safeName(name ?: uri.lastPathSegment), resolver.getType(uri)) }
    }

    /** Stores bytes from [input] (bounded by [maxBytes]) and returns the attachment. Used by [import] and tests. */
    suspend fun store(input: InputStream, fileName: String, declaredType: String?): TaskAttachment = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temporary = File(directory, "incoming-" + Ids.newId() + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(16)
        var headSize = 0
        var total = 0L
        try {
            temporary.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw TaskFileException(tooLarge())
                    if (headSize < head.size) { val k = minOf(n, head.size - headSize); System.arraycopy(buffer, 0, head, headSize, k); headSize += k }
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            if (total == 0L) throw TaskFileException("The file is empty.")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val type = sniff(head.copyOf(headSize)) ?: declaredType?.lowercase()?.substringBefore(';')?.trim()?.takeIf { MEDIA_TYPE.matches(it) } ?: "application/octet-stream"
            val target = fileOf(sha)
            val existing = db.taskFiles().get(sha)
            if (!target.isFile) {
                target.parentFile!!.mkdirs()
                if (!temporary.renameTo(target)) { temporary.copyTo(target, overwrite = true) }
            }
            db.taskFiles().put(TaskFileEntity(sha, type, total, existing?.createdUtc ?: time.now(), uploaded = existing?.uploaded ?: false, present = true))
            TaskAttachment(id = Ids.newId(), fileName = fileName, size = total, addedUtc = time.now(), sha256 = sha, mediaType = type)
        } finally {
            temporary.delete()
        }
    }

    /** Where an attachment's bytes are, for the status line. */
    fun state(a: TaskAttachment, file: TaskFileEntity?): AttachmentState = when {
        a.sha256 == null -> AttachmentState.ON_OTHER_DEVICE
        file?.lastError != null && !file.uploaded -> AttachmentState.FAILED
        hasFile(a.sha256) && file?.uploaded == true -> AttachmentState.SYNCED
        hasFile(a.sha256) -> AttachmentState.LOCAL_ONLY
        file?.uploaded == true || file == null -> AttachmentState.IN_CLOUD
        else -> AttachmentState.ON_OTHER_DEVICE
    }

    /** Keeps downloaded bytes only when they match the attachment's hash. */
    suspend fun addDownloaded(sha: String, data: ByteArray, mediaType: String?): Boolean = withContext(Dispatchers.IO) {
        if (hash(data) != sha) return@withContext false
        val target = fileOf(sha)
        target.parentFile!!.mkdirs()
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(data)
        if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
        val existing = db.taskFiles().get(sha)
        db.taskFiles().put(TaskFileEntity(sha, mediaType ?: existing?.mimeType ?: sniff(data) ?: "application/octet-stream", data.size.toLong(),
            existing?.createdUtc ?: time.now(), uploaded = true, present = true))
        true
    }

    /**
     * Removes stored files no task (live or in the undo window) lists any more. Run at start, not on every change, so
     * Undo can still restore an attachment; files that only finished arriving in the last day are kept.
     */
    suspend fun removeUnreferenced(): Int = withContext(Dispatchers.IO) {
        val referenced = db.tasks().allIncludingDeleted().flatMap { TaskAttachments.parse(it.attachmentsJson) }.mapNotNull { it.sha256 }.toSet()
        val cutoff = time.now().minusSeconds(24 * 3600)
        var removed = 0
        for (f in db.taskFiles().all()) {
            if (f.sha256 in referenced || f.createdUtc.isAfter(cutoff)) continue
            fileOf(f.sha256).delete()
            db.taskFiles().delete(f.sha256)
            removed++
        }
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        removed
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        directory.deleteRecursively()
        db.taskFiles().clear()
    }

    private fun tooLarge() = "A file can be at most ${maxBytes shr 20} MB."

    companion object {
        @Volatile var limitBytes: Long = TaskAttachments.DEFAULT_MAX_BYTES
        @Volatile var limitPerTask: Int = TaskAttachments.DEFAULT_MAX_PER_TASK

        private val MEDIA_TYPE = Regex("^[a-z0-9.+-]{1,50}/[a-z0-9.+-]{1,100}$")

        fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        /** The type from the first bytes, as the server recognises it (pictures, PDF, ZIP/Office); null when unknown. */
        fun sniff(d: ByteArray): String? = NoteAssets.sniff(d) ?: when {
            d.size >= 5 && String(d, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
            d.size >= 4 && d[0] == 'P'.code.toByte() && d[1] == 'K'.code.toByte() && (d[2].toInt() == 3 || d[2].toInt() == 5) -> null // ZIP or Office: keep the declared type
            else -> null
        }
    }
}
