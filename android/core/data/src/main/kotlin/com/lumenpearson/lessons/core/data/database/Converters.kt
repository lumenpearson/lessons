package com.lumenpearson.lessons.core.data.database

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalTime

/**
 * `java.time` <-> SQLite bridging.
 *
 * Dates are stored as epoch days and times as seconds of day - integers rather
 * than ISO strings - for two reasons: `ORDER BY date` and `date BETWEEN ?` then
 * mean what they say without any collation subtleties, and each row costs a few
 * bytes instead of ten. Nothing outside Room ever sees these numbers.
 *
 * `java.time` needs no desugaring here: the module's minSdk is 26.
 */
internal class LessonsTypeConverters {

    /** @return days since 1970-01-01, negative before it. */
    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    /** @return seconds since midnight, local wall time, 0..86399. */
    @TypeConverter
    fun localTimeToSecondOfDay(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun secondOfDayToLocalTime(value: Int?): LocalTime? =
        value?.let { LocalTime.ofSecondOfDay(it.toLong()) }
}
