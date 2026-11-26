package com.alondra1.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * A file attached to a task (contract todo-v1 TaskAttachment): metadata only, the bytes are separate. [sha256] is the
 * file's id in cloud storage and in this device's task-file store; [relativePath] is set by the Windows app for its
 * own local copy and is meaningless here. Unknown keys of other clients are kept by [TaskAttachments].
 */
data class TaskAttachment(
    val id: String,
    val fileName: String,
    val size: Long,
    val addedUtc: Instant?,
    val sha256: String?,
    val mediaType: String?,
    val relativePath: String? = null,
) {
    /** Picture types shown as a preview. */
    val isPicture: Boolean get() = mediaType in PICTURE_TYPES

    companion object {
        val PICTURE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}

/** Where an attachment's bytes are, as the task detail screen shows it. */
enum class AttachmentState {
    /** On this device; not in the cloud (Cloud Sync off, or waiting to upload). */
    LOCAL_ONLY,
    /** On this device and in the cloud. */
    SYNCED,
    /** In the cloud, not downloaded to this device yet (opened on demand). */
    IN_CLOUD,
    /** Added on another device that has not uploaded it (yet), or only kept by the Windows app. */
    ON_OTHER_DEVICE,
    /** The last upload failed for good (too large, no room); see the file's error. */
    FAILED,
}

object TaskAttachments {
    /** Defaults until the server's limits (GET /v1/task-files/limits) are known; product decision in the spec §7.3. */
    const val DEFAULT_MAX_PER_TASK = 10
    const val DEFAULT_MAX_BYTES = 25L shl 20
    const val NAME_MAX = 260

    private val json = Json { ignoreUnknownKeys = true }

    fun array(text: String?): List<JsonObject> =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty().mapNotNull { it as? JsonObject }

    fun parse(text: String?): List<TaskAttachment> = array(text).mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        TaskAttachment(
            id = id,
            fileName = o.str("fileName").orEmpty().ifBlank { "File" },
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            addedUtc = o.str("addedUtc")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() },
            sha256 = o.str("sha256")?.takeIf(::isHash),
            mediaType = o.str("mediaType"),
            relativePath = o.str("relativePath")?.takeIf { it.isNotEmpty() },
        )
    }

    fun encode(a: TaskAttachment): JsonObject = JsonObject(buildMap {
        put("id", JsonPrimitive(a.id))
        put("fileName", JsonPrimitive(a.fileName))
        put("size", JsonPrimitive(a.size))
        put("addedUtc", JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(a.addedUtc ?: Instant.EPOCH)))
        a.sha256?.let { put("sha256", JsonPrimitive(it)) }
        a.mediaType?.let { put("mediaType", JsonPrimitive(it)) }
        a.relativePath?.let { put("relativePath", JsonPrimitive(it)) }
    })

    /** Appends [added] to the stored array, keeping every existing entry exactly as it was (unknown keys included). */
    fun add(text: String?, added: List<TaskAttachment>): String = JsonArray(array(text) + added.map(::encode)).toString()

    fun remove(text: String?, id: String): String = JsonArray(array(text).filterNot { it.str("id") == id }).toString()

    fun count(text: String?): Int = if (text.isNullOrEmpty() || text == "[]") 0 else array(text).size

    fun isHash(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** A display name safe to use as a file name anywhere: no path, no control or reserved characters, bounded. */
    fun safeName(raw: String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trim('.').take(NAME_MAX)
        return cleaned.ifBlank { "file" }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

@Suppress("unused")
private fun JsonObject.debug() = this.jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull }
