package com.alondra1.app.domain

import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class NoteAndEventRulesTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test fun noteTitleDerivation() {
        assertEquals("Shopping", NoteRules.displayTitle("Shopping", "milk"))
        assertEquals("milk and eggs", NoteRules.displayTitle("  ", "\n\n  milk and eggs \nbread"))
        assertEquals("Untitled note", NoteRules.displayTitle("", "   \n "))
        assertEquals(80, NoteRules.displayTitle("", "x".repeat(200)).length)
        assertTrue(NoteRules.isEmpty(" ", "\n"))
    }

    @Test fun taskTitleFromNote() {
        val note = NoteEntity(id = "n", title = "", body = "Call the plumber\nabout the sink", createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH)
        assertEquals("Call the plumber", NoteRules.taskTitleFrom(note, null))
        assertEquals("about the sink", NoteRules.taskTitleFrom(note, "  about the sink "))
        assertEquals("", NoteRules.taskTitleFrom(note.copy(body = ""), null))
    }

    private fun timed(start: String, end: String) = EventEntity(
        id = "e", title = "t", allDay = false,
        startUtc = LocalDateTime.parse(start).atZone(zone).toInstant(), endUtc = LocalDateTime.parse(end).atZone(zone).toInstant(),
        timeZoneId = zone.id, createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH,
    )

    private fun allDay(start: String, end: String) = EventEntity(
        id = "e", title = "t", allDay = true, startDate = LocalDate.parse(start), endDate = LocalDate.parse(end),
        timeZoneId = zone.id, createdUtc = Instant.EPOCH, modifiedUtc = Instant.EPOCH,
    )

    @Test fun validation() {
        val d = LocalDate.of(2026, 11, 23)
        assertEquals(EventError.EMPTY_TITLE, EventRules.validate(" ", true, d, d, null, null))
        assertEquals(EventError.END_BEFORE_START, EventRules.validate("x", true, d, d.minusDays(1), null, null))
        assertNull(EventRules.validate("x", true, d, d, null, null))
        val s = Instant.parse("2026-11-23T10:00:00Z")
        assertEquals(EventError.END_BEFORE_START, EventRules.validate("x", false, null, null, s, s.minusSeconds(60)))
        assertNull("zero-length events are allowed", EventRules.validate("x", false, null, null, s, s))
        assertEquals(EventError.MISSING_TIME, EventRules.validate("x", false, null, null, s, null))
    }

    @Test fun allDayEventsSpanInclusiveDatesInAnyZone() {
        val e = allDay("2026-11-23", "2026-11-25")
        listOf(zone, ZoneId.of("America/Los_Angeles"), ZoneId.of("Pacific/Auckland")).forEach { z ->
            assertTrue(EventRules.occursOn(e, LocalDate.of(2026, 11, 23), z))
            assertTrue(EventRules.occursOn(e, LocalDate.of(2026, 11, 25), z))
            assertFalse(EventRules.occursOn(e, LocalDate.of(2026, 11, 26), z))
        }
    }

    @Test fun timedEventsAcrossMidnight() {
        val e = timed("2026-11-23T22:00", "2026-11-24T01:00")
        assertTrue(EventRules.occursOn(e, LocalDate.of(2026, 11, 23), zone))
        assertTrue(EventRules.occursOn(e, LocalDate.of(2026, 11, 24), zone))
        assertFalse(EventRules.occursOn(e, LocalDate.of(2026, 11, 25), zone))
        val endsAtMidnight = timed("2026-11-23T22:00", "2026-11-24T00:00")
        assertFalse("ending exactly at midnight does not spill into the next day", EventRules.occursOn(endsAtMidnight, LocalDate.of(2026, 11, 24), zone))
    }

    @Test fun eventOnDstShortDay() {
        // 2026-03-29 has 23 hours in Berlin.
        val e = timed("2026-03-29T01:30", "2026-03-29T04:00")
        assertTrue(EventRules.occursOn(e, LocalDate.of(2026, 3, 29), zone))
        assertFalse(EventRules.occursOn(e, LocalDate.of(2026, 3, 28), zone))
    }

    @Test fun reminderTimes() {
        assertEquals(LocalDateTime.parse("2026-11-23T09:50"), EventRules.reminderTime(timed("2026-11-23T10:00", "2026-11-23T11:00").copy(reminderMinutes = 10), zone))
        assertEquals(LocalDateTime.parse("2026-11-22T09:00"), EventRules.reminderTime(allDay("2026-11-23", "2026-11-23").copy(reminderMinutes = 1440), zone))
        assertNull(EventRules.reminderTime(allDay("2026-11-23", "2026-11-23"), zone))
    }
}
