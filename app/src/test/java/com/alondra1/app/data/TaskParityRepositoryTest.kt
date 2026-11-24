package com.alondra1.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.AppContainer
import com.alondra1.app.core.FixedTimeSource
import com.alondra1.app.TestSupport
import com.alondra1.app.data.notifications.RecordingReminderScheduler
import com.alondra1.app.domain.Frequency
import com.alondra1.app.domain.RecurrenceRule
import com.alondra1.app.domain.TaskJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/** Steps, repeats with completion history, several reminders and list groups through the repository (Room). */
@RunWith(AndroidJUnit4::class)
class TaskParityRepositoryTest {
    private val time = FixedTimeSource(TestSupport.clockAt("2026-11-23T10:00:00"))
    private val c: AppContainer = TestSupport.container(time)
    private val reminders get() = c.reminders as RecordingReminderScheduler

    @Test fun stepsCanBeAddedCheckedReorderedAndRemoved() = runBlocking<Unit> {
        val t = c.todo.addTask("Move house")
        c.todo.addStep(t.id, "Boxes")
        c.todo.addStep(t.id, "Van")
        c.todo.addStep(t.id, "  ") // ignored
        var steps = c.todo.steps(c.todo.getTask(t.id)!!)
        assertEquals(listOf("Boxes", "Van"), steps.map { it.title })
        c.todo.toggleStep(t.id, steps[0].id)
        c.todo.moveStep(t.id, steps[1].id, -1)
        steps = c.todo.steps(c.todo.getTask(t.id)!!)
        assertEquals(listOf("Van", "Boxes"), steps.map { it.title })
        assertTrue(steps[1].completed && steps[1].completedUtc != null)
        c.todo.deleteStep(t.id, steps[0].id)
        assertEquals(listOf("Boxes"), c.todo.steps(c.todo.getTask(t.id)!!).map { it.title })
    }

    @Test fun completingARepeatingTaskAdvancesOnceAndKeepsHistory() = runBlocking<Unit> {
        val t = c.todo.addTask("Pay rent", dueDate = LocalDate.parse("2026-11-30"))
        c.todo.setRecurrence(t.id, RecurrenceRule(Frequency.Monthly))
        c.todo.addReminder(t.id, LocalDateTime.parse("2026-11-30T09:00"))
        c.todo.setCompleted(t.id, true)
        c.todo.setCompleted(t.id, true) // a second tap on the new occurrence completes it too, it never skips one
        val after = c.todo.getTask(t.id)!!
        assertFalse(after.completed)
        assertEquals(LocalDate.parse("2027-01-30"), after.dueDate)
        val history = c.todo.history(t.id)
        assertEquals(2, history.size)
        assertEquals(setOf(LocalDate.parse("2026-11-30"), LocalDate.parse("2026-12-30")), history.map { it.occurrenceDate }.toSet())
        assertEquals("2027-01-30T09:00", reminders.scheduled.values.single())
        assertEquals("2026-11-30", TaskJson.recurrence(after.recurrenceJson)!!.anchor.toString())
    }

    @Test fun repeatWithoutDueDateStartsToday() = runBlocking<Unit> {
        val t = c.todo.addTask("Stretch")
        c.todo.setRecurrence(t.id, RecurrenceRule(Frequency.Daily))
        assertEquals(LocalDate.parse("2026-11-23"), c.todo.getTask(t.id)!!.dueDate)
        c.todo.setRecurrence(t.id, null)
        assertNull(c.todo.getTask(t.id)!!.recurrenceJson)
    }

    @Test fun undoAfterCompletingRemovesTheUnsentHistoryEntry() = runBlocking<Unit> {
        val t = c.todo.addTask("Call mum")
        time.clock = java.time.Clock.offset(time.clock, java.time.Duration.ofSeconds(5))
        val before = c.todo.setCompleted(t.id, true)!!
        assertEquals(1, c.todo.history(t.id).size)
        time.clock = java.time.Clock.offset(time.clock, java.time.Duration.ofSeconds(5))
        c.todo.restore(before)
        assertFalse(c.todo.getTask(t.id)!!.completed)
        assertEquals(0, c.todo.history(t.id).size)
    }

    @Test fun removingAGroupUngroupsItsListsAndKeepsThem() = runBlocking<Unit> {
        val group = c.todo.createGroup("Home")
        val list = c.todo.createList("Groceries")
        c.todo.setListGroup(list.id, group.id)
        assertEquals(group.id, c.db.taskLists().get(list.id)!!.groupId)
        c.todo.setGroupCollapsed(group.id, true)
        assertTrue(c.db.listGroups().get(group.id)!!.collapsed)
        c.todo.deleteGroup(group.id)
        assertNull(c.db.taskLists().get(list.id)!!.groupId)
        assertNull(c.db.taskLists().get(list.id)!!.deletedUtc)
        assertTrue(c.todo.observeGroups().first().isEmpty())
    }
}
