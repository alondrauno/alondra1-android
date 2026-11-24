package com.alondra1.app.domain

import com.alondra1.app.TestSupport.task
import com.alondra1.app.data.sync.WireMapper
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Repeat, steps, reminders and completion with the Windows semantics (completion spec v1.1 §8.1): the shared cases
 * month end, leap year, selected weekdays, end date, and one record per completed occurrence.
 */
class TaskParityTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun monthlyFromTheAnchorDoesNotDrift() {
        val rule = RecurrenceRule(Frequency.Monthly, anchor = d("2026-01-31"))
        val feb = Recurrence.next(rule, d("2026-01-31"))
        assertEquals(d("2026-02-28"), feb)
        assertEquals(d("2026-03-31"), Recurrence.next(rule, feb!!))
        assertEquals(d("2026-04-30"), Recurrence.next(rule, d("2026-03-31")))
    }

    @Test fun yearlyOnLeapDay() {
        val rule = RecurrenceRule(Frequency.Yearly, anchor = d("2024-02-29"))
        assertEquals(d("2025-02-28"), Recurrence.next(rule, d("2024-02-29")))
        assertEquals(d("2026-02-28"), Recurrence.next(rule, d("2025-02-28")))
        assertEquals(d("2028-02-29"), Recurrence.next(rule, d("2027-02-28")))
    }

    @Test fun selectedWeekdaysEveryOtherWeek() {
        // Anchor Monday 2026-11-23; Mon and Thu every 2 weeks.
        val rule = RecurrenceRule(Frequency.Weekly, interval = 2, daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), anchor = d("2026-11-23"))
        assertEquals(d("2026-11-26"), Recurrence.next(rule, d("2026-11-23")))
        assertEquals(d("2026-12-07"), Recurrence.next(rule, d("2026-11-26"))) // skips the week in between
        assertEquals(d("2026-12-10"), Recurrence.next(rule, d("2026-12-07")))
    }

    @Test fun seriesEndsAtUntil() {
        val rule = RecurrenceRule(Frequency.Daily, until = d("2026-11-25"))
        assertEquals(d("2026-11-25"), Recurrence.next(rule, d("2026-11-24")))
        assertNull(Recurrence.next(rule, d("2026-11-25")))
    }

    @Test fun recurrenceJsonRoundTripsInContractNames() {
        val rule = RecurrenceRule(Frequency.Weekly, 2, setOf(DayOfWeek.SUNDAY, DayOfWeek.FRIDAY), d("2027-01-01"), d("2026-11-22"))
        val json = TaskJson.recurrenceJson(rule)!!
        assertTrue(json, json.contains("\"daysOfWeek\":[\"Sunday\",\"Friday\"]"))
        assertEquals(rule, TaskJson.recurrence(json))
        assertEquals("Every 2 weeks on Fri, Sun until Jan 1, 2027", Recurrence.describe(rule))
    }

    @Test fun completingARepeatingTaskMovesItToTheNextOccurrenceOnce() {
        val t = task(id = "t", due = d("2026-01-31")).copy(
            startDate = d("2026-01-29"),
            recurrenceJson = TaskJson.recurrenceJson(RecurrenceRule(Frequency.Monthly, anchor = d("2026-01-31"))),
            stepsJson = """[{"id":"s1","title":"Pay","completed":true,"completedUtc":"2026-01-30T10:00:00Z"}]""",
            remindersJson = """[{"id":"r1","at":"2026-01-31T09:00:00"}]""",
            myDayDate = d("2026-01-31"),
        )
        val now = Instant.parse("2026-01-31T12:00:00Z")
        val (next, record) = TaskCompletion.complete(t, now, d("2026-01-31")) { "c1" }
        assertFalse("a repeating task stays open", next.completed)
        assertEquals(d("2026-02-28"), next.dueDate)
        assertEquals(d("2026-02-26"), next.startDate) // moved by the same 28 days
        assertEquals(LocalDateTime.parse("2026-02-28T09:00"), TaskJson.reminders(next.remindersJson).single().at)
        assertFalse(TaskJson.steps(next.stepsJson).single().completed)
        assertNull("it leaves today's My Day", next.myDayDate)
        assertEquals(CompletionRecord("c1", "t", t.title, t.listId, now, d("2026-01-31")), record)
    }

    @Test fun completingAPlainTaskMarksItDone() {
        val now = Instant.parse("2026-11-24T08:00:00Z")
        val (done, record) = TaskCompletion.complete(task(id = "p"), now, d("2026-11-24")) { "c2" }
        assertTrue(done.completed)
        assertEquals(now, done.completedUtc)
        assertNull(record.occurrenceDate)
    }

    @Test fun repeatingTaskWithoutDueDateCompletesNormally() {
        val t = task(id = "r").copy(recurrenceJson = TaskJson.recurrenceJson(RecurrenceRule(Frequency.Daily)))
        assertTrue(TaskCompletion.complete(t, Instant.EPOCH, d("2026-11-24")) { "c" }.first.completed)
    }

    @Test fun stepsKeepUnknownKeysWhenEdited() {
        val previous = """[{"id":"s1","title":"Old","completed":false,"futureField":{"x":1}}]"""
        val edited = TaskJson.stepsJson(TaskJson.steps(previous).map { it.copy(title = "New") }, previous)
        assertTrue(edited, edited.contains("\"futureField\":{\"x\":1}"))
        assertTrue(edited.contains("\"title\":\"New\""))
    }

    @Test fun wireRoundTripIsLossless() {
        val fromWindows = WireMapper.json.parseToJsonElement(
            """{"id":"t1","listId":"l1","title":"Pay rent","notes":"","completed":false,"important":false,"position":1,
               "createdUtc":"2026-11-01T08:00:00Z","modifiedUtc":"2026-11-02T08:00:00Z","startDate":"2026-11-28","dueDate":"2026-11-30",
               "recurrence":{"frequency":"Monthly","interval":1,"anchor":"2026-01-31"},
               "subtasks":[{"id":"s1","title":"Transfer","completed":false}],
               "reminders":[{"id":"r1","at":"2026-11-30T09:00:00","deliveredFor":"2026-11-30T09:00:00"},{"id":"r2","at":"2026-11-29T18:00:00"}],
               "attachments":[{"id":"a1","name":"lease.pdf"}],"futureField":true}""",
        ).jsonObject
        val local = WireMapper.taskFrom(fromWindows, 5, null)
        val out = WireMapper.task(local)
        assertEquals("2026-11-28", out["startDate"]!!.jsonPrimitive.content)
        assertEquals(2, out["reminders"]!!.jsonArray.size)
        assertFalse("delivery state is device-local", out["reminders"].toString().contains("deliveredFor"))
        assertEquals(fromWindows["attachments"], out["attachments"])
        assertEquals(fromWindows["futureField"], out["futureField"])
        assertEquals(fromWindows["recurrence"], out["recurrence"])
        assertEquals(fromWindows["subtasks"], out["subtasks"])
    }
}
