package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun sleepPastMidnightShowsOnBothDaysAndRepeats() {
        val sleep = Task(title = "Сон", isEvent = true, startAt = at(start, 23), dueAt = at(start.plusDays(1), 5, 45), repeatRule = "FREQ=DAILY")
        val entries = calendarEntries(listOf(sleep), start, start.plusDays(2), zone = zone)
        val first = entries.getValue(start).single()
        assertEquals(23 * 60 to MINUTES_PER_DAY, first.start to first.end)
        assertEquals(1 to 2, first.part to first.parts)
        // The next day has the morning part of the first night and the evening part of the second.
        val next = entries.getValue(start.plusDays(1)).map { it.start to it.end }
        assertEquals(listOf(0 to 5 * 60 + 45, 23 * 60 to MINUTES_PER_DAY), next)
        assertTrue(entries.getValue(start.plusDays(2)).first().projected)
    }

    @Test
    fun timedTaskWithoutStartIsAHalfHourBlock() {
        val call = Task(title = "Звонок", dueAt = at(start, 10))
        val e = calendarEntries(listOf(call), start, start, zone = zone).getValue(start).single()
        assertEquals(600 to 630, e.start to e.end)
    }

    @Test
    fun allDayEventOverSeveralDaysFillsEachDay() {
        val trip = Task(
            title = "Поездка", isEvent = true, isAllDay = true, timeZone = zone.id,
            startAt = allDayDue(start, zone).at, dueAt = allDayDue(start.plusDays(2), zone).at,
        )
        val entries = calendarEntries(listOf(trip), start, start.plusDays(5), zone = zone)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), entries.keys.sorted())
        assertEquals(listOf(1, 2, 3), entries.keys.sorted().map { entries.getValue(it).single().part })
    }

    @Test
    fun laterOverlappingBlockIsNestedLikeGoogleCalendar() {
        // Morning routine 5:45–6:55, breakfast 6:35–7:05: breakfast sits on top, one step in.
        val placed = layoutBlocks(listOf(TimeBlock("routine", 345, 415), TimeBlock("breakfast", 395, 425))).associateBy { it.item }
        assertEquals(1, placed.getValue("breakfast").lanes)
        assertEquals(1, placed.getValue("breakfast").depth)
        assertEquals(0, placed.getValue("routine").depth)
    }
}
