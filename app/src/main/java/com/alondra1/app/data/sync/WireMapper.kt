package com.alondra1.app.data.sync

import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.NoteEntity
import com.alondra1.app.data.local.TaskEntity
import com.alondra1.app.data.local.TaskListEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Converts local records to the shared wire formats (contract/schemas: todo-v1, notes-v1, calendar-v1) and back.
 *
 * Outgoing objects start from the last server copy ([TaskEntity.remoteJson]) so fields this app does not edit yet
 * (recurrence, steps, attachments, start date, list groups) are sent back unchanged instead of being erased.
 */
object WireMapper {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun base(remoteJson: String?): MutableMap<String, JsonElement> =
        remoteJson?.let { runCatching { json.parseToJsonElement(it).jsonObject.toMutableMap() }.getOrNull() } ?: mutableMapOf()

    private fun MutableMap<String, JsonElement>.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else this[key] = JsonPrimitive(value)
    }

    private fun utc(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    /** Seconds always included: the contract's local date-time pattern requires them. */
    private fun localDateTime(value: LocalDateTime) = value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    private fun localTime(value: LocalTime) = value.format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    fun reminderId(taskId: String) = (taskId + "-r").take(64)

    fun task(t: TaskEntity): JsonObject {
        val m = base(t.remoteJson)
        m["id"] = JsonPrimitive(t.id)
        m["listId"] = JsonPrimitive(t.listId)
        m["title"] = JsonPrimitive(t.title)
        m["notes"] = JsonPrimitive(t.notes)
        m["completed"] = JsonPrimitive(t.completed)
        m["important"] = JsonPrimitive(t.important)
        m["position"] = JsonPrimitive(t.position)
        m["createdUtc"] = JsonPrimitive(utc(t.createdUtc))
        m["modifiedUtc"] = JsonPrimitive(utc(t.modifiedUtc))
        m.putOrRemove("completedUtc", t.completedUtc?.let(::utc))
        m.putOrRemove("dueDate", t.dueDate?.toString())
        m.putOrRemove("dueTime", t.dueTime?.let(::localTime))
        m.putOrRemove("myDayDate", t.myDayDate?.toString())
        // Phase 1 edits one reminder; extra reminders from another device are kept after it.
        val others = (m["reminders"] as? JsonArray)?.filter { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull != reminderId(t.id) }.orEmpty()
        m["reminders"] = buildJsonArray {
            t.reminderAt?.let { add(buildJsonObject { put("id", reminderId(t.id)); put("at", localDateTime(it)) }) }
            others.forEach { add(stripReminderState(it)) }
        }
        return JsonObject(m)
    }

    /** Reminder delivery state is per device and never synced. */
    private fun stripReminderState(e: JsonElement): JsonElement =
        JsonObject(e.jsonObject.filterKeys { it == "id" || it == "at" })

    fun list(l: TaskListEntity): JsonObject {
        val m = base(l.remoteJson)
        m["id"] = JsonPrimitive(l.id)
        m["name"] = JsonPrimitive(l.name)
        m["position"] = JsonPrimitive(l.position)
        m["isDefault"] = JsonPrimitive(l.isDefault)
        m["createdUtc"] = JsonPrimitive(utc(l.createdUtc))
        m.putOrRemove("color", l.color)
        return JsonObject(m)
    }

    /** notes-v1 with the rich fields (doc, folder, favorite, archive/trash, pictures, links). */
    fun note(n: NoteEntity): JsonObject {
        val m = base(n.remoteJson)
        m["id"] = JsonPrimitive(n.id); m["title"] = JsonPrimitive(n.title); m["body"] = JsonPrimitive(n.body); m["pinned"] = JsonPrimitive(n.pinned)
        m["favorite"] = JsonPrimitive(n.favorite)
        val doc = n.docJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (doc != null) m["doc"] = doc else m.remove("doc")
        m.putOrRemove("folderId", n.folderId)
        m.putOrRemove("archivedUtc", n.archivedUtc?.let(::utc))
        m.putOrRemove("trashedUtc", n.trashedUtc?.let(::utc))
        m["assets"] = json.parseToJsonElement(n.assetsJson)
        m["links"] = json.parseToJsonElement(n.linksJson)
        m["createdUtc"] = JsonPrimitive(utc(n.createdUtc)); m["modifiedUtc"] = JsonPrimitive(utc(n.modifiedUtc))
        return JsonObject(m)
    }

    fun folder(f: com.alondra1.app.data.local.NoteFolderEntity): JsonObject {
        val m = base(f.remoteJson)
        m["id"] = JsonPrimitive(f.id); m["name"] = JsonPrimitive(f.name); m["position"] = JsonPrimitive(f.position)
        m.putOrRemove("parentId", f.parentId)
        m["createdUtc"] = JsonPrimitive(utc(f.createdUtc)); m["modifiedUtc"] = JsonPrimitive(utc(f.modifiedUtc))
        return JsonObject(m)
    }

    fun folderFrom(o: JsonObject, version: Long): com.alondra1.app.data.local.NoteFolderEntity = com.alondra1.app.data.local.NoteFolderEntity(
        id = o.str("id")!!, parentId = o.str("parentId"), name = o.str("name").orEmpty().ifEmpty { "Folder" },
        position = (o["position"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        createdUtc = instant(o.str("createdUtc")) ?: Instant.now(), modifiedUtc = instant(o.str("modifiedUtc")) ?: Instant.now(),
        serverVersion = version, remoteJson = o.toString(),
    )

    fun event(e: EventEntity): JsonObject = buildJsonObject {
        put("id", e.id); put("title", e.title); put("description", e.description); put("allDay", e.allDay)
        if (e.allDay) {
            put("startDate", e.startDate.toString()); put("endDate", (e.endDate ?: e.startDate).toString())
        } else {
            put("startUtc", utc(e.startUtc!!)); put("endUtc", utc(e.endUtc ?: e.startUtc))
        }
        put("timeZone", e.timeZoneId)
        e.reminderMinutes?.let { put("reminderMinutes", it) }
        put("createdUtc", utc(e.createdUtc)); put("modifiedUtc", utc(e.modifiedUtc))
    }

    // ------------------------------------------------------------------------------------------------ incoming

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun instant(value: String?): Instant? = value?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    fun taskFrom(o: JsonObject, version: Long, local: TaskEntity?): TaskEntity {
        val reminder = (o["reminders"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("at")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val now = Instant.now()
        return TaskEntity(
            id = o.str("id")!!,
            listId = o.str("listId")!!,
            title = o.str("title").orEmpty(),
            notes = o.str("notes").orEmpty(),
            completed = o.bool("completed"),
            important = o.bool("important"),
            position = (o["position"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            createdUtc = instant(o.str("createdUtc")) ?: local?.createdUtc ?: now,
            modifiedUtc = instant(o.str("modifiedUtc")) ?: now,
            completedUtc = instant(o.str("completedUtc")),
            dueDate = o.str("dueDate")?.let(LocalDate::parse),
            dueTime = o.str("dueTime")?.let { LocalTime.parse(it) },
            myDayDate = o.str("myDayDate")?.let(LocalDate::parse),
            reminderAt = reminder,
            sourceNoteId = local?.sourceNoteId,
            deletedUtc = null,
            remoteJson = o.toString(),
            serverVersion = version,
        )
    }

    fun listFrom(o: JsonObject, version: Long, local: TaskListEntity?): TaskListEntity = TaskListEntity(
        id = o.str("id")!!,
        name = o.str("name").orEmpty().ifBlank { "List" },
        color = o.str("color"),
        position = (o["position"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        isDefault = o.bool("isDefault"),
        createdUtc = instant(o.str("createdUtc")) ?: local?.createdUtc ?: Instant.now(),
        modifiedUtc = Instant.now(),
        deletedUtc = null,
        remoteJson = o.toString(),
        serverVersion = version,
    )

    fun noteFrom(o: JsonObject, version: Long): NoteEntity = NoteEntity(
        id = o.str("id")!!, title = o.str("title").orEmpty(), body = o.str("body").orEmpty(), pinned = o.bool("pinned"),
        createdUtc = instant(o.str("createdUtc")) ?: Instant.now(), modifiedUtc = instant(o.str("modifiedUtc")) ?: Instant.now(),
        deletedUtc = null, serverVersion = version,
        docJson = (o["doc"] as? JsonObject)?.toString(), folderId = o.str("folderId"), favorite = o.bool("favorite"),
        archivedUtc = instant(o.str("archivedUtc")), trashedUtc = instant(o.str("trashedUtc")),
        assetsJson = (o["assets"] as? JsonArray)?.toString() ?: "[]", linksJson = (o["links"] as? JsonArray)?.toString() ?: "[]",
        remoteJson = o.toString(),
    )

    fun eventFrom(o: JsonObject, version: Long): EventEntity {
        val allDay = o.bool("allDay")
        return EventEntity(
            id = o.str("id")!!, title = o.str("title").orEmpty(), description = o.str("description").orEmpty(), allDay = allDay,
            startUtc = if (allDay) null else instant(o.str("startUtc")), endUtc = if (allDay) null else instant(o.str("endUtc")),
            startDate = if (allDay) o.str("startDate")?.let(LocalDate::parse) else null,
            endDate = if (allDay) o.str("endDate")?.let(LocalDate::parse) else null,
            timeZoneId = o.str("timeZone") ?: "UTC",
            reminderMinutes = (o["reminderMinutes"] as? JsonPrimitive)?.intOrNull,
            createdUtc = instant(o.str("createdUtc")) ?: Instant.now(), modifiedUtc = instant(o.str("modifiedUtc")) ?: Instant.now(),
            deletedUtc = null, serverVersion = version,
        )
    }

    fun isNull(e: JsonElement?) = e == null || e is JsonNull
}
