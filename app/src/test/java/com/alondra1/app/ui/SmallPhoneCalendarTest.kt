package com.alondra1.app.ui

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.Alondra1App
import com.alondra1.app.MainActivity
import com.alondra1.app.TestApp
import com.alondra1.app.data.local.EventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/** Review spec U3: Planned's calendar on a 360×640 dp phone shows the day heading, both add buttons and three items. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w360dp-h640dp")
// Real text metrics: the legacy shadows measure every line about 36 dp high, so the layout would not be the phone's.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmallPhoneCalendarTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as Alondra1App).container
    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        condition()
    }
    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = waitUntil { exists(tag) }

    @Test fun theSelectedDaysItemsFitAndTheWholeMonthIsOneTapAway() {
        val today = container.time.today()
        runBlocking {
            val now = Instant.now()
            container.calendar.save(EventEntity(id = "", title = "Standup", allDay = true, startDate = today, endDate = today,
                timeZoneId = "UTC", createdUtc = now, modifiedUtc = now))
            container.todo.addTask("Pay rent", dueDate = today)
            container.todo.addTask("Call bank", dueDate = today)
        }
        waitFor("tab:Planned"); rule.onNodeWithTag("tab:Planned").performClick()
        waitFor("plannedCalendar")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.onNodeWithTag("plannedCalendar").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("event:Standup")

        listOf("dayHeading", "addTaskOnDay", "addEventOnDay", "event:Standup", "calTask:Pay rent", "calTask:Call bank")
            .forEach { rule.onNodeWithTag(it).assertIsDisplayed() }

        // One week shown: today's; the whole month after the toggle.
        val otherWeek = if (today.dayOfMonth > 14) today.withDayOfMonth(1) else today.withDayOfMonth(today.lengthOfMonth())
        assertTrue("only the selected week at first", !exists("day:$otherWeek"))
        rule.onNodeWithTag("gridToggle").performClick()
        waitFor("day:$otherWeek")
        rule.onNodeWithTag("gridToggle").performClick()
        waitUntil { !exists("day:$otherWeek") }
    }
}
