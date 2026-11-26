package com.alondra1.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alondra1.app.data.repository.NotesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.repository.NoteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Flows that need a real device or emulator: the shared rich-text editor in the system WebView (Robolectric has
 * none). Documents written on Windows must open and save on Android without losing anything.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DialogFlowsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val container get() = (rule.activity.application as Alondra1App).container
    private val json = Json

    private val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC", android.util.Base64.DEFAULT)

    /** Every feature of the format, including a part from a newer version that must be kept unchanged. */
    private fun rich(assetId: String) = """{"schemaVersion":1,"type":"doc","content":[
        {"type":"heading","attrs":{"level":1,"textAlign":"center"},"content":[{"type":"text","text":"Project ideas"}]},
        {"type":"paragraph","attrs":{"indent":2,"spacing":"relaxed"},"content":[{"type":"text","text":"Important","marks":[{"type":"bold"},{"type":"textStyle","attrs":{"color":"#2563EB","fontSize":"18pt","fontFamily":"Georgia"}}]},{"type":"text","text":" work","marks":[{"type":"highlight","attrs":{"color":"#FDE047"}}]}]},
        {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Outer"}]},{"type":"orderedList","attrs":{"start":1},"content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Inner"}]}]}]}]}]},
        {"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":false},"content":[{"type":"paragraph","content":[{"type":"text","text":"Call the plumber"}]}]},{"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"Done"}]}]}]},
        {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","attrs":{"colspan":1,"rowspan":1,"colwidth":[140],"backgroundColor":"#FEF3C7"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Name"}]}]},{"type":"tableHeader","attrs":{"colspan":1,"rowspan":1},"content":[{"type":"paragraph","attrs":{"textAlign":"right"},"content":[{"type":"text","text":"Days"}]}]}]},{"type":"tableRow","content":[{"type":"tableCell","attrs":{"colspan":1,"rowspan":1,"colwidth":[140]},"content":[{"type":"paragraph","content":[{"type":"text","text":"Ana"}]}]},{"type":"tableCell","attrs":{"colspan":1,"rowspan":1,"backgroundColor":"#DCFCE7"},"content":[{"type":"paragraph","attrs":{"textAlign":"right"},"content":[{"type":"text","text":"3"}]}]}]}]},
        {"type":"image","attrs":{"assetId":"$assetId","width":120,"alignment":"center","caption":"Logo"}},
        {"type":"futureWidget","attrs":{"x":1}},
        {"type":"paragraph","content":[{"type":"text","text":"End"}]}]}"""

    /** Drops null/empty attrs and treats marks as a set (the editor keeps them in schema order). */
    private fun canonical(e: JsonElement?): JsonElement? = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is kotlinx.serialization.json.JsonNull }.mapValues { (k, v) ->
            when (k) {
                "marks" -> JsonArray((v as JsonArray).map { canonical(it)!! }.sortedBy { it.toString() })
                else -> canonical(v)!!
            }
        }.filterNot { (k, v) -> k == "attrs" && v is JsonObject && v.isEmpty() })
        is JsonArray -> JsonArray(e.map { canonical(it)!! })
        else -> e
    }

    private fun webView(): WebView {
        var found: WebView? = null
        rule.waitUntil(20_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fun search(v: View) { if (v is WebView) found = v else if (v is ViewGroup) (0 until v.childCount).forEach { search(v.getChildAt(it)) } }
                search(rule.activity.window.decorView)
            }
            found != null
        }
        return found!!
    }

    private fun js(web: WebView, script: String): String {
        var out = "null"
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.evaluateJavascript(script) { out = it; latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        return out
    }

    private fun flushed(web: WebView): JsonObject? {
        val raw = js(web, "window.alondra && window.alondra.editor ? window.alondra.flush() : null")
        if (raw == "null") return null
        return json.parseToJsonElement((json.parseToJsonElement(raw) as JsonPrimitive).content).jsonObject
    }

    private fun openNote(title: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab:Notes"), 10_000)
        rule.onNodeWithTag("tab:Notes").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("note:$title"), 10_000)
        rule.onNodeWithTag("note:$title").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("richEditor"), 10_000)
    }

    @Test fun aWindowsDocumentOpensAndSavesUnchangedInTheAndroidEditor() {
        val asset = runBlocking { container.noteAssets.add(png) }
        val doc = rich(asset.sha256)
        runBlocking { container.notes.saveContent("n1", "Plan", doc, "Project ideas", listOf(asset.sha256)) }
        openNote("Plan")
        val web = webView()
        rule.waitUntil(20_000) { flushed(web)?.get("title")?.let { (it as JsonPrimitive).content } == "Plan" }
        val snapshot = flushed(web)!!
        assertEquals(canonical(json.parseToJsonElement(doc)), canonical(snapshot["doc"]))
        // The picture is served from local storage (no network).
        rule.waitUntil(10_000) { js(web, "(document.querySelector('.a1-img-wrap img')||{}).naturalWidth || 0") == "1" }

        // An edit is saved when leaving the note; everything else stays as it was.
        js(web, "alondra.editor.commands.insertContentAt(alondra.editor.state.doc.content.size - 1, ' (edited on Android)')")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { runBlocking { container.notes.getNote("n1")!!.docJson!!.contains("edited on Android") } }
        val saved = json.parseToJsonElement(container.let { runBlocking { it.notes.getNote("n1")!!.docJson!! } }).jsonObject
        assertTrue(saved.toString().contains("futureWidget"))
        assertTrue(saved.toString().contains("#2563EB"))
        assertTrue(saved.toString().contains(asset.sha256))
        assertEquals(listOf(asset.sha256), NotesRepository.decodeIds(runBlocking { container.notes.getNote("n1")!!.assetsJson }))
    }

    @Test fun aChecklistItemBecomesALinkedTask() {
        val asset = runBlocking { container.noteAssets.add(png) }
        runBlocking { container.notes.saveContent("n2", "Chores", rich(asset.sha256), "", listOf(asset.sha256)) }
        openNote("Chores")
        val web = webView()
        rule.waitUntil(20_000) { flushed(web) != null }
        js(web, """(function(){ const e = alondra.editor; let pos = -1;
            e.state.doc.descendants((n, p) => { if (pos < 0 && n.isText && n.text === 'Call the plumber') pos = p + 2; return pos < 0; });
            e.commands.setTextSelection(pos); document.querySelector('[data-bar="task"] [data-id="task"]').click(); })()""")
        rule.waitUntil(10_000) { runBlocking { container.db.tasks().all() }.any { it.title == "Call the plumber" && it.sourceNoteId == "n2" } }
        rule.waitUntil(10_000) { flushed(web).toString().contains("alondra://task/") }
        rule.waitUntil(10_000) { runBlocking { NotesRepository.decodeLinks(container.notes.getNote("n2")!!.linksJson) }.any { it.label == "Call the plumber" } }
    }

    @Test fun linkedTasksAndEventsOpenFromTheNote() {
        val task = runBlocking { container.todo.addTask("Book the venue") }
        val now = java.time.Instant.now()
        val event = runBlocking { container.calendar.save(EventEntity(id = "", title = "Dentist", allDay = true, startDate = java.time.LocalDate.now(),
            endDate = java.time.LocalDate.now(), timeZoneId = "UTC", createdUtc = now, modifiedUtc = now)) }
        runBlocking {
            container.notes.saveContent("n3", "Links", rich("0".repeat(64)), "", emptyList())
            container.notes.addLink("n3", NoteLink(NoteLink.TASK, task.id, task.title))
            container.notes.addLink("n3", NoteLink(NoteLink.EVENT, event.id, event.title))
        }
        openNote("Links")
        rule.waitUntilAtLeastOneExists(hasTestTag("link:event:${event.id}"), 10_000)
        rule.onNodeWithTag("link:event:${event.id}").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Dentist"), 10_000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntilAtLeastOneExists(hasTestTag("link:task:${task.id}"), 10_000)
        rule.onNodeWithTag("link:task:${task.id}").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Book the venue"), 10_000)
    }

    @Test fun aSyncConflictIsComparedAndCombinedIntoTheNote() {
        val other = """{"schemaVersion":1,"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Shared start"}]},{"type":"paragraph","content":[{"type":"text","text":"From Windows"}]}]}"""
        val mine = """{"schemaVersion":1,"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Shared start"}]},{"type":"paragraph","content":[{"type":"text","text":"From the phone"}]}]}"""
        runBlocking {
            container.notes.saveContent("n5", "Trip", other, "Shared start\nFrom Windows", emptyList())
            container.notes.saveContent("n5c", "Trip (conflict copy)", mine, "Shared start\nFrom the phone", emptyList())
            container.db.sync().addIssue(com.alondra1.app.data.local.SyncIssueEntity(entityType = "note", entityId = "n5", title = "Trip",
                message = "It was changed on another device too.", createdUtc = 0, copyId = "n5c"))
        }
        openNote("Trip")
        rule.waitUntilAtLeastOneExists(hasTestTag("compareVersions"), 10_000)
        rule.onNodeWithTag("compareVersions").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("version:other"), 10_000)
        rule.onNodeWithText("From Windows").assertExists()
        rule.onNodeWithText("From the phone").assertExists()
        rule.onNodeWithTag("conflict:combine").performClick()
        rule.waitUntil(10_000) { runBlocking { container.notes.getNote("n5c")!!.trashedUtc } != null }
        val combined = runBlocking { container.notes.getNote("n5")!!.docJson!! }
        assertTrue(combined.contains("From Windows") && combined.contains("From the phone"))
        assertEquals(1, Regex("Shared start").findAll(combined).count())
        assertTrue(runBlocking { container.db.sync().observeIssues().first() }.none { it.copyId == "n5c" })
        // The combined note opens in the editor, without the conflict banner.
        val web = webView()
        rule.waitUntil(20_000) { flushed(web)?.toString()?.contains("From the phone") == true }
        assertEquals(0, rule.onAllNodes(hasTestTag("conflictBanner")).fetchSemanticsNodes().size)
    }

    @Test fun meetingNotesStartFromACalendarEventAndReopenTheSameNote() {
        val now = java.time.Instant.now()
        val event = runBlocking { container.calendar.save(EventEntity(id = "", title = "Planning", description = "Room 4", allDay = true,
            startDate = java.time.LocalDate.now(), endDate = java.time.LocalDate.now(), timeZoneId = "UTC", createdUtc = now, modifiedUtc = now)) }
        // The calendar lives inside Planned.
        rule.waitUntilAtLeastOneExists(hasTestTag("tab:Planned"), 10_000)
        rule.onNodeWithTag("tab:Planned").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("plannedCalendar"), 10_000)
        rule.onNodeWithTag("plannedCalendar").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Planning"), 10_000)
        rule.onAllNodes(hasText("Planning"))[0].performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("meetingNotes"), 10_000)
        rule.onNodeWithTag("meetingNotes").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("richEditor"), 10_000)
        val note = runBlocking { container.notes.findLinked(NoteLink.EVENT, event.id) }!!
        assertEquals("Planning – meeting notes", note.title)
        val web = webView()
        rule.waitUntil(20_000) { flushed(web)?.toString()?.let { it.contains("Action items") && it.contains("Room 4") } == true }
        // Back to the event: Meeting notes opens the same note again.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntilAtLeastOneExists(hasTestTag("meetingNotes"), 10_000)
        rule.onNodeWithTag("meetingNotes").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("richEditor"), 10_000)
        assertEquals(1, runBlocking { container.notes.observeNotes().first() }.count { it.title == "Planning – meeting notes" })
    }

    @Test fun deletingANoteFromTrashAsksFirst() {
        runBlocking {
            container.notes.saveContent("n4", "Old list", rich("0".repeat(64)), "", emptyList())
            container.notes.trash(listOf("n4"))
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab:Notes"), 10_000)
        rule.onNodeWithTag("tab:Notes").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("noteViews"), 10_000)
        rule.onNodeWithTag("noteViews").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("noteView:Trash"), 10_000)
        rule.onNodeWithTag("noteView:Trash").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("note:Old list"), 10_000)
        rule.onAllNodesWithContentDescription("Note options")[0].performClick()
        rule.waitUntilAtLeastOneExists(hasText("Delete permanently"), 10_000)
        rule.onNodeWithText("Delete permanently").performClick()
        // Nothing is deleted until the user confirms.
        rule.waitUntilAtLeastOneExists(hasText("Delete note permanently?"), 10_000)
        assertEquals(null, runBlocking { container.notes.getNote("n4")!!.deletedUtc })
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(null, runBlocking { container.notes.getNote("n4")!!.deletedUtc })
        rule.onAllNodesWithContentDescription("Note options")[0].performClick()
        rule.waitUntilAtLeastOneExists(hasText("Delete permanently"), 10_000)
        rule.onNodeWithText("Delete permanently").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("confirmDeleteNote"), 10_000)
        rule.onNodeWithTag("confirmDeleteNote").performClick()
        rule.waitUntil(10_000) { runBlocking { container.notes.getNote("n4")!!.deletedUtc } != null }
    }
}
