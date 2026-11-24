package com.alondra1.app.domain

import com.alondra1.app.core.Ids
import com.alondra1.app.data.local.TaskEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Steps, repeats, reminders and completion records of the shared task contract (todo-v1), with the same semantics as
 * the Windows app (Core/Tasks: Recurrence, TaskBoard.SetCompletedAsync, Reminders.ShiftForRecurrence), which stays
 * normative (completion spec v1.1 §8.1). They are stored on the task as contract JSON, so a round trip keeps every
 * field, including ones this app does not edit.
 */
enum class Frequency { Daily, Weekly, Monthly, Yearly }

data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    /** First date of the series: monthly and yearly repeats count from it, so Jan 31 -> Feb 28 -> Mar 31. */
    val anchor: LocalDate? = null,
)

data class TaskStep(val id: String, val title: String, val completed: Boolean = false, val completedUtc: Instant? = null)

/** A reminder definition (synced). Delivery state (delivered, dismissed, snoozed) stays on the device. */
data class TaskReminder(val id: String, val at: LocalDateTime)

data class CompletionRecord(
    val id: String,
    val taskId: String,
    val title: String,
    val listId: String,
    val completedUtc: Instant,
    val occurrenceDate: LocalDate?,
)

object Recurrence {
    /** The first occurrence strictly after [current], or null when the series has ended. Deterministic. */
    fun next(rule: RecurrenceRule, current: LocalDate): LocalDate? {
        val interval = maxOf(1, rule.interval)
        val anchor = rule.anchor ?: current
        val next = when (rule.frequency) {
            Frequency.Daily -> current.plusDays(interval.toLong())
            Frequency.Weekly -> if (rule.daysOfWeek.isNotEmpty()) nextWeekly(rule.daysOfWeek, interval, anchor, current) else current.plusWeeks(interval.toLong())
            Frequency.Monthly -> nextByMonths(anchor, current, interval)
            Frequency.Yearly -> nextByMonths(anchor, current, interval * 12)
        }
        return if (rule.until != null && next.isAfter(rule.until)) null else next
    }

    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun nextWeekly(days: Set<DayOfWeek>, interval: Int, anchor: LocalDate, current: LocalDate): LocalDate {
        val anchorWeek = weekStart(anchor)
        for (offset in 1..(7 * interval + 7)) {
            val candidate = current.plusDays(offset.toLong())
            if (candidate.dayOfWeek !in days) continue
            val weeks = ChronoUnit.DAYS.between(anchorWeek, weekStart(candidate)) / 7
            if (weeks % interval == 0L) return candidate
        }
        return current.plusWeeks(interval.toLong())
    }

    private fun nextByMonths(anchor: LocalDate, current: LocalDate, step: Int): LocalDate {
        val months = (current.year - anchor.year) * 12 + current.monthValue - anchor.monthValue
        var k = maxOf(0, months - months % step)
        repeat(4) {
            val candidate = anchor.plusMonths(k.toLong()) // clamps to the month's last day, like the Windows app
            if (candidate.isAfter(current)) return candidate
            k += step
        }
        return current.plusMonths(step.toLong())
    }

    fun describe(rule: RecurrenceRule?): String {
        if (rule == null) return ""
        val every = maxOf(1, rule.interval)
        var text = when (rule.frequency) {
            Frequency.Daily -> if (every == 1) "Daily" else "Every $every days"
            Frequency.Weekly -> if (every == 1) "Weekly" else "Every $every weeks"
            Frequency.Monthly -> if (every == 1) "Monthly" else "Every $every months"
            Frequency.Yearly -> if (every == 1) "Yearly" else "Every $every years"
        }
        if (rule.frequency == Frequency.Weekly && rule.daysOfWeek.isNotEmpty()) {
            text += " on " + rule.daysOfWeek.sortedBy { it.value }.joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::titlecase) }
        }
        rule.until?.let { text += " until " + it.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) }
        return text
    }
}

