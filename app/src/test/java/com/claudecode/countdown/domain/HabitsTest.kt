package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Habit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HabitsTest {
    // Monday.
    private val today = LocalDate.of(2026, 9, 28)
    private fun counts(vararg daysAgo: Long, value: Int = 1) = daysAgo.associate { today.minusDays(it).toEpochDay() to value }

    @Test
    fun dailyStreakCountsBackFromYesterdayWhenTodayIsOpen() {
        val habit = Habit(name = "h")
        assertEquals(3, habitStats(habit, counts(1, 2, 3, 5), today).currentStreak)
        assertEquals(4, habitStats(habit, counts(0, 1, 2, 3, 5), today).currentStreak)
    }

    @Test
    fun unscheduledDaysDoNotBreakStreak() {
        // Mon/Wed/Fri habit: done on Fri 25 and Wed 23, today (Mon) not yet.
        val habit = Habit(name = "h", days = "MO,WE,FR")
        val stats = habitStats(habit, counts(3, 5), today)
        assertEquals(2, stats.currentStreak)
    }

    @Test
    fun goalAboveOneNeedsFullCount() {
        val habit = Habit(name = "water", goal = 8)
        assertEquals(0, habitStats(habit, counts(1, value = 5), today).currentStreak)
        assertEquals(1, habitStats(habit, counts(1, value = 8), today).currentStreak)
    }

    @Test
    fun bestStreakAndMonthRate() {
        val habit = Habit(name = "h", createdAt = 0)
        // Done 20..24 Sep (5 days), then gap, then 26..27.
        val stats = habitStats(habit, counts(8, 7, 6, 5, 4, 2, 1), today)
        assertEquals(5, stats.bestStreak)
        assertEquals(2, stats.currentStreak)
        assertEquals(7 * 100 / 28, stats.monthRate)
    }

    @Test
    fun monthRateStartsAtCreationDay() {
        val created = today.minusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val habit = Habit(name = "h", createdAt = created)
        assertEquals(50, habitStats(habit, counts(1), today).monthRate)
    }

    @Test
    fun daysCodeRoundTrip() {
        assertEquals("", habitDaysCode(DayOfWeek.entries.toSet()))
        assertEquals("MO,FR", habitDaysCode(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), habitDays("MO,FR"))
        assertEquals(7, habitDays("").size)
    }
}
