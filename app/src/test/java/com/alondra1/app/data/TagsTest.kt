package com.alondra1.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.TestSupport
import com.alondra1.app.domain.TagException
import com.alondra1.app.domain.TagRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** FEATURES_EXTENSION Part B §2 on Android: the rules shared with the cloud, Windows and Web, and the tag repository. */
@RunWith(AndroidJUnit4::class)
class TagsTest {
    private val c = TestSupport.container()

    @Test fun linkIdsAndNameKeysMatchTheCloud() {
        // Reference values computed by the backend (internal/tasks.TagLinkID / TagNameKey).
        assertEquals("tl__JvvuZgM8P4jKZwUvr_viMw3QiHi74LQF5fzj6XE", TagRules.linkId("travel", "mail", "acct-1|INBOX|42"))
        assertEquals("tl_Y8jUgutivQVAStEgDYiFl-mZ8FENm3IBSoS6-vCD", TagRules.linkId("t1", "note", "itinerary"))
        assertEquals("strasse", TagRules.nameKey("  Straße "))
        assertEquals(TagRules.nameKey("Café"), TagRules.nameKey("CAFÉ"))
        assertEquals("2 tasks, 1 note, 1 message", TagRules.describeUsage(mapOf("task" to 2, "note" to 1, "mail" to 1)))
    }

    @Test fun badNamesGetAPlainReason() {
        assertTrue(assertThrows(TagException::class.java) { TagRules.cleanName("  ") }.message!!.contains("Enter a name"))
        assertTrue(assertThrows(TagException::class.java) { TagRules.cleanName("a\u0007b") }.message!!.contains("control"))
        assertTrue(assertThrows(TagException::class.java) { TagRules.cleanName("x".repeat(65)) }.message!!.contains("64"))
    }

    @Test fun oneTagAcrossTypesRenameUnassignAndDelete() = runBlocking<Unit> {
        val task = c.todo.addTask("Book the ferry")
        val travel = c.tags.create("  Travel ", "blue")
        assertEquals("Travel", travel.name)
        assertThrows(TagException::class.java) { runBlocking { c.tags.create("TRAVEL") } }
        assertThrows(TagException::class.java) { runBlocking { c.tags.setColor(travel.id, "#ff0000") } }
        c.tags.setTag(TagRules.TASK, listOf(task.id), travel.id, true)
        c.tags.setTag(TagRules.NOTE, listOf("n1"), travel.id, true)
        c.tags.setTag(TagRules.EVENT, listOf("e1"), travel.id, true)
        assertEquals(3, c.tags.links().size)
        c.tags.rename(travel.id, "Trips")
        assertEquals("Trips", c.tags.tags().single().name)
        assertEquals(travel.id, c.tags.findOrCreate("trips").id)

        // Unassign keeps the link as removed; re-adding brings back the same record.
        c.tags.setTags(TagRules.NOTE, "n1", emptyList())
        val id = TagRules.linkId(travel.id, TagRules.NOTE, "n1")
        assertTrue(c.db.tags().link(id)!!.removed)
        c.tags.setTag(TagRules.NOTE, listOf("n1"), travel.id, true)
        assertEquals(false, c.db.tags().link(id)!!.removed)

        c.tags.delete(travel.id)
        assertEquals(0, c.tags.tags().size)
        assertEquals(0, c.tags.links().size)
        assertNotNull("the items stay", c.todo.getTask(task.id))
        assertThrows(TagException::class.java) { runBlocking { c.tags.setTag(TagRules.TASK, listOf(task.id), travel.id, true) } }
    }

    @Test fun anItemHoldsAtMostFiftyTags() = runBlocking<Unit> {
        val ids = (0 until TagRules.MAX_PER_ITEM).map { c.tags.create("Tag $it").id }
        c.tags.setTags(TagRules.NOTE, "busy", ids)
        val extra = c.tags.create("One more")
        assertTrue(assertThrows(TagException::class.java) { runBlocking { c.tags.setTag(TagRules.NOTE, listOf("busy"), extra.id, true) } }.message!!.contains("50"))
    }
}
