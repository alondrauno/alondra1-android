package com.alondra1.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.Alondra1App
import com.alondra1.app.MainActivity
import com.alondra1.app.TestApp
import com.alondra1.app.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class AppFlowsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as Alondra1App).container
    /** Waits while advancing Robolectric's main-looper clock, so debounces (autosave, search) actually fire. */
    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        condition()
    }
    private fun waitFor(tag: String) = waitUntil { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = waitUntil { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(tag: String) = waitUntil { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    @Test fun myDayIsTheStartAndShowsAnEmptyState() {
        waitFor("viewSelector")
        rule.onNodeWithText("My Day").assertIsDisplayed()
        rule.onNodeWithText("Nothing planned for today").assertIsDisplayed()
        rule.onNodeWithTag("bottomNav").assertIsDisplayed()
    }

    @Test fun quickAddCompleteAndUndo() {
        waitFor("quickAdd")
        rule.onNodeWithTag("quickAdd").performTextInput("Buy milk")
        rule.onNodeWithTag("quickAdd").performImeAction()
        waitFor("task:Buy milk")
        val task = runBlocking { container.db.tasks().all().single() }
        assertEquals("added to My Day", container.time.today(), task.myDayDate)

        rule.onNodeWithContentDescription("Complete Buy milk").performClick()
        waitGone("task:Buy milk")
        waitForText("Undo")
        rule.onNodeWithText("Undo").performClick()
        waitFor("task:Buy milk")
        assertTrue(runBlocking { !container.db.tasks().get(task.id)!!.completed })
    }

    @Test fun openTaskEditTitleAndBack() {
        runBlocking { container.todo.addTask("Draft title", view = com.alondra1.app.domain.SmartView.MY_DAY) }
        waitFor("task:Draft title")
        rule.onNodeWithTag("task:Draft title").performClick()
        waitFor("taskTitle")
        rule.onNodeWithTag("taskTitle").performTextClearance()
        rule.onNodeWithTag("taskTitle").performTextInput("Draft title v2")
        waitUntil { runBlocking { container.db.tasks().all().single().title } == "Draft title v2" }
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("task:Draft title v2")
    }

    @Test fun noteAutosavesAndSurvivesRecreation() {
        waitFor("tab:Notes")
        rule.onNodeWithTag("tab:Notes").performClick()
        waitFor("newNote")
        rule.onNodeWithTag("newNote").performClick()
        waitFor("noteBody")
        rule.onNodeWithTag("noteTitle").performTextInput("Packing list")
        rule.onNodeWithTag("noteBody").performTextInput("passport\ncharger")
        waitUntil { runBlocking { container.db.notes().search("%passport%", 5).isNotEmpty() } }

        rule.activityRule.scenario.recreate()
        waitFor("noteBody")
        rule.onNodeWithText("Packing list").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("note:Packing list")
    }

    @Test fun createEventFromCalendarAndSeeTasksOnTheSameDay() {
        runBlocking { container.todo.addTask("Pay rent", dueDate = container.time.today()) }
        waitFor("tab:Calendar"); rule.onNodeWithTag("tab:Calendar").performClick()
        waitFor("calTask:Pay rent")
        rule.onNodeWithTag("addEventOnDay").performClick()
        waitFor("eventTitle")
        rule.onNodeWithTag("eventTitle").performTextInput("Dentist")
        rule.onNodeWithTag("saveEvent").performClick()
        waitFor("event:Dentist")
        rule.onNodeWithTag("calTask:Pay rent").assertIsDisplayed()
        assertEquals("tasks are not duplicated as events", 1, runBlocking { container.db.events().search("%%", 10).size })
    }

    @Test fun eventWithoutTitleShowsValidation() {
        waitFor("tab:Calendar"); rule.onNodeWithTag("tab:Calendar").performClick()
        waitFor("addEventOnDay"); rule.onNodeWithTag("addEventOnDay").performClick()
        waitFor("saveEvent"); rule.onNodeWithTag("saveEvent").performClick()
        waitFor("eventError")
        rule.onNodeWithText("Add a title").assertIsDisplayed()
    }

    @Test fun searchFindsTasksNotesAndEvents() {
        runBlocking {
            container.todo.addTask("Alpha task")
            container.notes.save("n", "Alpha note", "")
        }
        waitFor("viewSelector")
        rule.onNodeWithContentDescription("Search").performClick()
        waitFor("searchField")
        rule.onNodeWithTag("searchField").performTextInput("alpha")
        waitFor("result:task:Alpha task")
        rule.onNodeWithTag("result:note:Alpha note").assertIsDisplayed()
        rule.onNodeWithTag("result:task:Alpha task").performClick()
        waitFor("taskTitle")
    }

    @Test fun lightThemeAndLargeFontStillUsable() {
        runBlocking { container.settings.setTheme(ThemeMode.LIGHT) }
        RuntimeEnvironment.setFontScale(2.0f)
        rule.activityRule.scenario.recreate()
        waitFor("quickAdd")
        rule.onNodeWithTag("quickAdd").assertIsDisplayed()
        rule.onNodeWithTag("bottomNav").assertIsDisplayed()
        RuntimeEnvironment.setFontScale(1.0f)
    }
}
