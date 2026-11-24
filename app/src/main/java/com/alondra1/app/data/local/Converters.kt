package com.alondra1.app.data.local

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Instants as epoch milliseconds; date/time values as ISO-8601 text (sortable, no time zone conversion). */
class Converters {
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
    @TypeConverter fun dateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun timeToString(value: LocalTime?): String? = value?.toString()
    @TypeConverter fun stringToTime(value: String?): LocalTime? = value?.let(LocalTime::parse)
    @TypeConverter fun dateTimeToString(value: LocalDateTime?): String? = value?.toString()
    @TypeConverter fun stringToDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)
}
