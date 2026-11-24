package com.alondra1.app.sync

import com.alondra1.app.TestSupport.task
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.sync.WireMapper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WireMapperTest {
    /** The Windows golden fixture (contract/fixtures, mirrored from alondra1-windows). */
    private val windowsTask: JsonObject = WireMapper.json.parseToJsonElement(
        """{"id":"t-win","listId":"l1","title":"Pay rent","notes":"","completed":false,"important":false,"position":3,
           "createdUtc":"2026-11-01T08:00:00+00:00","modifiedUtc":"2026-11-02T08:00:00+00:00","dueDate":"2026-11-30",
           "recurrence":{"frequency":"Monthly","interval":1,"daysOfWeek":[],"anchor":"2026-01-31"},
           "subtasks":[{"id":"s1","title":"Transfer","completed":false}],"reminders":[{"id":"t-win-r","at":"2026-11-30T09:00:00"}]}""",
    ).jsonObject

    @Test fun fieldsAndroidDoesNotEditSurviveARoundTrip() {
        val local = WireMapper.taskFrom(windowsTask, 4, null)
        assertEquals(LocalDateTime.parse("2026-11-30T09:00"), local.reminderAt)
        val edited = local.copy(title = "Pay rent now", important = true)
        val out = WireMapper.task(edited)
        assertEquals("Monthly", out["recurrence"]!!.jsonObject["frequency"]!!.jsonPrimitive.content)
        assertEquals(1, out["subtasks"]!!.jsonArray.size)
        assertEquals("Pay rent now", out["title"]!!.jsonPrimitive.content)
        assertEquals("2026-11-30T09:00:00", out["reminders"]!!.jsonArray.first().jsonObject["at"]!!.jsonPrimitive.content)
    }

    @Test fun contractFormats() {
        val t = task(id = "abc", due = LocalDate.parse("2026-11-24"), dueTime = LocalTime.of(16, 0)).copy(reminderAt = LocalDateTime.parse("2026-11-24T15:30"))
        val o = WireMapper.task(t)
        assertEquals("16:00:00", o["dueTime"]!!.jsonPrimitive.content)
        assertEquals("2026-11-24", o["dueDate"]!!.jsonPrimitive.content)
        assertEquals("abc-r", o["reminders"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(o["createdUtc"]!!.jsonPrimitive.content.endsWith("Z"))
        assertFalse("device-only links are not synced", o.containsKey("sourceNoteId"))
    }

    @Test fun clearedFieldsAreNotSentAndEventsKeepDateOnlySemantics() {
        val local = WireMapper.taskFrom(windowsTask, 4, null).copy(dueDate = null, reminderAt = null)
        val out = WireMapper.task(local)
        assertNull(out["dueDate"])
        assertEquals(0, out["reminders"]!!.jsonArray.size)
        val e = EventEntity(id = "e", title = "Trip", allDay = true, startDate = LocalDate.parse("2026-12-20"), endDate = LocalDate.parse("2026-12-27"),
            timeZoneId = "Europe/Berlin", createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH)
        val wire = WireMapper.event(e)
        assertNull(wire["startUtc"])
        assertEquals("2026-12-27", wire["endDate"]!!.jsonPrimitive.content)
        assertEquals(e.copy(serverVersion = 1), WireMapper.eventFrom(wire, 1).copy(modifiedUtc = Instant.EPOCH, createdUtc = Instant.EPOCH))
    }

    @Test fun matchesTheSharedNoteAndEventSchemas() {
        // The committed schemas list the allowed properties; the mapper must not send anything else.
        val schemaDir = File("../../alondra1-backend/contract/schemas").takeIf { it.exists() } ?: return
        fun props(name: String) = WireMapper.json.parseToJsonElement(File(schemaDir, name).readText()).jsonObject["properties"]!!.jsonObject.keys
        val note = WireMapper.note(com.alondra1.app.data.local.NoteEntity(id = "n", title = "t", body = "b", createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH))
        assertTrue(props("notes-v1.schema.json").containsAll(note.keys))
        val event = WireMapper.event(EventEntity(id = "e", title = "x", allDay = false, startUtc = Instant.EPOCH, endUtc = Instant.EPOCH, timeZoneId = "UTC",
            reminderMinutes = 10, createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH))
        assertTrue(props("calendar-v1.schema.json").containsAll(event.keys))
    }
}
