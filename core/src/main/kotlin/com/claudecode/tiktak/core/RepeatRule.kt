package com.claudecode.tiktak.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** A weekday, optionally with an ordinal inside the month: 2MO = second Monday, -1FR = last Friday. */
data class WeekdayNum(val day: DayOfWeek, val ordinal: Int = 0)

/**
 * Subset of RFC 5545 RRULE: FREQ, INTERVAL, BYDAY, BYMONTHDAY (incl. -1 = last day), COUNT, UNTIL.
 * COUNT is the number of occurrences still left, decremented each time the task advances.
 */
data class RepeatRule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: List<WeekdayNum> = emptyList(),
    val byMonthDay: Int? = null,
    val count: Int? = null,
    val until: LocalDate? = null,
) {
    fun toRRule(): String = buildList {
        add("FREQ=${freq.name}")
        if (interval > 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.joinToString(",") { (if (it.ordinal != 0) "${it.ordinal}" else "") + CODES.getValue(it.day) })
        byMonthDay?.let { add("BYMONTHDAY=$it") }
        count?.let { add("COUNT=$it") }
        until?.let { add("UNTIL=" + it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
    }.joinToString(";")

    /**
     * First occurrence strictly after [from]. Intervals are counted from [from], which is the current
     * occurrence (or the completion date for "repeat from completion").
     */
    fun nextAfter(from: LocalDate): LocalDate? {
        if (count != null && count <= 1) return null
        val next = when (freq) {
            Freq.DAILY -> from.plusDays(interval.toLong())
            Freq.WEEKLY -> nextWeekly(from)
            Freq.MONTHLY -> nextMonthly(from)
            Freq.YEARLY -> nextYearly(from)
        }
        return next.takeIf { until == null || !it.isAfter(until) }
    }

    /** The rule to store after advancing once (COUNT shrinks). */
    fun advanced(): RepeatRule = if (count != null) copy(count = count - 1) else this

    private fun nextWeekly(from: LocalDate): LocalDate {
        val days = byDay.map { it.day }.toSortedSet()
        if (days.isEmpty()) return from.plusWeeks(interval.toLong())
        days.firstOrNull { it > from.dayOfWeek }?.let { return from.with(TemporalAdjusters.nextOrSame(it)) }
        val weekStart = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(interval.toLong())
        return weekStart.with(TemporalAdjusters.nextOrSame(days.first()))
    }

    private fun nextMonthly(from: LocalDate): LocalDate {
        val ordinals = byDay.filter { it.ordinal != 0 }
        if (ordinals.isNotEmpty()) {
            // Candidates in the current month after [from], otherwise in the month `interval` later.
            candidatesInMonth(from, ordinals).firstOrNull { it.isAfter(from) }?.let { return it }
            var month = from.withDayOfMonth(1).plusMonths(interval.toLong())
            repeat(48) {
                candidatesInMonth(month, ordinals).firstOrNull()?.let { return it }
                month = month.plusMonths(interval.toLong())
            }
            return from.plusMonths(interval.toLong())
        }
        val day = byMonthDay ?: from.dayOfMonth
        val month = from.withDayOfMonth(1).plusMonths(interval.toLong())
        return dayInMonth(month, day)
    }

    private fun nextYearly(from: LocalDate): LocalDate {
        val target = from.withDayOfMonth(1).plusYears(interval.toLong())
        return dayInMonth(target, byMonthDay ?: from.dayOfMonth)
    }

    companion object {
        private val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        private val BY_CODE = CODES.entries.associate { it.value to it.key }

        val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        fun parse(rrule: String?): RepeatRule? {
            if (rrule.isNullOrBlank()) return null
            val parts = rrule.removePrefix("RRULE:").split(';')
                .mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }
                .toMap()
            val freq = parts["FREQ"]?.let { f -> Freq.entries.firstOrNull { it.name == f.uppercase() } } ?: return null
            val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { token ->
                val t = token.trim().uppercase()
                val day = BY_CODE[t.takeLast(2)] ?: return@mapNotNull null
                WeekdayNum(day, t.dropLast(2).toIntOrNull() ?: 0)
            }.orEmpty()
            return RepeatRule(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                byDay = byDay,
                byMonthDay = parts["BYMONTHDAY"]?.toIntOrNull(),
                count = parts["COUNT"]?.toIntOrNull(),
                until = parts["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() },
            )
        }

        /** [day] of the month containing [anyDayOfMonth]; -1 = last day; clamped to month length. */
        fun dayInMonth(anyDayOfMonth: LocalDate, day: Int): LocalDate {
            val length = anyDayOfMonth.lengthOfMonth()
            val d = if (day < 0) length + day + 1 else day
            return anyDayOfMonth.withDayOfMonth(d.coerceIn(1, length))
        }

        private fun candidatesInMonth(anyDayOfMonth: LocalDate, days: List<WeekdayNum>): List<LocalDate> =
            days.mapNotNull { wd ->
                val adjuster = if (wd.ordinal > 0) TemporalAdjusters.dayOfWeekInMonth(wd.ordinal, wd.day)
                else TemporalAdjusters.lastInMonth(wd.day)
                val date = anyDayOfMonth.with(adjuster)
                date.takeIf { it.month == anyDayOfMonth.month }
            }.sorted()
    }
}
