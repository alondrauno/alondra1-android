package com.alondra1.app.domain

import com.alondra1.app.data.local.EventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class EventError(val message: String) {
    EMPTY_TITLE("Add a title"),
    END_BEFORE_START("The end must be after the start"),
    MISSING_TIME("Choose a start and end"),
}

enum class CalendarFilter(val label: String) { BOTH("Both"), EVENTS("Events"), TASKS("Tasks") }

object EventRules {
    /** All-day reminders are relative to this local time on the start date. */
    val ALL_DAY_REMINDER_BASE: LocalTime = LocalTime.of(9, 0)

    fun validate(title: String, allDay: Boolean, startDate: LocalDate?, endDate: LocalDate?, startUtc: Instant?, endUtc: Instant?): EventError? {
        if (title.isBlank()) return EventError.EMPTY_TITLE
        return if (allDay) {
            when {
                startDate == null || endDate == null -> EventError.MISSING_TIME
                endDate.isBefore(startDate) -> EventError.END_BEFORE_START
                else -> null
            }
        } else {
            when {
                startUtc == null || endUtc == null -> EventError.MISSING_TIME
                endUtc.isBefore(startUtc) -> EventError.END_BEFORE_START
                else -> null
            }
        }
    }

    /** Whether the event is visible on [date] in the device zone [zone]. */
    fun occursOn(event: EventEntity, date: LocalDate, zone: ZoneId): Boolean {
        if (event.deletedUtc != null) return false
        if (event.allDay) {
            val start = event.startDate ?: return false
            val end = event.endDate ?: start
            return !date.isBefore(start) && !date.isAfter(end)
        }
        val start = event.startUtc ?: return false
        val end = event.endUtc ?: start
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        if (start == end) return !start.isBefore(dayStart) && start.isBefore(dayEnd)
        return start.isBefore(dayEnd) && end.isAfter(dayStart)
    }

    /** Local wall-clock moment the reminder fires, or null without a reminder. */
    fun reminderTime(event: EventEntity, zone: ZoneId): LocalDateTime? {
        val minutes = event.reminderMinutes ?: return null
        val base = if (event.allDay) {
            event.startDate?.atTime(ALL_DAY_REMINDER_BASE) ?: return null
        } else {
            (event.startUtc ?: return null).atZone(zone).toLocalDateTime()
        }
        return base.minusMinutes(minutes.toLong())
    }

    fun sortKey(event: EventEntity, zone: ZoneId): LocalDateTime =
        if (event.allDay) (event.startDate ?: LocalDate.MIN).atStartOfDay() else (event.startUtc ?: java.time.Instant.EPOCH).atZone(zone).toLocalDateTime()
}
