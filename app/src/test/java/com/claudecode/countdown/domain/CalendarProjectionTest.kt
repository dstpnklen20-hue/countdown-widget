package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarProjectionTest {
    private val start = LocalDate.of(2026, 9, 28)

    private fun task(day: LocalDate, rule: String? = null, done: Boolean = false, from: RepeatFrom = RepeatFrom.DUE): Task {
        val due = allDayDue(day)
        return Task(
            title = "t", dueAt = due.at, isAllDay = true, timeZone = due.timeZone, repeatRule = rule, repeatFrom = from,
            status = if (done) TaskStatus.DONE else TaskStatus.OPEN,
        )
    }

    @Test
    fun weeklyTaskIsProjectedAcrossTheMonth() {
        val entries = calendarEntries(listOf(task(start, "FREQ=WEEKLY;BYDAY=MO")), start, start.plusDays(27))
        assertEquals(listOf(0L, 7L, 14L, 21L).map { start.plusDays(it) }, entries.keys.sorted())
        assertFalse(entries.getValue(start).single().projected)
        assertTrue(entries.getValue(start.plusDays(7)).single().projected)
    }

    @Test
    fun countLimitsProjection() {
        val entries = calendarEntries(listOf(task(start, "FREQ=DAILY;COUNT=3")), start, start.plusDays(10))
        assertEquals(3, entries.size)
    }

    @Test
    fun seriesStartingBeforeRangeStillShowsInside() {
        val entries = calendarEntries(listOf(task(start.minusDays(30), "FREQ=DAILY")), start, start.plusDays(2))
        assertEquals(3, entries.size)
    }

    @Test
    fun doneAndFromCompletionTasksAreNotProjected() {
        val entries = calendarEntries(
            listOf(task(start, "FREQ=DAILY", done = true), task(start, "FREQ=DAILY", from = RepeatFrom.COMPLETION)),
            start, start.plusDays(5),
        )
        assertEquals(setOf(start), entries.keys)
        assertEquals(2, entries.getValue(start).size)
    }
}
