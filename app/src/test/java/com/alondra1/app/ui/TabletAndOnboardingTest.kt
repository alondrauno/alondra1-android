package com.alondra1.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.TestApp
import com.alondra1.app.TestSupport
import com.alondra1.app.navigation.Alondra1Root
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class TabletAndOnboardingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstRunOffersEmptyStartOrExamples() {
        val c = TestSupport.container()
        rule.setContent { Alondra1Root(c) }
        rule.waitUntilAtLeastOneExists(hasTestTag("startExamples"), 5_000)
        rule.onNodeWithTag("startExamples").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("task:Buy groceries"), 5_000)
        assertEquals(4, runBlocking { c.db.tasks().all().size })
    }

    @Test fun startingEmptyCreatesNoDemoData() {
        val c = TestSupport.container()
        rule.setContent { Alondra1Root(c) }
        rule.waitUntilAtLeastOneExists(hasTestTag("startEmpty"), 5_000)
        rule.onNodeWithTag("startEmpty").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Nothing planned for today"), 5_000)
        assertEquals(0, runBlocking { c.db.tasks().all().size })
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp-land")
    fun tabletUsesRailAndTwoPanes() {
        val c = TestSupport.container()
        runBlocking { c.settings.setOnboardingComplete(); c.todo.addTask("Tablet task", view = com.alondra1.app.domain.SmartView.MY_DAY) }
        rule.setContent { Alondra1Root(c) }
        rule.waitUntilAtLeastOneExists(hasTestTag("navRail"), 5_000)
        rule.onNodeWithTag("detailPane").assertIsDisplayed()
        rule.onNodeWithText("No task selected").assertIsDisplayed()
        rule.onNodeWithTag("task:Tablet task").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("taskTitle"), 5_000)
        rule.onNodeWithTag("task:Tablet task").assertIsDisplayed()
    }
}
