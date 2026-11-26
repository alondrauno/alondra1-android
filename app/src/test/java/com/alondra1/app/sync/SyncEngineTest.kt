package com.alondra1.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.AppContainer
import com.alondra1.app.TestSupport
import com.alondra1.app.auth.ApiClient
import com.alondra1.app.auth.SessionStore
import com.alondra1.app.core.FixedTimeSource
import com.alondra1.app.data.notifications.RecordingReminderScheduler
import com.alondra1.app.data.sync.OutboxRecorder
import com.alondra1.app.data.sync.SyncEngine
import com.alondra1.app.data.sync.SyncState
import com.alondra1.app.data.sync.WireMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** The sync engine against a scripted server that speaks the real contract (contract/openapi.yaml). */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val server = MockWebServer()
    private val pushes = Collections.synchronizedList(mutableListOf<JsonObject>())
    @Volatile private var bootstrapItems = "[]"
    @Volatile private var pushResponder: (JsonObject) -> MockResponse = { appliedAll(it) }
    @Volatile private var changes = "[]"
    /** Every request path in order, and the bytes PUT to /v1/task-files. */
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val uploaded = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
    @Volatile private var fileUploadStatus = 201
    private lateinit var session: SessionStore
    private lateinit var engine: SyncEngine
    private lateinit var c: AppContainer
    private val tenant = "01a209c9-4fe9-7666-a5f5-145014a54198"

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun appliedAll(req: JsonObject): MockResponse {
        val results = req["mutations"]!!.jsonArray.map { m ->
            val o = m.jsonObject
            val type = o["resource_type"]!!.jsonPrimitive.content
            val record = when (o["action"]!!.jsonPrimitive.content) {
                "create" -> ""","record":{"id":"${o["resource_id"]!!.jsonPrimitive.content}","version":1,"schema_version":1,"updated_at":"2026-11-23T10:00:00Z","$type":${o["resource"]}}"""
                "patch" -> ""","record":{"id":"${o["resource_id"]!!.jsonPrimitive.content}","version":${o["base_version"]!!.jsonPrimitive.content.toLong() + 1},"schema_version":1,"updated_at":"2026-11-23T10:00:00Z","$type":${o["changes"]}}"""
                else -> ""
            }
            """{"mutation_id":"${o["mutation_id"]!!.jsonPrimitive.content}","status":"applied"$record}"""
        }
        return json("""{"results":[${results.joinToString(",")}]}""")
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += request.method + " " + path
                return when {
                    path.startsWith("/v1/task-files/limits") -> json("""{"max_file_bytes":1048576,"max_per_task":4,"quota_bytes":0,"space_used_bytes":0}""")
                    path.startsWith("/v1/task-files/") && request.method == "PUT" -> {
                        uploaded[path.removePrefix("/v1/task-files/")] = request.body.readByteArray()
                        if (fileUploadStatus == 201) json("""{"id":"x","mime_type":"application/pdf","bytes":1,"created_at":"2026-11-23T10:00:00Z"}""", 201)
                        else json("""{"error":{"code":"FILE_TOO_LARGE","message":"A file can be at most 25 MB."}}""", fileUploadStatus)
                    }
                    path.startsWith("/v1/task-files/") && request.method == "GET" -> uploaded[path.removePrefix("/v1/task-files/")]
                        ?.let { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(it)) } ?: json("""{"error":{"code":"NOT_FOUND","message":"no"}}""", 404)
                    path.startsWith("/v1/sync/bootstrap") -> json("""{"items":$bootstrapItems,"cursor":"c1"}""")
                    path.startsWith("/v1/sync/push") -> {
                        val body = WireMapper.json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        pushes += body
                        pushResponder(body)
                    }
                    path.startsWith("/v1/sync/changes") -> json("""{"changes":$changes,"cursor":"c2","has_more":false}""").also { changes = "[]" }
                    else -> json("""{"error":{"code":"NOT_FOUND","message":"no"}}""", 404)
                }
            }
        }
        server.start()
        val time = FixedTimeSource(TestSupport.clockAt("2026-11-23T10:00:00"))
        val db = TestSupport.memoryDb()
        session = SessionStore.inMemory(TestSupport.context(), "session-${System.nanoTime()}")
        val api = ApiClient(server.url("/").toString(), session)
        engine = SyncEngine(db, api, session, time, RecordingReminderScheduler(), null)
        val recorder = OutboxRecorder(session, { engine }, {})
        c = AppContainer(db, time, RecordingReminderScheduler(), TestSupport.settings(), recorder)
        session.saveTokens(buildJsonObject {
            put("access_token", "token"); put("refresh_token", "refresh")
            put("user", buildJsonObject { put("id", "u1"); put("email", "maya@example.com") })
        })
    }

    @After fun tearDown() { server.shutdown() }

    private fun listRecord(id: String, version: Int, isDefault: Boolean) =
        """{"resource_type":"list","action":"upsert","resource_id":"$id","record":{"id":"$id","version":$version,"schema_version":1,"updated_at":"2026-11-20T00:00:00Z","list":{"id":"$id","name":"Tasks","isDefault":$isDefault,"position":0,"createdUtc":"2026-11-01T00:00:00Z"}}}"""

    private fun taskRecord(id: String, version: Int, title: String, listId: String) =
        """{"resource_type":"task","action":"upsert","resource_id":"$id","record":{"id":"$id","version":$version,"schema_version":1,"updated_at":"2026-11-20T00:00:00Z","task":{"id":"$id","listId":"$listId","title":"$title","notes":"","completed":false,"important":false,"position":1,"createdUtc":"2026-11-01T00:00:00Z","modifiedUtc":"2026-11-01T00:00:00Z","myDayDate":"2026-11-23"}}}"""

    @Test fun nothingIsRecordedBeforeSyncIsTurnedOn() = runBlocking<Unit> {
        c.todo.addTask("Local only")
        c.notes.save("n", "Private", "")
        assertEquals(0, c.db.sync().outbox().size)
        assertEquals("no request reaches the server", 0, server.requestCount)
    }

    @Test fun enablingMergesBothSidesAndAdoptsTheCloudDefaultList() = runBlocking<Unit> {
        val localTask = c.todo.addTask("From the phone", view = com.alondra1.app.domain.SmartView.MY_DAY)
        c.notes.save("n-local", "Phone note", "")
        bootstrapItems = "[${listRecord("win-default", 1, true)},${taskRecord("win-task", 2, "From Windows", "win-default")}]"

        val status = engine.enable(tenant)

        assertEquals(SyncState.SYNCED, status.state)
        assertEquals("From Windows", c.db.tasks().get("win-task")!!.title)
        assertEquals("local tasks move to the cloud's default list", "win-default", c.db.tasks().get(localTask.id)!!.listId)
        val sent = pushes.flatMap { it["mutations"]!!.jsonArray }.map { it.jsonObject }
        val createdTask = sent.single { it["resource_id"]!!.jsonPrimitive.content == localTask.id }
        assertEquals("create", createdTask["action"]!!.jsonPrimitive.content)
        assertEquals("win-default", createdTask["resource"]!!.jsonObject["listId"]!!.jsonPrimitive.content)
        assertTrue(sent.any { it["resource_id"]!!.jsonPrimitive.content == "n-local" })
        assertTrue("the never-synced local default list is not uploaded", sent.none { it["resource_type"]!!.jsonPrimitive.content == "list" })
        assertEquals(0, c.db.sync().outbox().size)
        assertEquals(1L, c.db.tasks().get(localTask.id)!!.serverVersion)
    }

    @Test fun aConflictKeepsBothVersions() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)},${taskRecord("t1", 3, "Shared task", "d")}]"
        engine.enable(tenant)
        c.todo.getTask("t1")!!.let { c.todo.updateTask(it.copy(title = "Edited on the phone")) }
        pushResponder = { req ->
            val m = req["mutations"]!!.jsonArray.first().jsonObject
            pushResponder = { appliedAll(it) }
            json("""{"results":[{"mutation_id":"${m["mutation_id"]!!.jsonPrimitive.content}","status":"conflict","code":"VERSION_CONFLICT",
                "current":${taskRecord("t1", 4, "Edited on Windows", "d").let { WireMapper.json.parseToJsonElement(it).jsonObject["record"] }}}]}""")
        }
        engine.syncNow()
        val tasks = c.db.tasks().all()
        assertEquals("Edited on Windows", c.db.tasks().get("t1")!!.title)
        val copy = tasks.single { it.title == "Edited on the phone (conflict copy)" }
        assertEquals(1, c.db.sync().observeIssuesOnce().size)
        val sent = pushes.flatMap { it["mutations"]!!.jsonArray }.map { it.jsonObject }
        assertTrue("the copy is sent in the same run", sent.any { it["resource_id"]!!.jsonPrimitive.content == copy.id && it["action"]!!.jsonPrimitive.content == "create" })
        assertEquals(0, c.db.sync().outbox().size)
    }

    @Test fun attachmentMergeFollowsTheSharedContractFixture() {
        val fixture = WireMapper.json.parseToJsonElement(javaClass.classLoader!!.getResource("todo-v1/merge-attachments.json")!!.readText()).jsonObject
        for (case in fixture["cases"]!!.jsonArray.map { it.jsonObject }) {
            val merged = com.alondra1.app.domain.AttachmentMerge.merge(case["base"] as? kotlinx.serialization.json.JsonArray,
                case["local"] as? kotlinx.serialization.json.JsonArray, case["remote"] as? kotlinx.serialization.json.JsonArray)
            assertEquals(case["name"]!!.jsonPrimitive.content, case["expected"]!!.jsonArray.map { it.jsonPrimitive.content },
                merged.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        }
    }

    private fun fileJson(id: String, sha: Char) =
        """{"id":"$id","fileName":"$id.pdf","size":3,"addedUtc":"2026-11-01T00:00:00Z","sha256":"${sha.toString().repeat(64)}","mediaType":"application/pdf"}"""

    private fun taskWithFiles(id: String, version: Int, title: String, vararg files: String) =
        taskRecord(id, version, title, "d").replace(""","myDayDate":"2026-11-23"}""", ""","myDayDate":"2026-11-23","attachments":[${files.joinToString(",")}]}""")

    @Test fun aConflictNeverBringsBackARemovedAttachmentNorDuplicatesFiles() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)},${taskWithFiles("t1", 3, "Shared task", fileJson("a1", '1'), fileJson("a2", '2'))}]"
        engine.enable(tenant)
        c.todo.removeAttachment("t1", "a1")
        pushResponder = { req ->
            val m = req["mutations"]!!.jsonArray.first().jsonObject
            pushResponder = { appliedAll(it) }
            json("""{"results":[{"mutation_id":"${m["mutation_id"]!!.jsonPrimitive.content}","status":"conflict","code":"VERSION_CONFLICT",
                "current":${taskWithFiles("t1", 4, "Renamed on Windows", fileJson("a1", '1'), fileJson("a2", '2'), fileJson("a3", '3')).let { WireMapper.json.parseToJsonElement(it).jsonObject["record"] }}}]}""")
        }
        engine.syncNow()
        val t1 = c.db.tasks().get("t1")!!
        assertEquals("Renamed on Windows", t1.title)
        assertEquals(listOf("a2", "a3"), com.alondra1.app.domain.TaskAttachments.parse(t1.attachmentsJson).map { it.id })
        assertTrue("no conflict copy when only attachments differed", c.db.tasks().all().none { it.title.endsWith("(conflict copy)") })
        assertEquals(0, c.db.sync().observeIssuesOnce().size)
        val patch = pushes.flatMap { it["mutations"]!!.jsonArray }.map { it.jsonObject }.last { it["resource_id"]!!.jsonPrimitive.content == "t1" }
        assertEquals(listOf("a2", "a3"), patch["changes"]!!.jsonObject["attachments"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun aConflictCopyCarriesNoAttachmentsWhileTheOriginalExists() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)},${taskWithFiles("t1", 3, "Shared task", fileJson("a1", '1'))}]"
        engine.enable(tenant)
        c.todo.getTask("t1")!!.let { c.todo.updateTask(it.copy(title = "Edited on the phone")) }
        pushResponder = { req ->
            val m = req["mutations"]!!.jsonArray.first().jsonObject
            pushResponder = { appliedAll(it) }
            json("""{"results":[{"mutation_id":"${m["mutation_id"]!!.jsonPrimitive.content}","status":"conflict","code":"VERSION_CONFLICT",
                "current":${taskWithFiles("t1", 4, "Edited on Windows", fileJson("a1", '1')).let { WireMapper.json.parseToJsonElement(it).jsonObject["record"] }}}]}""")
        }
        engine.syncNow()
        val copy = c.db.tasks().all().single { it.title == "Edited on the phone (conflict copy)" }
        assertEquals("[]", copy.attachmentsJson)
        assertEquals(listOf("a1"), com.alondra1.app.domain.TaskAttachments.parse(c.db.tasks().get("t1")!!.attachmentsJson).map { it.id })
        assertEquals(1, c.db.sync().observeIssuesOnce().size)
    }

    private fun noteRecord(id: String, version: Long, body: String) =
        """{"resource_type":"note","action":"upsert","resource_id":"$id","record":{"id":"$id","version":$version,"schema_version":1,"updated_at":"2026-11-20T00:00:00Z","note":{"id":"$id","title":"Shared","body":"$body","createdUtc":"2026-11-01T00:00:00Z","modifiedUtc":"2026-11-01T00:00:00Z"}}}"""

    @Test fun aNoteConflictRemembersItsCopySoTheUserCanCombineBoth() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)},${noteRecord("n1", 2, "first line")}]"
        engine.enable(tenant)
        c.notes.save("n1", "Shared", "first line\nedited on the phone")
        pushResponder = { req ->
            val m = req["mutations"]!!.jsonArray.first().jsonObject
            pushResponder = { appliedAll(it) }
            json("""{"results":[{"mutation_id":"${m["mutation_id"]!!.jsonPrimitive.content}","status":"conflict","code":"VERSION_CONFLICT",
                "current":${noteRecord("n1", 3, "first line\\nedited on Windows").let { WireMapper.json.parseToJsonElement(it).jsonObject["record"] }}}]}""")
        }
        engine.syncNow()
        val copy = c.db.notes().all().single { it.title == "Shared (conflict copy)" }
        val issue = c.db.sync().observeIssuesOnce().single()
        assertEquals("n1" to copy.id, issue.entityId to issue.copyId)
        assertEquals(issue, c.db.sync().observeNoteConflict(copy.id).first())

        assertEquals("n1", c.notes.resolveConflict("n1", copy.id, com.alondra1.app.domain.NoteConflictChoice.COMBINE))
        assertEquals("first line\nedited on Windows\nedited on the phone", c.db.notes().get("n1")!!.body)
        assertNotNull(c.db.notes().get(copy.id)!!.trashedUtc)
    }

    @Test fun aPullNeverOverwritesUnsentEdits() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)},${taskRecord("t1", 1, "Original", "d")}]"
        engine.enable(tenant)
        pushResponder = { json("""{"error":{"code":"INTERNAL","message":"down"}}""", 500) }
        c.todo.getTask("t1")!!.let { c.todo.updateTask(it.copy(title = "Unsent edit")) }
        changes = "[${taskRecord("t1", 2, "Remote edit", "d")}]"
        val status = engine.syncNow()
        assertEquals(SyncState.ERROR_RETRYABLE, status.state)
        assertEquals("Unsent edit", c.db.tasks().get("t1")!!.title)
        assertEquals(1, c.db.sync().outbox().size)
    }

    @Test fun mutationIdsAreRenewedOnlyAfterASendAttempt() = runBlocking<Unit> {
        session.setSync(tenant, false)
        session.setSync(tenant, true)
        c.notes.save("n1", "A", "")
        val first = c.db.sync().find("note", "n1")!!
        c.notes.save("n1", "B", "")
        assertEquals("unsent: same id", first.mutationId, c.db.sync().find("note", "n1")!!.mutationId)
        c.db.sync().markAttempted(listOf(first.seq))
        c.notes.save("n1", "C", "")
        assertNotEquals("changed after a send attempt: new id", first.mutationId, c.db.sync().find("note", "n1")!!.mutationId)
    }

    @Test fun planAndAccessErrorsStopSyncWithoutTouchingLocalData() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)}]"
        engine.enable(tenant)
        c.todo.addTask("Made after the plan ended")
        pushResponder = { json("""{"error":{"code":"ENTITLEMENT_REQUIRED","message":"paid","entitlement_state":"read_only"}}""", 403) }
        assertEquals(SyncState.READ_ONLY_PLAN, engine.syncNow().state)
        assertEquals(1, c.db.sync().outbox().size)
        pushResponder = { json("""{"error":{"code":"TENANT_NOT_FOUND","message":"gone"}}""", 404) }
        assertEquals(SyncState.ERROR_PERMANENT, engine.syncNow().state)
        assertTrue("sync turns off, data stays", !session.state.value.syncEnabled)
        assertNotNull(c.db.tasks().all().singleOrNull { it.title == "Made after the plan ended" })
    }

    @Test fun offlineIsAStateNotAnError() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)}]"
        engine.enable(tenant)
        c.todo.addTask("Offline task")
        server.shutdown()
        assertEquals(SyncState.OFFLINE, engine.syncNow().state)
        assertEquals(1, c.db.sync().outbox().size)
    }

    @Test fun listGroupsSyncBeforeTheirLists() = runBlocking<Unit> {
        val group = c.todo.createGroup("Home")
        val list = c.todo.createList("Groceries")
        c.todo.setListGroup(list.id, group.id)
        engine.enable(tenant)
        val mutations = pushes.flatMap { it["mutations"]!!.jsonArray.map { m -> m.jsonObject } }
        val types = mutations.map { it["resource_type"]!!.jsonPrimitive.content }
        assertTrue(types.toString(), types.indexOf("group") in 0 until types.indexOf("list"))
        val groupWire = mutations.first { it["resource_type"]!!.jsonPrimitive.content == "group" }["resource"]!!.jsonObject
        assertEquals("Home", groupWire["name"]!!.jsonPrimitive.content)
        assertTrue("collapsed is device-local", groupWire["collapsed"] == null)
        val listWire = mutations.first { it["resource_id"]!!.jsonPrimitive.content == list.id }["resource"]!!.jsonObject
        assertEquals(group.id, listWire["groupId"]!!.jsonPrimitive.content)
        assertEquals(1L, c.db.listGroups().get(group.id)!!.serverVersion)
    }

    @Test fun completionRecordsTravelWithTheCompletingChangeOnce() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)}]"
        val task = c.todo.addTask("Pay rent", dueDate = java.time.LocalDate.parse("2026-11-30"))
        c.todo.setRecurrence(task.id, com.alondra1.app.domain.RecurrenceRule(com.alondra1.app.domain.Frequency.Monthly))
        engine.enable(tenant) // the task is created in the cloud (version 1)
        pushes.clear()
        c.todo.setCompleted(task.id, true)
        engine.syncNow()
        val patch = pushes.flatMap { it["mutations"]!!.jsonArray.map { m -> m.jsonObject } }.single { it["resource_id"]!!.jsonPrimitive.content == task.id }
        assertEquals("patch", patch["action"]!!.jsonPrimitive.content)
        val completion = patch["append_completion"]!!.jsonObject
        assertEquals(task.id, completion["taskId"]!!.jsonPrimitive.content)
        assertEquals("2026-11-30", completion["occurrenceDate"]!!.jsonPrimitive.content)
        assertTrue("marked sent once applied", c.db.completions().forTask(task.id).single().sent)
        pushes.clear()
        engine.syncNow()
        assertTrue("nothing is sent twice", pushes.none { p -> p["mutations"]!!.jsonArray.any { it.jsonObject["append_completion"] != null } })
    }

    @Test fun completionsFromOtherDevicesAreKept() = runBlocking<Unit> {
        bootstrapItems = "[${listRecord("d", 1, true)}]"
        engine.enable(tenant)
        changes = """[{"seq":5,"resource_type":"completion","resource_id":"c-other","action":"upsert",
            "record":{"id":"c-other","taskId":"t-other","title":"Water plants","listId":"l1","completedUtc":"2026-11-22T18:00:00Z","occurrenceDate":"2026-11-22"}}]"""
        engine.syncNow()
        val record = c.db.completions().forTask("t-other").single()
        assertEquals("Water plants", record.title)
        assertTrue(record.sent)
    }

    private fun engineWithFiles(): com.alondra1.app.data.repository.TaskFiles {
        val files = com.alondra1.app.data.repository.TaskFiles(c.db, java.io.File(TestSupport.context().cacheDir, "tf-${System.nanoTime()}"), c.time)
        engine = SyncEngine(c.db, ApiClient(server.url("/").toString(), session), session, c.time, RecordingReminderScheduler(), null, null, files)
        return files
    }

    @Test fun taskFilesGoUpBeforeTheTaskThatListsThem() = runBlocking<Unit> {
        val files = engineWithFiles()
        val pdf = "%PDF-1.7 attachment".toByteArray()
        val a = files.store(pdf.inputStream(), "ticket.pdf", null)
        val task = c.todo.addTask("Trip", attachments = listOf(a))
        engine.enable(tenant)

        val put = requests.indexOfFirst { it == "PUT /v1/task-files/${a.sha256}" }
        val push = requests.indexOfFirst { it.startsWith("POST /v1/sync/push") }
        assertTrue("uploaded before the push: $requests", put in 0 until push)
        assertTrue(uploaded[a.sha256]!!.contentEquals(pdf))
        assertTrue(c.db.taskFiles().get(a.sha256!!)!!.uploaded)
        val sentTask = pushes.flatMap { it["mutations"]!!.jsonArray }.map { it.jsonObject }.single { it["resource_id"]!!.jsonPrimitive.content == task.id }
        assertEquals(a.sha256, sentTask["resource"]!!.jsonObject["attachments"]!!.jsonArray[0].jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals("the server's limits are adopted", 4, com.alondra1.app.data.repository.TaskFiles.limitPerTask)
        com.alondra1.app.data.repository.TaskFiles.limitPerTask = com.alondra1.app.domain.TaskAttachments.DEFAULT_MAX_PER_TASK
        com.alondra1.app.data.repository.TaskFiles.limitBytes = com.alondra1.app.domain.TaskAttachments.DEFAULT_MAX_BYTES

        // Uploaded once: a later sync does not resend it.
        requests.clear()
        c.todo.updateTask(c.todo.getTask(task.id)!!.copy(title = "Trip to Rome"))
        engine.syncNow()
        assertTrue(requests.none { it.startsWith("PUT /v1/task-files/") })
    }

    @Test fun aRefusedFileIsMarkedAndTheTaskStillSyncs() = runBlocking<Unit> {
        val files = engineWithFiles()
        fileUploadStatus = 413
        val a = files.store("big".byteInputStream(), "big.bin", null)
        val task = c.todo.addTask("Big", attachments = listOf(a))
        val status = engine.enable(tenant)
        assertEquals(SyncState.SYNCED, status.state)
        assertEquals("A file can be at most 25 MB.", c.db.taskFiles().get(a.sha256!!)!!.lastError)
        assertNotNull(c.db.tasks().get(task.id)!!.serverVersion)

        // Retry (U9): once the server accepts it, the same file goes up and the error is gone.
        fileUploadStatus = 201
        requests.clear()
        assertEquals(SyncState.SYNCED, engine.retryTaskFile(task.id, a.sha256!!).state)
        assertTrue(requests.contains("PUT /v1/task-files/${a.sha256}"))
        val file = c.db.taskFiles().get(a.sha256!!)!!
        assertTrue(file.uploaded)
        assertEquals(null, file.lastError)
    }

    @Test fun aBusyServerKeepsTheFileAndTheTaskQueued() = busyKeepsQueued(503)

    /** 408 UPLOAD_INCOMPLETE: the transfer broke off; retried like a busy server. */
    @Test fun anIncompleteUploadKeepsTheFileAndTheTaskQueued() = busyKeepsQueued(408)

    private fun busyKeepsQueued(status: Int) = runBlocking<Unit> {
        val files = engineWithFiles()
        fileUploadStatus = status
        val a = files.store("%PDF-1.7 busy".byteInputStream(), "busy.pdf", null)
        val task = c.todo.addTask("Busy", attachments = listOf(a))
        engine.enable(tenant)
        val file = c.db.taskFiles().get(a.sha256!!)!!
        assertTrue("a busy server is not a refusal", !file.uploaded && file.lastError == null)
        assertTrue("the task stays queued", c.db.sync().outbox().any { it.entityId == task.id })
        fileUploadStatus = 201
        engine.syncNow()
        assertTrue(c.db.taskFiles().get(a.sha256!!)!!.uploaded)
    }

    @Test fun aFileFromAnotherDeviceDownloadsOnDemandAndIsVerified() = runBlocking<Unit> {
        val files = engineWithFiles()
        engine.enable(tenant)
        val bytes = "from windows".toByteArray()
        val sha = com.alondra1.app.data.repository.TaskFiles.hash(bytes)
        assertEquals("not in the cloud yet", false, engine.downloadTaskFile(sha, "text/plain"))
        uploaded[sha] = bytes
        assertTrue(engine.downloadTaskFile(sha, "text/plain"))
        assertTrue(files.hasFile(sha))
        uploaded["f".repeat(64)] = bytes
        assertEquals("bytes that do not match are not kept", false, engine.downloadTaskFile("f".repeat(64), null))
    }
}

private suspend fun com.alondra1.app.data.local.SyncDao.observeIssuesOnce() = observeIssues().first()
