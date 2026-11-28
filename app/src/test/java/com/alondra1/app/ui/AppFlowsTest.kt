package com.alondra1.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        rule.onNodeWithTag("viewSelector").assertTextContains("My Day")
        rule.onNodeWithText("Nothing planned for today").assertIsDisplayed()
        rule.onNodeWithTag("bottomNav").assertIsDisplayed()
        rule.onNodeWithTag("tab:My Day").assertIsSelected()
    }

    /** Android UI spec §4, §14: exactly My Day, Planned, Lists, Notes, in that order; no To-do, Important or Calendar tab. */
    @Test fun bottomNavigationHasTheFourDestinationsInOrder() {
        waitFor("bottomNav")
        val tabs = rule.onAllNodes(hasTestTag("bottomNav")).fetchSemanticsNodes().single()
        val labels = rule.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(hasTestTag("bottomNav")) and androidx.compose.ui.test.hasClickAction())
            .fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
        assertEquals(listOf("tab:My Day", "tab:Planned", "tab:Lists", "tab:Notes"), labels)
        assertTrue(tabs.size.height > 0)
        listOf("tab:To-do", "tab:Important", "tab:Calendar").forEach { assertTrue(rule.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isEmpty()) }
    }

    @Test fun importantIsInListsAndTheCalendarInPlanned() {
        runBlocking {
            container.todo.addTask("Starred", important = true)
            container.todo.addTask("Pay rent", dueDate = container.time.today())
        }
        waitFor("tab:Lists"); rule.onNodeWithTag("tab:Lists").performClick()
        waitFor("smart:Important"); rule.onNodeWithTag("smart:Important").performClick()
        waitFor("task:Starred")
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("tab:Planned"); rule.onNodeWithTag("tab:Planned").performClick()
        waitFor("task:Pay rent")
        assertTrue("grouped by date", rule.onAllNodes(hasText("Today")).fetchSemanticsNodes().isNotEmpty())
        selectCalendar()
        waitFor("calTask:Pay rent")
        // The choice is kept when coming back to Planned.
        rule.onNodeWithTag("tab:My Day").performClick(); waitFor("viewSelector")
        rule.onNodeWithTag("tab:Planned").performClick()
        waitFor("monthTitle")
    }

    @Test fun theCardShowsAttachmentsAndTheDetailListsThem() {
        runBlocking {
            val a = container.taskFiles.store("%PDF-1.7 x".byteInputStream(), "Ticket.pdf", null)
            container.todo.addTask("Trip", view = com.alondra1.app.domain.SmartView.MY_DAY, attachments = listOf(a))
        }
        waitFor("task:Trip")
        rule.onNodeWithTag("attachmentCount", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("task:Trip").performClick()
        waitFor("attachments")
        waitFor("attachment:Ticket.pdf")
        rule.onNodeWithTag("addAttachment").assertIsDisplayed()
    }

    private fun startComposer() {
        waitFor("composer")
        rule.onNodeWithTag("composer").performClick()
        waitFor("quickAdd")
    }

    @Test fun composerHasOnePrimaryActionAndClears() {
        startComposer()
        rule.onNodeWithTag("quickAddButton").assertIsNotEnabled()
        rule.onNodeWithTag("quickAdd").performTextInput("Call mom")
        rule.onNodeWithTag("quickAddButton").assertIsEnabled().performClick()
        waitFor("task:Call mom")
        rule.onNodeWithTag("quickAdd").assertTextContains("", substring = true)
        rule.onNodeWithContentDescription("Close the composer").performClick()
        waitFor("composer")
    }

    @Test fun quickAddCompleteAndUndo() {
        startComposer()
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

    private fun openCalendar() {
        waitFor("tab:Planned"); rule.onNodeWithTag("tab:Planned").performClick()
        selectCalendar()
    }

    /** The List/Calendar switch, through its accessibility click (Robolectric drops the injected tap mid-transition). */
    private fun selectCalendar() {
        waitFor("plannedCalendar")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.onNodeWithTag("plannedCalendar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        waitFor("monthTitle")
    }

    @Test fun createEventFromCalendarAndSeeTasksOnTheSameDay() {
        runBlocking { container.todo.addTask("Pay rent", dueDate = container.time.today()) }
        openCalendar()
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
        openCalendar()
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
        waitFor("composer")
        rule.onNodeWithTag("composer").assertIsDisplayed()
        rule.onNodeWithTag("bottomNav").assertIsDisplayed()
        RuntimeEnvironment.setFontScale(1.0f)
    }

    /** Dev-only spec §1.1 item 8: version and environment, diagnostics without personal data, feedback on dev only. */
    @Test fun aboutShowsVersionEnvironmentDiagnosticsAndFeedback() {
        waitFor("tab:My Day")
        rule.onNodeWithContentDescription("Account and settings").performClick()
        waitFor("aboutVersion")
        rule.onNodeWithTag("aboutVersion").performScrollTo().assertTextContains(com.alondra1.app.BuildConfig.VERSION_NAME, substring = true)
        rule.onNodeWithTag("copyDiagnostics").performScrollTo().performClick()
        waitForText("Copied. Paste them into your feedback.")
        val copied = (RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java))
            .primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied, copied.startsWith("Alondra1 for Android ") && copied.contains("Changes waiting to sync: "))
        assertTrue("no email address in diagnostics", !copied.contains("@"))
        val dev = com.alondra1.app.BuildConfig.ENVIRONMENT == "dev"
        assertEquals(dev, rule.onAllNodes(hasTestTag("sendFeedback")).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * FEATURES_EXTENSION Part B §2: a tagged task shows #tag on its card and its chip in the details, the tag filter
     * narrows the list, and Settings → Tags shows the usage and everything with the tag. (Picking a tag in the dialog
     * is an instrumented test: DialogFlowsTest.)
     */
    @Test fun taggedTaskShowsOnTheCardFiltersAndInSettings() {
        runBlocking {
            val task = container.todo.addTask("Book the ferry", view = com.alondra1.app.domain.SmartView.MY_DAY)
            container.todo.addTask("Water the plants", view = com.alondra1.app.domain.SmartView.MY_DAY)
            val travel = container.tags.create("Travel", "blue")
            container.tags.setTag(com.alondra1.app.domain.TagRules.TASK, listOf(task.id), travel.id, true)
        }
        waitForText("#Travel")
        waitFor("tagFilter:Travel"); rule.onNodeWithTag("tagFilter:Travel").performClick()
        waitGone("task:Water the plants")
        rule.onNodeWithTag("task:Book the ferry").performClick()
        waitFor("tag:Travel")
        rule.onNodeWithContentDescription("Back").performClick()

        waitFor("tab:My Day")
        rule.onNodeWithContentDescription("Account and settings").performClick()
        waitFor("tagSettings"); rule.onNodeWithTag("tagSettings").performClick()
        waitFor("tagRow:Travel")
        waitForText("1 task · Blue")
        rule.onNodeWithText("Show items").performClick()
        waitFor("tagged:Book the ferry")
    }
}
