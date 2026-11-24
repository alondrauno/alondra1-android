package com.alondra1.app.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Injectable clock so date rules (My Day, Overdue, DST) are testable. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun zonedNow(): ZonedDateTime = ZonedDateTime.ofInstant(now(), zone())
}

class SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedTimeSource(var clock: Clock) : TimeSource {
    override fun now(): Instant = clock.instant()
    override fun zone(): ZoneId = clock.zone
}