/** Contract JSON for the fields stored as JSON on [TaskEntity]. Unknown keys inside items are kept by the caller. */
object TaskJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val localDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun dayName(d: DayOfWeek) = d.name.lowercase().replaceFirstChar(Char::titlecase)

    fun recurrence(text: String?): RecurrenceRule? {
        val o = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val frequency = o["frequency"]?.jsonPrimitive?.contentOrNull?.let { f -> Frequency.entries.firstOrNull { it.name == f } } ?: return null
        return RecurrenceRule(
            frequency = frequency,
            interval = o["interval"]?.jsonPrimitive?.intOrNull ?: 1,
            daysOfWeek = (o["daysOfWeek"] as? JsonArray).orEmpty().mapNotNull { e ->
                DayOfWeek.entries.firstOrNull { dayName(it) == e.jsonPrimitive.contentOrNull }
            }.toSet(),
            until = o["until"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
            anchor = o["anchor"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
        )
    }

    fun recurrenceJson(rule: RecurrenceRule?): String? = rule?.let { r ->
        buildJsonObject {
            put("frequency", r.frequency.name)
            put("interval", maxOf(1, r.interval))
            if (r.daysOfWeek.isNotEmpty()) put("daysOfWeek", buildJsonArray { r.daysOfWeek.sortedBy { it.value % 7 }.forEach { add(JsonPrimitive(dayName(it))) } })
            r.until?.let { put("until", it.toString()) }
            r.anchor?.let { put("anchor", it.toString()) }
        }.toString()
    }

    fun steps(text: String?): List<TaskStep> = array(text).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        TaskStep(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
            completed = o["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
            completedUtc = o["completedUtc"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        )
    }

    /** Writes steps back, keeping keys of each step this app does not know (matched by id). */
    fun stepsJson(steps: List<TaskStep>, previous: String?): String {
        val old = array(previous).mapNotNull { it as? JsonObject }.associateBy { it["id"]?.jsonPrimitive?.contentOrNull }
        return buildJsonArray {
            steps.forEach { s ->
                val m = old[s.id]?.toMutableMap() ?: mutableMapOf()
                m["id"] = JsonPrimitive(s.id)
                m["title"] = JsonPrimitive(s.title)
                m["completed"] = JsonPrimitive(s.completed)
                if (s.completedUtc != null) m["completedUtc"] = JsonPrimitive(s.completedUtc.toString()) else m.remove("completedUtc")
                add(JsonObject(m))
            }
        }.toString()
    }

    fun reminders(text: String?): List<TaskReminder> = array(text).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val at = o["at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
        TaskReminder(id, at)
    }

    /** Reminder definitions only: delivery state is device-local and never stored here. */
    fun remindersJson(reminders: List<TaskReminder>): String = buildJsonArray {
        reminders.forEach { r -> add(buildJsonObject { put("id", r.id); put("at", r.at.format(localDateTime)) }) }
    }.toString()

    private fun array(text: String?): List<JsonElement> =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
}

object TaskCompletion {
    /**
     * Completes a task (Windows semantics): one completion record per completion; a repeating task with a due date
     * stays one record and moves to its next occurrence (start date and reminders move by the same number of days,
     * steps reset, it leaves today's My Day); otherwise it is marked completed. Applied once, atomically, by the caller.
     */
    fun complete(task: TaskEntity, now: Instant, today: LocalDate, newId: () -> String = Ids::newId): Pair<TaskEntity, CompletionRecord> {
        val record = CompletionRecord(newId(), task.id, task.title, task.listId, now, task.dueDate)
        val rule = TaskJson.recurrence(task.recurrenceJson)
        val due = task.dueDate
        val next = if (rule != null && due != null) Recurrence.next(rule, due) else null
        val updated = if (rule != null && due != null && next != null) {
            val shift = ChronoUnit.DAYS.between(due, next)
            task.copy(
                dueDate = next,
                startDate = task.startDate?.plusDays(shift),
                remindersJson = TaskJson.remindersJson(TaskJson.reminders(task.remindersJson).map { it.copy(at = it.at.plusDays(shift)) }),
                stepsJson = TaskJson.stepsJson(TaskJson.steps(task.stepsJson).map { it.copy(completed = false, completedUtc = null) }, task.stepsJson),
                myDayDate = task.myDayDate?.takeIf { it != today },
                completed = false,
                completedUtc = null,
            )
        } else {
            task.copy(completed = true, completedUtc = now)
        }
        return updated to record
    }
}
