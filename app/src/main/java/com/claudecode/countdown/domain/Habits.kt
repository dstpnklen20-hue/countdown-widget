package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Habit
import java.time.DayOfWeek
import java.time.LocalDate

private val CODES = mapOf(
    "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
    "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
)

fun habitDays(codes: String): Set<DayOfWeek> =
    codes.split(',').mapNotNull { CODES[it.trim().uppercase()] }.toSet().ifEmpty { DayOfWeek.entries.toSet() }

fun habitDaysCode(days: Set<DayOfWeek>): String =
    if (days.size == 7 || days.isEmpty()) "" else DayOfWeek.entries.filter { it in days }.joinToString(",") { d -> CODES.entries.first { it.value == d }.key }

fun Habit.isScheduled(day: LocalDate): Boolean = day.dayOfWeek in habitDays(days)

data class HabitStats(val currentStreak: Int, val bestStreak: Int, val totalDone: Int, val monthRate: Int)

/**
 * Streaks count consecutive scheduled days that reached the goal; unscheduled days neither
 * extend nor break a streak. An unfinished today does not break the current streak yet.
 */
fun habitStats(habit: Habit, counts: Map<Long, Int>, today: LocalDate): HabitStats {
    fun done(day: LocalDate) = (counts[day.toEpochDay()] ?: 0) >= habit.goal

    var current = 0
    var day = if (done(today)) today else today.minusDays(1)
    var guard = 0
    while (guard++ < 3660) {
        if (habit.isScheduled(day)) {
            if (done(day)) current++ else break
        }
        day = day.minusDays(1)
    }

    val doneDays = counts.filter { it.value >= habit.goal }.keys.sorted()
    var best = 0
    if (doneDays.isNotEmpty()) {
        var run = 0
        var d = LocalDate.ofEpochDay(doneDays.first())
        val last = LocalDate.ofEpochDay(doneDays.last())
        while (!d.isAfter(last)) {
            if (habit.isScheduled(d)) {
                if (done(d)) { run++; best = maxOf(best, run) } else run = 0
            }
            d = d.plusDays(1)
        }
    }

    // A habit created mid-month is only judged from the day it was created.
    val created = java.time.Instant.ofEpochMilli(habit.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val monthStart = maxOf(today.withDayOfMonth(1), created)
    var scheduled = 0
    var hit = 0
    var d = monthStart
    while (!d.isAfter(today)) {
        if (habit.isScheduled(d)) {
            scheduled++
            if (done(d)) hit++
        }
        d = d.plusDays(1)
    }
    val rate = if (scheduled == 0) 0 else hit * 100 / scheduled
    return HabitStats(current, maxOf(best, current), doneDays.size, rate)
}
