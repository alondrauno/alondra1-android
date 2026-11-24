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
                return when {
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
}

private suspend fun com.alondra1.app.data.local.SyncDao.observeIssuesOnce() = observeIssues().first()
