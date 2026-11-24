package com.alondra1.app.domain

import com.alondra1.app.TestSupport.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskRulesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 23)
    private fun at(time: String, date: LocalDate = today) = ZonedDateTime.of(date, LocalTime.parse(time), zone)

    @Test fun myDayIsExplicitOnly() {
        val now = at("10:00")
        assertTrue(TaskRules.matches(SmartView.MY_DAY, task(myDay = today), now))
        assertFalse("due today is not automatically in My Day", TaskRules.matches(SmartView.MY_DAY, task(due = today), now))
        assertFalse("yesterday's My Day starts empty today", TaskRules.matches(SmartView.MY_DAY, task(myDay = today.minusDays(1)), now))
        assertFalse(TaskRules.matches(SmartView.MY_DAY, task(myDay = today, completed = true), now))
    }

    @Test fun dateOnlyTaskBecomesOverdueAfterItsDayEnds() {
        val t = task(due = today)
        assertFalse(TaskRules.isOverdue(t, at("23:59:59")))
        assertTrue(TaskRules.isOverdue(t, at("00:00", today.plusDays(1))))
    }

    @Test fun timedTaskIsOverdueAfterItsTime() {
        val t = task(due = today, dueTime = LocalTime.of(16, 0))
        assertFalse(TaskRules.isOverdue(t, at("15:59")))
        assertTrue(TaskRules.isOverdue(t, at("16:01")))
        assertFalse("completed tasks are never overdue", TaskRules.isOverdue(t.copy(completed = true), at("18:00")))
    }

    @Test fun smartViewSemantics() {
        val now = at("10:00")
        val past = task(title = "past", due = today.minusDays(2))
        val todayT = task(title = "today", due = today)
        val future = task(title = "future", due = today.plusDays(1))
        val undated = task(title = "undated")
        val star = task(title = "star", important = true)
        val done = task(title = "done", completed = true, due = today.plusDays(1))
        val all = listOf(past, todayT, future, undated, star, done)
        fun titles(v: SmartView, s: StatusFilter = StatusFilter.OPEN) = all.filter { TaskRules.matches(v, it, now, s) }.map { it.title }.toSet()
        assertEquals(setOf("past", "today", "future"), titles(SmartView.PLANNED))
        assertEquals(setOf("future"), titles(SmartView.UPCOMING))
        assertEquals(setOf("past"), titles(SmartView.OVERDUE))
        assertEquals(setOf("undated", "star"), titles(SmartView.UNSCHEDULED))
        assertEquals(setOf("star"), titles(SmartView.IMPORTANT))
        assertEquals(setOf("done"), titles(SmartView.COMPLETED))
        assertEquals(6, titles(SmartView.ALL, StatusFilter.ALL).size)
        assertEquals(setOf("done"), titles(SmartView.ALL, StatusFilter.COMPLETED))
        assertFalse("deleted tasks never match", TaskRules.matches(SmartView.ALL, past.copy(deletedUtc = Instant.EPOCH), now, StatusFilter.ALL))
    }

    @Test fun countsMatchTheLists() {
        val now = at("10:00")
        val tasks = listOf(task(myDay = today), task(myDay = today, completed = true), task(due = today.minusDays(1)), task(important = true))
        val counts = TaskRules.counts(tasks, now)
        SmartView.entries.forEach { v ->
            assertEquals(v.name, TaskRules.select(v, tasks, now, TaskSort.DUE_DATE).size, counts[v])
        }
    }

    @Test fun overdueAcrossDstChange() {
        // Berlin leaves DST on 2026-10-25 at 03:00; a 02:30 due time exists twice that night.
        val night = LocalDate.of(2026, 10, 25)
        val t = task(due = night, dueTime = LocalTime.of(2, 30))
        assertTrue(TaskRules.isOverdue(t, ZonedDateTime.of(night, LocalTime.of(4, 0), zone)))
        assertFalse(TaskRules.isOverdue(t, ZonedDateTime.of(night, LocalTime.of(1, 0), zone)))
    }

    @Test fun completedSortedNewestFirst() {
        val now = at("10:00")
        val a = task(title = "a", completed = true, completedAt = Instant.parse("2026-11-20T00:00:00Z"))
        val b = task(title = "b", completed = true, completedAt = Instant.parse("2026-11-22T00:00:00Z"))
        assertEquals(listOf("b", "a"), TaskRules.select(SmartView.COMPLETED, listOf(a, b), now, TaskSort.TITLE).map { it.title })
    }

    @Test fun sortOptions() {
        val x = task(title = "b", due = today.plusDays(2), created = Instant.parse("2026-11-02T00:00:00Z"))
        val y = task(title = "a", important = true, created = Instant.parse("2026-11-03T00:00:00Z"))
        val z = task(title = "c", due = today, created = Instant.parse("2026-11-01T00:00:00Z"))
        assertEquals(listOf("c", "b", "a"), TaskRules.sort(listOf(x, y, z), TaskSort.DUE_DATE).map { it.title })
        assertEquals("a", TaskRules.sort(listOf(x, y, z), TaskSort.IMPORTANCE).first().title)
        assertEquals(listOf("a", "b", "c"), TaskRules.sort(listOf(x, y, z), TaskSort.TITLE).map { it.title })
        assertEquals("a", TaskRules.sort(listOf(x, y, z), TaskSort.CREATED).first().title)
    }

    @Test fun titleRules() {
        assertEquals("Buy milk", TaskRules.cleanTitle("  Buy milk  "))
        assertNull(TaskRules.cleanTitle("   "))
        assertNull(TaskRules.cleanTitle("x".repeat(501)))
        assertEquals(500, TaskRules.cleanTitle("x".repeat(500))!!.length)
    }
}
