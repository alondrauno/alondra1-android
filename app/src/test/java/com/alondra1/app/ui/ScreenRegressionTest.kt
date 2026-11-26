package com.alondra1.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alondra1.app.Alondra1App
import com.alondra1.app.MainActivity
import com.alondra1.app.TestApp
import com.alondra1.app.domain.SmartView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Dev-only spec AND-03 (review T1/U7): a small, focused screenshot and accessibility set for My Day, Planned, Lists,
 * Notes and a task's attachment detail, on a 360×640 dp phone.
 *
 * Accessibility is asserted on each screen: every control that can be tapped has a name a screen reader can say, and
 * a touch target of at least 48×48 dp. Screenshots are recorded to `app/build/outputs/screens/` (a CI artifact) for a
 * person to compare; they are not diffed pixel by pixel, because font rendering differs between machines.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as Alondra1App).container
    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        condition()
    }
    private fun waitFor(tag: String) = waitUntil { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        rule.waitForIdle()
    }

    private fun seed() = runBlocking {
        val today = container.time.today()
        val ticket = container.taskFiles.store("%PDF-1.7 x".byteInputStream(), "Ticket.pdf", null)
        container.todo.addTask("Trip to Lisbon", view = SmartView.MY_DAY, attachments = listOf(ticket))
        container.todo.addTask("Pay rent", view = SmartView.MY_DAY, dueDate = today, important = true)
        container.todo.addTask("Call the bank about the card that expires next month", dueDate = today.plusDays(2))
        container.notes.create("Packing list", text = "passport, charger")
    }

    private fun tab(name: String) {
        waitFor("tab:$name"); rule.onNodeWithTag("tab:$name").performClick()
    }

    @Test fun myDay() {
        seed()
        waitFor("task:Trip to Lisbon")
        check("my-day")
    }

    @Test fun planned() {
        seed()
        tab("Planned")
        waitFor("task:Pay rent")
        check("planned")
        rule.onNodeWithTag("plannedCalendar").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("dayHeading")
        check("planned-calendar")
    }

    @Test fun lists() {
        seed()
        tab("Lists")
        waitFor("smart:Important")
        check("lists")
    }

    @Test fun notes() {
        seed()
        tab("Notes")
        waitFor("note:Packing list")
        check("notes")
    }

    @Test fun taskAttachmentDetail() {
        seed()
        waitFor("task:Trip to Lisbon"); rule.onNodeWithTag("task:Trip to Lisbon").performClick()
        waitFor("attachment:Ticket.pdf")
        check("task-attachment-detail")
    }

    private fun check(screen: String) {
        settle()
        record(screen)
        val problems = accessibilityProblems()
        assertTrue("$screen accessibility:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun record(screen: String) {
        val dir = File("build/outputs/screens").apply { mkdirs() }
        // Drawn straight from the window's view: captureToImage waits for a frame that Robolectric's paused looper
        // never delivers.
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun accessibilityProblems(): List<String> {
        val min = with(rule.density) { 48.dp.toPx() } - 0.5f
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        return rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { n ->
            val bounds = n.boundsInRoot
            // Only what is on screen: a control scrolled partly out of view is checked where it is fully visible.
            if (bounds.isEmpty || bounds.top < root.top || bounds.bottom > root.bottom) return@mapNotNull null
            val name = describe(n)
            val touch = n.touchBoundsInRoot
            when {
                label(n).isBlank() -> "unlabelled control $name"
                touch.width < min || touch.height < min ->
                    "small touch target $name: ${(touch.width / rule.density.density).toInt()}×${(touch.height / rule.density.density).toInt()} dp"
                else -> null
            }
        }
    }

    private fun label(n: SemanticsNode): String = listOfNotNull(
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        n.config.getOrNull(SemanticsProperties.EditableText)?.text,
    ).joinToString(" ").trim()

    private fun describe(n: SemanticsNode) =
        (n.config.getOrNull(SemanticsProperties.TestTag) ?: label(n).ifBlank { "#${n.id}" }) + " at ${n.boundsInRoot}"
}
