package com.alondra1.app.data.sync

import androidx.room.withTransaction
import com.alondra1.app.auth.ApiClient
import com.alondra1.app.auth.ApiException
import com.alondra1.app.auth.SessionStore
import com.alondra1.app.core.Ids
import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.OutboxEntity
import com.alondra1.app.data.local.SyncIssueEntity
import com.alondra1.app.data.notifications.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/** Sync states shown to the user (Windows integration spec §6.6, same names on both clients). */
enum class SyncState { LOCAL_ONLY, SIGN_IN_REQUIRED, PLAN_REQUIRED, READY_OFF, BOOTSTRAPPING, SYNCING, SYNCED, OFFLINE, PAUSED, AUTH_REQUIRED, READ_ONLY_PLAN, CONFLICT, ERROR_RETRYABLE, ERROR_PERMANENT }

data class SyncStatus(val state: SyncState = SyncState.LOCAL_ONLY, val lastSynced: Instant? = null, val message: String? = null)

data class MergePreview(val localTasks: Int, val localLists: Int, val localNotes: Int, val localEvents: Int, val cloudObjects: Long?)

/**
 * Optional paid Cloud Sync. The local database stays the source of the UI; this replicates authorized changes.
 * Push first (from the durable outbox), then pull (ordered change feed); a pulled change never overwrites an item
 * with unsent local edits. Conflicts keep both versions: the server copy under the original id, the local version as
 * a conflict copy, plus a "Sync issues" entry. Nothing here ever deletes local data because of a server error.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val api: ApiClient,
    private val session: SessionStore,
    private val time: TimeSource,
    private val reminders: ReminderScheduler,
    private val backupDir: File?,
    /** Pictures in notes (null: notes sync without pictures, e.g. in tests that do not need them). */
    private val assets: com.alondra1.app.data.repository.NoteAssets? = null,
    /** Files attached to tasks (null: tasks sync with their attachment metadata only). */
    private val taskFiles: com.alondra1.app.data.repository.TaskFiles? = null,
) {
    private val lock = Mutex()
    private val sync = db.sync()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private fun set(state: SyncState, message: String? = null, synced: Boolean = false) {
        _status.value = SyncStatus(state, if (synced) time.now() else _status.value.lastSynced, message)
    }

    // ------------------------------------------------------------------------------------------- enable / disable

    suspend fun preview(cloudObjects: Long?): MergePreview = MergePreview(
        localTasks = db.tasks().all().size,
        localLists = db.taskLists().active().size,
        localNotes = db.notes().all().size,
        localEvents = db.events().all().size,
        cloudObjects = cloudObjects,
    )

    /**
     * First sync for [tenantId] (after the user confirmed the merge preview): back up the local database, download the
     * cloud copy, merge it, queue everything local the cloud does not have, then sync.
     */
    suspend fun enable(tenantId: String): SyncStatus = lock.withLock {
        set(SyncState.BOOTSTRAPPING)
        try {
            backupDatabase()
            session.setSync(tenantId, true)
            bootstrap(tenantId)
        } catch (e: ApiException) {
            return@withLock fail(e)
        }
        null
    }.let { it ?: syncNow() }

    /** Stop syncing on this device. Local data stays; queued changes stay queued for a later re-enable. */
    fun pause() {
        session.setSync(session.state.value.tenantId, false)
        set(SyncState.PAUSED)
    }

    private fun backupDatabase() {
        val dir = backupDir ?: return
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            val source = File(db.openHelper.writableDatabase.path ?: return)
            dir.mkdirs()
            source.copyTo(File(dir, "pre-sync-${time.now().toEpochMilli()}.db"), overwrite = true)
            // Keep the three newest backups.
            dir.listFiles { f -> f.name.startsWith("pre-sync-") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------------------------------- bootstrap

    private suspend fun bootstrap(tenantId: String) {
        var pageToken: String? = null
        var cursor: String
        val remoteIds = mutableMapOf<String, MutableSet<String>>()
        do {
            val path = "/v1/sync/bootstrap?limit=500" + (pageToken?.let { "&page_token=$it" } ?: "")
            val page = api.call("GET", path, tenant = tenantId)
            cursor = page["cursor"]!!.jsonPrimitive.content
            val items = (page["items"] as? JsonArray).orEmpty().map { it.jsonObject }
            db.withTransaction {
                items.forEach { item ->
                    val type = item["resource_type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    item["resource_id"]?.jsonPrimitive?.contentOrNull?.let { remoteIds.getOrPut(type) { mutableSetOf() }.add(it) }
                    applyUpsert(item, merging = true)
                }
            }
            pageToken = page["next_page_token"]?.jsonPrimitive?.contentOrNull
        } while (pageToken != null)

        db.withTransaction {
            adoptRemoteDefaultList()
            // Everything local that the cloud does not have yet goes up as a create.
            db.listGroups().active().filter { it.serverVersion == null && it.id !in remoteIds["group"].orEmpty() }.forEach { enqueue(EntityType.LIST_GROUP, it.id) }
            db.taskLists().active().filter { it.serverVersion == null && it.id !in remoteIds["list"].orEmpty() }.forEach { enqueue(EntityType.TASK_LIST, it.id) }
            db.tasks().all().filter { it.serverVersion == null && it.id !in remoteIds["task"].orEmpty() }.forEach { enqueue(EntityType.TASK, it.id) }
            db.noteFolders().active().filter { it.serverVersion == null && it.id !in remoteIds["note_folder"].orEmpty() }.forEach { enqueue(EntityType.NOTE_FOLDER, it.id) }
            db.notes().all().filter { it.serverVersion == null && it.id !in remoteIds["note"].orEmpty() }.forEach { enqueue(EntityType.NOTE, it.id) }
            db.events().all().filter { it.serverVersion == null && it.id !in remoteIds["event"].orEmpty() }.forEach { enqueue(EntityType.EVENT, it.id) }
            session.setCursor(cursor)
        }
    }

    /** The cloud has one default list per person. A local default list that never synced gives its tasks to it. */
    private suspend fun adoptRemoteDefaultList() {
        val lists = db.taskLists().active()
        val remoteDefault = lists.firstOrNull { it.isDefault && it.serverVersion != null } ?: return
        lists.filter { it.isDefault && it.serverVersion == null && it.id != remoteDefault.id }.forEach { local ->
            db.tasks().inList(local.id).forEach { task ->
                db.tasks().put(task.copy(listId = remoteDefault.id, modifiedUtc = time.now()))
                if (task.serverVersion != null) enqueue(EntityType.TASK, task.id)
            }
            db.taskLists().put(local.copy(isDefault = false, deletedUtc = time.now(), modifiedUtc = time.now()))
            sync.find(EntityType.TASK_LIST.wire, local.id)?.let { sync.complete(it.entityType, it.entityId, it.mutationId) }
        }
    }

    // ------------------------------------------------------------------------------------------- outbox

    /** Called by [OutboxRecorder] for every local change once sync is set up (also while paused). */
    suspend fun enqueue(type: EntityType, id: String) {
        val existing = sync.find(type.wire, id)
        val mutationId = if (existing != null && !existing.attempted) existing.mutationId else Ids.newId()
        sync.upsert(OutboxEntity(seq = existing?.seq ?: 0, entityType = type.wire, entityId = id, mutationId = mutationId, attempted = false,
            createdUtc = existing?.createdUtc ?: time.now().toEpochMilli()))
    }

    // ------------------------------------------------------------------------------------------- sync now

    suspend fun syncNow(): SyncStatus = lock.withLock {
        val s = session.state.value
        when {
            !api.configured -> set(SyncState.LOCAL_ONLY)
            !s.signedIn -> set(SyncState.SIGN_IN_REQUIRED)
            s.tenantId == null -> set(SyncState.READY_OFF)
            !s.syncEnabled -> set(SyncState.PAUSED)
            else -> try {
                set(SyncState.SYNCING)
                push(s.tenantId)
                pull(s.tenantId)
                downloadAssets(s.tenantId)
                refreshTaskFileLimits(s.tenantId)
                // Conflict copies are created while pushing: send them in this same run, not at the next sync.
                if (sync.outbox().any { !it.attempted }) push(s.tenantId)
                val issues = sync.outbox().size
                set(SyncState.SYNCED, if (issues > 0) "$issues changes still waiting" else null, synced = true)
            } catch (e: ApiException) {
                fail(e)
            }
        }
        _status.value
    }

    private fun fail(e: ApiException): SyncStatus {
        when {
            e.isNetwork -> set(SyncState.OFFLINE, "Offline. Changes are saved on this device and will sync later.")
            e.code == "ENTITLEMENT_REQUIRED" -> {
                val readOnly = e.body?.get("entitlement_state")?.jsonPrimitive?.contentOrNull == "read_only"
                set(if (readOnly) SyncState.READ_ONLY_PLAN else SyncState.PLAN_REQUIRED,
                    if (readOnly) "The plan ended: cloud data is read-only. New changes stay on this device." else "Cloud Sync needs a paid plan for this space. Your data stays on this device.")
            }
            e.code == "UNAUTHENTICATED" || e.status == 401 -> set(SyncState.AUTH_REQUIRED, "Sign in again to resume Cloud Sync. Nothing was deleted.")
            e.code == "TENANT_NOT_FOUND" -> {
                session.setSync(session.state.value.tenantId, false)
                set(SyncState.ERROR_PERMANENT, "You no longer have access to this space. Sync stopped; your local data is kept.")
            }
            e.status >= 500 || e.status == 429 || e.status == 408 -> set(SyncState.ERROR_RETRYABLE, "The cloud is busy. Alondra1 will retry.")
            else -> set(SyncState.ERROR_PERMANENT, e.message)
        }
        return _status.value
    }

    private val typeOrder = listOf(EntityType.LIST_GROUP.wire, EntityType.TASK_LIST.wire, EntityType.TASK.wire, EntityType.NOTE_FOLDER.wire, EntityType.NOTE.wire, EntityType.EVENT.wire)

    /** mutation id -> completion record sent with it (append_completion), marked sent when the mutation is applied. */
    private val sentCompletions = mutableMapOf<String, String>()

    private suspend fun push(tenantId: String) {
        val pending = sync.outbox().sortedWith(compareBy({ typeOrder.indexOf(it.entityType) }, { it.seq }))
        val batch = mutableListOf<Pair<OutboxEntity, JsonObject>>()
        var batchBytes = 0
        for (row in pending) {
            val mutation = mutationFor(row)
            if (mutation == null) { sync.complete(row.entityType, row.entityId, row.mutationId); continue }
            if (row.entityType == EntityType.NOTE.wire) uploadAssets(tenantId, mutation)
            if (row.entityType == EntityType.TASK.wire) uploadTaskFiles(tenantId, mutation)
            val size = mutation.toString().toByteArray().size
            // Requests stay below the server's 8 MiB limit: rich notes can be large.
            if (batch.isNotEmpty() && (batch.size >= 100 || batchBytes + size > MAX_PUSH_BYTES)) { send(tenantId, batch); batch.clear(); batchBytes = 0 }
            batch += row to mutation
            batchBytes += size
        }
        if (batch.isNotEmpty()) send(tenantId, batch)
    }

    private suspend fun send(tenantId: String, mutations: List<Pair<OutboxEntity, JsonObject>>) {
        sync.markAttempted(mutations.map { it.first.seq })
        val response = api.call("POST", "/v1/sync/push", buildJsonObject { put("mutations", buildJsonArray { mutations.forEach { add(it.second) } }) }, tenantId)
        val results = (response["results"] as? JsonArray).orEmpty().map { it.jsonObject }
        results.forEachIndexed { i, result -> handleResult(mutations[i].first, result) }
    }

    /** Pictures go up before the note that uses them; content-addressed, so a picture already in the cloud is not resent. */
    private suspend fun uploadAssets(tenantId: String, mutation: JsonObject) {
        val store = assets ?: return
        val note = (mutation["resource"] ?: mutation["changes"]) as? JsonObject ?: return
        (note["assets"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.forEach { sha ->
            val (asset, bytes) = store.read(sha) ?: return@forEach
            if (asset.uploaded) return@forEach
            api.putBytes("/v1/note-assets/$sha", bytes, asset.mimeType, tenantId)
            db.noteAssets().markUploaded(sha)
        }
    }

    /**
     * Files attached to a task go up before the task that lists them, so another device that sees the attachment can
     * fetch it. Content-addressed: a file already in the cloud is not resent, and an interrupted upload is sent again
     * whole. A file the server refuses for good (too large, no room) is marked failed and the task still syncs; the
     * user sees why on the task. Bytes are never put into the task JSON.
     */
    private suspend fun uploadTaskFiles(tenantId: String, mutation: JsonObject) {
        val store = taskFiles ?: return
        val task = (mutation["resource"] ?: mutation["changes"]) as? JsonObject ?: return
        val attachments = (task["attachments"] as? JsonArray) ?: return
        for (a in com.alondra1.app.domain.TaskAttachments.parse(attachments.toString())) {
            val sha = a.sha256 ?: continue
            val file = db.taskFiles().get(sha) ?: continue
            if (file.uploaded || !store.hasFile(sha)) continue
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.fileOf(sha).readBytes() }
                api.putBytes("/v1/task-files/$sha", bytes, "application/octet-stream", tenantId)
                db.taskFiles().markUploaded(sha)
            } catch (e: ApiException) {
                // 408 UPLOAD_INCOMPLETE: the transfer broke off; retried like 429/503, never a permanent failure.
                if (e.isNetwork || e.status == 401 || e.status == 403 || e.status >= 500 || e.status == 429 || e.status == 408) throw e
                db.taskFiles().markFailed(sha, e.message ?: "The file could not be uploaded.")
            }
        }
    }

    /**
     * Retry for a file whose upload was refused (Dev-only spec U9): forget the error, queue the task that lists it again
     * (the upload runs before that task is pushed) and sync now. Returns the resulting status.
     */
    suspend fun retryTaskFile(taskId: String, sha: String): SyncStatus {
        db.taskFiles().clearError(sha)
        db.withTransaction { enqueue(EntityType.TASK, taskId) }
        return syncNow()
    }

    /** The server's attachment limits, so the app refuses a file before the user waits for a transfer. */
    private suspend fun refreshTaskFileLimits(tenantId: String) {
        val limits = runCatching { api.call("GET", "/v1/task-files/limits", null, tenantId) }.getOrNull() ?: return
        limits["max_file_bytes"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { com.alondra1.app.data.repository.TaskFiles.limitBytes = it }
        limits["max_per_task"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { com.alondra1.app.data.repository.TaskFiles.limitPerTask = it }
    }

    /**
     * Downloads an attachment added on another device, on demand (opening it). True when the bytes are now here;
     * false when the cloud does not have them (not uploaded yet, or Cloud Sync is off for this space).
     */
    suspend fun downloadTaskFile(sha: String, mediaType: String?): Boolean {
        val store = taskFiles ?: return false
        val s = session.state.value
        val tenant = s.tenantId?.takeIf { s.signedIn && s.syncEnabled && api.configured } ?: return false
        val bytes = api.getBytes("/v1/task-files/$sha", tenant) ?: return false
        return store.addDownloaded(sha, bytes, mediaType)
    }

    /** Pictures of notes from other devices (a batch per run; the rest next time). */
    private suspend fun downloadAssets(tenantId: String) {
        val store = assets ?: return
        for (missing in db.noteAssets().missing(MAX_DOWNLOADS)) {
            val bytes = try { api.getBytes("/v1/note-assets/${missing.sha256}", tenantId) } catch (e: ApiException) { if (e.isNetwork) throw e else continue }
            if (bytes == null) { db.noteAssets().put(missing.copy(present = true, mimeType = "gone")); continue } // deleted in the cloud: stop asking
            store.addDownloaded(missing.sha256, bytes)
        }
    }

    /** Builds create/patch/delete for the item's current local state; null when there is nothing to send. */
    private suspend fun mutationFor(row: OutboxEntity): JsonObject? {
        val (local, remoteJson, version, deleted) = when (row.entityType) {
            EntityType.LIST_GROUP.wire -> db.listGroups().get(row.entityId)?.let { Quad(WireMapper.group(it), it.remoteJson, it.serverVersion, it.deletedUtc != null) }
            EntityType.TASK.wire -> db.tasks().get(row.entityId)?.let { Quad(WireMapper.task(it), it.remoteJson, it.serverVersion, it.deletedUtc != null) }
            EntityType.TASK_LIST.wire -> db.taskLists().get(row.entityId)?.let { Quad(WireMapper.list(it), it.remoteJson, it.serverVersion, it.deletedUtc != null) }
            EntityType.NOTE.wire -> db.notes().get(row.entityId)?.let { Quad(WireMapper.note(it), it.remoteJson, it.serverVersion, it.deletedUtc != null) }
            EntityType.NOTE_FOLDER.wire -> db.noteFolders().get(row.entityId)?.let { Quad(WireMapper.folder(it), it.remoteJson, it.serverVersion, it.deletedUtc != null) }
            EntityType.EVENT.wire -> db.events().get(row.entityId)?.let { Quad(WireMapper.event(it), null, it.serverVersion, it.deletedUtc != null) }
            else -> null
        } ?: return null
        return buildJsonObject {
            put("mutation_id", row.mutationId)
            put("resource_type", row.entityType)
            put("resource_id", row.entityId)
            when {
                version == null && deleted -> return null // created and deleted before it ever synced
                version == null -> { put("action", "create"); put("resource", local) }
                deleted -> { put("action", "delete"); put("base_version", version) }
                else -> {
                    put("action", "patch"); put("base_version", version); put("changes", diff(remoteJson, local))
                    // One completion record per task change, appended by the server in the same transaction, so a
                    // completed occurrence is recorded exactly once (the receipt makes retries safe).
                    if (row.entityType == EntityType.TASK.wire) db.completions().oldestUnsent(row.entityId)?.let { c ->
                        put("append_completion", WireMapper.completion(c))
                        sentCompletions[row.mutationId] = c.id
                    }
                }
            }
        }
    }

    /** Merge patch from the last server copy to the local object: removed fields become null. */
    private fun diff(remoteJson: String?, local: JsonObject): JsonObject {
        val old = remoteJson?.let { runCatching { WireMapper.json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return local
        val out = local.toMutableMap<String, JsonElement>()
        old.keys.filter { it !in local }.forEach { out[it] = JsonNull }
        return JsonObject(out)
    }

    private suspend fun handleResult(row: OutboxEntity, result: JsonObject) {
        when (result["status"]?.jsonPrimitive?.contentOrNull) {
            "applied" -> db.withTransaction {
                (result["record"] as? JsonObject)?.let { applyRecord(row.entityType, it, force = true) }
                    ?: markDeletedSynced(row.entityType, row.entityId)
                sync.complete(row.entityType, row.entityId, row.mutationId)
                sentCompletions.remove(row.mutationId)?.let { db.completions().markSent(it) }
                // More completions waiting (offline completions, or the task was created and completed before its first
                // sync): send the next one with another change.
                if (row.entityType == EntityType.TASK.wire && db.completions().unsentCount(row.entityId) > 0 &&
                    db.tasks().get(row.entityId)?.deletedUtc == null) enqueue(EntityType.TASK, row.entityId)
            }
            "conflict" -> db.withTransaction {
                keepBoth(row, result["current"] as? JsonObject, result["current_deleted"]?.jsonPrimitive?.booleanOrNull == true)
                sync.complete(row.entityType, row.entityId, row.mutationId)
            }
            else -> {
                val message = result["message"]?.jsonPrimitive?.contentOrNull ?: "The cloud did not accept this change."
                sync.addIssue(SyncIssueEntity(entityType = row.entityType, entityId = row.entityId, title = titleOf(row.entityType, row.entityId),
                    message = "Not synced: $message It is still on this device.", createdUtc = time.now().toEpochMilli()))
                sync.complete(row.entityType, row.entityId, row.mutationId)
            }
        }
    }

    private suspend fun titleOf(type: String, id: String): String = when (type) {
        EntityType.LIST_GROUP.wire -> db.listGroups().get(id)?.name
        EntityType.TASK.wire -> db.tasks().get(id)?.title
        EntityType.TASK_LIST.wire -> db.taskLists().get(id)?.name
        EntityType.NOTE.wire -> db.notes().get(id)?.let { com.alondra1.app.domain.NoteRules.displayTitle(it) }
        EntityType.NOTE_FOLDER.wire -> db.noteFolders().get(id)?.name
        EntityType.EVENT.wire -> db.events().get(id)?.title
        else -> null
    } ?: "Item"

    /** A delete was applied: the local tombstone stays, so the item cannot come back from an older copy. */
    @Suppress("UNUSED_PARAMETER")
    private fun markDeletedSynced(type: String, id: String) = Unit

    /**
     * Both sides changed. The server copy takes the original id (so every device converges); the local version is saved
     * as a new item marked "(conflict copy)" and queued, so no edit is lost. The user sees it under Sync issues.
     */
    private suspend fun keepBoth(row: OutboxEntity, current: JsonObject?, currentDeleted: Boolean) {
        val now = time.now()
        val copyId = Ids.newId()
        var noteCopy: String? = null
        var report = true
        var mergedFiles: JsonArray? = null
        val title = titleOf(row.entityType, row.entityId)
        when (row.entityType) {
            // Attachments merge three-way on the original task (Dev-only spec BE-03): a file removed here is not brought
            // back, files added on either side are kept, and the copy carries no attachments, so nothing is duplicated.
            // When only attachments differ, the merge is all there is: no copy and no sync issue.
            EntityType.TASK.wire -> db.tasks().get(row.entityId)?.takeIf { it.deletedUtc == null }?.let { mine ->
                val base = mine.remoteJson?.let { runCatching { WireMapper.json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val local = WireMapper.task(mine)
                val remoteExists = current != null && !currentDeleted
                val remoteFiles = (current?.get(row.entityType) as? JsonObject)?.get("attachments") as? JsonArray
                if (remoteExists) {
                    mergedFiles = com.alondra1.app.domain.AttachmentMerge.merge(base?.get("attachments") as? JsonArray, local["attachments"] as? JsonArray, remoteFiles)
                        .takeUnless { com.alondra1.app.domain.AttachmentMerge.sameIds(it, remoteFiles) }
                }
                report = !remoteExists || base == null || otherThanAttachments(base, local)
                if (report) {
                    // The original is gone on the server: the copy is where this device's files stay listed.
                    db.tasks().put(mine.copy(id = copyId, title = (mine.title + " (conflict copy)").take(500), serverVersion = null, remoteJson = null,
                        attachmentsJson = if (remoteExists) "[]" else mine.attachmentsJson, createdUtc = now, modifiedUtc = now))
                    enqueue(EntityType.TASK, copyId)
                }
            }
            EntityType.NOTE.wire -> db.notes().get(row.entityId)?.takeIf { it.deletedUtc == null }?.let {
                db.notes().put(it.copy(id = copyId, title = (com.alondra1.app.domain.NoteRules.displayTitle(it) + com.alondra1.app.domain.NoteMerge.CONFLICT_SUFFIX).take(500), serverVersion = null, remoteJson = null, createdUtc = now, modifiedUtc = now))
                enqueue(EntityType.NOTE, copyId)
                noteCopy = copyId
            }
            EntityType.EVENT.wire -> db.events().get(row.entityId)?.takeIf { it.deletedUtc == null }?.let {
                db.events().put(it.copy(id = copyId, title = (it.title + " (conflict copy)").take(500), serverVersion = null, createdUtc = now, modifiedUtc = now))
                enqueue(EntityType.EVENT, copyId)
            }
            EntityType.TASK_LIST.wire, EntityType.NOTE_FOLDER.wire -> Unit // containers: the server version wins below; contents keep their ids
        }
        if (current != null && !currentDeleted) applyRecord(row.entityType, current, force = true)
        if (currentDeleted) tombstone(row.entityType, row.entityId)
        mergedFiles?.let { files ->
            db.tasks().get(row.entityId)?.let { db.tasks().put(it.copy(attachmentsJson = files.toString(), modifiedUtc = now)) }
            enqueue(EntityType.TASK, row.entityId)
        }
        if (report) sync.addIssue(SyncIssueEntity(entityType = row.entityType, entityId = row.entityId, title = title,
            message = if (currentDeleted) "It was deleted on another device while you edited it here. Your version was kept as a conflict copy."
            else "It was changed on another device too. The other version is shown; yours was kept as a conflict copy.",
            createdUtc = now.toEpochMilli(), copyId = noteCopy))
    }

    /** True when the two task objects differ in more than attachments (and the modified time, which every edit changes). */
    private fun otherThanAttachments(a: JsonObject, b: JsonObject): Boolean =
        (a.keys + b.keys).filter { it != "attachments" && it != "modifiedUtc" }.any { !sameValue(a[it], b[it]) }

    /** Equal as task data: 1 and 1.0 are the same number; absent, null and an empty list are the same "nothing". */
    private fun sameValue(x: JsonElement?, y: JsonElement?): Boolean {
        fun empty(e: JsonElement?) = e == null || e is JsonNull || (e is JsonArray && e.isEmpty())
        if (empty(x) && empty(y)) return true
        if (x is JsonPrimitive && y is JsonPrimitive && !x.isString && !y.isString) {
            val a = x.content.toDoubleOrNull(); val b = y.content.toDoubleOrNull()
            if (a != null && b != null) return a == b
        }
        return x == y
    }

    // ------------------------------------------------------------------------------------------- pull

    private suspend fun pull(tenantId: String) {
        var cursor = session.cursor() ?: return bootstrap(tenantId)
        while (true) {
            val page = try {
                api.call("GET", "/v1/sync/changes?limit=500&cursor=$cursor", tenant = tenantId)
            } catch (e: ApiException) {
                if (e.code == "CURSOR_EXPIRED" || e.code == "INVALID_CURSOR") {
                    // Too old: download everything again and merge; unsent local changes stay queued.
                    bootstrap(tenantId); return
                }
                throw e
            }
            val changes = (page["changes"] as? JsonArray).orEmpty().map { it.jsonObject }
            val next = page["cursor"]!!.jsonPrimitive.content
            db.withTransaction {
                changes.forEach { change ->
                    when (change["action"]?.jsonPrimitive?.contentOrNull) {
                        "upsert" -> applyUpsert(change, merging = false)
                        "delete" -> applyDelete(change)
                    }
                }
                session.setCursor(next) // the cursor moves with the applied changes, in one transaction
            }
            cursor = next
            if (page["has_more"]?.jsonPrimitive?.booleanOrNull != true) break
        }
    }

    private suspend fun dirty(type: String, id: String) = sync.find(type, id) != null

    private suspend fun applyUpsert(item: JsonObject, merging: Boolean) {
        val type = item["resource_type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type == "completion") { // completion history from another device (or this one): append-only, ids are global
            (item["record"] as? JsonObject)?.let(WireMapper::completionFrom)?.let { db.completions().insert(it) }
            return
        }
        if (type !in typeOrder) return // settings are kept by the clients that edit them
        val record = item["record"] as? JsonObject ?: return
        val id = record["id"]?.jsonPrimitive?.contentOrNull ?: return
        if (dirty(type, id)) {
            // Local edits win the race here and will be pushed; a real conflict is then reported by the server.
            if (merging) adoptVersion(type, id, record)
            return
        }
        applyRecord(type, record, force = false)
    }

    /** Re-enabling sync: a local item that exists in the cloud adopts its version so the next push is a patch. */
    private suspend fun adoptVersion(type: String, id: String, record: JsonObject) {
        val version = record["version"]?.jsonPrimitive?.longOrNull ?: return
        when (type) {
            EntityType.LIST_GROUP.wire -> db.listGroups().get(id)?.let { db.listGroups().put(it.copy(serverVersion = version, remoteJson = (record["group"] as? JsonObject)?.toString())) }
            EntityType.TASK.wire -> db.tasks().get(id)?.let { db.tasks().put(it.copy(serverVersion = version, remoteJson = (record["task"] as? JsonObject)?.toString())) }
            EntityType.TASK_LIST.wire -> db.taskLists().get(id)?.let { db.taskLists().put(it.copy(serverVersion = version, remoteJson = (record["list"] as? JsonObject)?.toString())) }
            EntityType.NOTE.wire -> db.notes().get(id)?.let { db.notes().put(it.copy(serverVersion = version, remoteJson = (record["note"] as? JsonObject)?.toString())) }
            EntityType.NOTE_FOLDER.wire -> db.noteFolders().get(id)?.let { db.noteFolders().put(it.copy(serverVersion = version, remoteJson = (record["note_folder"] as? JsonObject)?.toString())) }
            EntityType.EVENT.wire -> db.events().get(id)?.let { db.events().put(it.copy(serverVersion = version)) }
        }
    }

    private suspend fun applyRecord(type: String, record: JsonObject, force: Boolean) {
        val version = record["version"]?.jsonPrimitive?.longOrNull ?: return
        when (type) {
            EntityType.LIST_GROUP.wire -> (record["group"] as? JsonObject)?.let { o ->
                val local = db.listGroups().get(o["id"]!!.jsonPrimitive.content)
                if (!force && local?.serverVersion != null && local.serverVersion >= version) return
                db.listGroups().put(WireMapper.groupFrom(o, version, local))
            }
            EntityType.TASK.wire -> (record["task"] as? JsonObject)?.let { o ->
                val local = db.tasks().get(o["id"]!!.jsonPrimitive.content)
                if (!force && local?.serverVersion != null && local.serverVersion >= version) return
                val task = WireMapper.taskFrom(o, version, local)
                db.tasks().put(task)
                reminders.syncTask(task)
            }
            EntityType.TASK_LIST.wire -> (record["list"] as? JsonObject)?.let { o ->
                val local = db.taskLists().get(o["id"]!!.jsonPrimitive.content)
                if (!force && local?.serverVersion != null && local.serverVersion >= version) return
                db.taskLists().put(WireMapper.listFrom(o, version, local))
                if (!force) adoptRemoteDefaultList()
            }
            EntityType.NOTE.wire -> (record["note"] as? JsonObject)?.let { o ->
                val local = db.notes().get(o["id"]!!.jsonPrimitive.content)
                if (!force && local?.serverVersion != null && local.serverVersion >= version) return
                val note = WireMapper.noteFrom(o, version)
                db.notes().put(note)
                assets?.expect(com.alondra1.app.data.repository.NotesRepository.decodeIds(note.assetsJson))
            }
            EntityType.NOTE_FOLDER.wire -> (record["note_folder"] as? JsonObject)?.let { o ->
                val local = db.noteFolders().get(o["id"]!!.jsonPrimitive.content)
                if (!force && local?.serverVersion != null && local.serverVersion >= version) return
                db.noteFolders().put(WireMapper.folderFrom(o, version))
            }
            EntityType.EVENT.wire -> (record["event"] as? JsonObject)?.let { o ->
                val local = db.events().get(o["id"]!!.jsonPrimitive.content)
                if (!force && local?.serverVersion != null && local.serverVersion >= version) return
                val event = WireMapper.eventFrom(o, version)
                db.events().put(event)
                reminders.syncEvent(event)
            }
        }
    }

    private suspend fun applyDelete(change: JsonObject) {
        val type = change["resource_type"]?.jsonPrimitive?.contentOrNull ?: return
        val id = change["resource_id"]?.jsonPrimitive?.contentOrNull ?: return
        if (type !in typeOrder || dirty(type, id)) return
        tombstone(type, id)
    }

    private suspend fun tombstone(type: String, id: String) {
        val now = time.now()
        when (type) {
            EntityType.LIST_GROUP.wire -> db.listGroups().get(id)?.let { g ->
                db.listGroups().put(g.copy(deletedUtc = now)) // its lists become ungrouped (the server clears groupId)
                db.taskLists().active().filter { it.groupId == id }.forEach { db.taskLists().put(it.copy(groupId = null)) }
            }
            EntityType.TASK.wire -> db.tasks().get(id)?.let { db.tasks().put(it.copy(deletedUtc = now)); reminders.cancelTask(id) }
            EntityType.TASK_LIST.wire -> db.taskLists().get(id)?.let { db.taskLists().put(it.copy(deletedUtc = now)) }
            EntityType.NOTE.wire -> db.notes().get(id)?.let { db.notes().put(it.copy(deletedUtc = now)) }
            EntityType.NOTE_FOLDER.wire -> db.noteFolders().get(id)?.let { db.noteFolders().put(it.copy(deletedUtc = now)) } // its notes show in All notes
            EntityType.EVENT.wire -> db.events().get(id)?.let { db.events().put(it.copy(deletedUtc = now)); reminders.cancelEvent(id) }
        }
    }

    private companion object {
        const val MAX_PUSH_BYTES = 6 shl 20
        const val MAX_DOWNLOADS = 100
    }

    private data class Quad(val local: JsonObject, val remoteJson: String?, val version: Long?, val deleted: Boolean)
}
