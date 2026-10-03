package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Reminder
import com.claudecode.countdown.data.db.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SeriesTest {
    private val zone = ZoneId.systemDefault()
    private val monday = LocalDate.of(2026, 10, 5)

    /** A daily event 9:00–10:00 from Monday. */
    private fun daily(rule: String = "FREQ=DAILY") = Task(
        title = "Планёрка", isEvent = true, repeatRule = rule,
        startAt = timedDue(monday, LocalTime.of(9, 0)).at, dueAt = timedDue(monday, LocalTime.of(10, 0)).at,
    )

    private fun days(tasks: List<Task>, to: LocalDate = monday.plusDays(6)) =
        calendarEntries(tasks, monday, to).toSortedMap().mapValues { (_, list) -> list.map { localTimeOf(it.task.atOccurrence(it.occurrence).startAt!!) } }

    private fun Task.at(day: LocalDate, from: Int, to: Int) =
        copy(startAt = timedDue(day, LocalTime.of(from, 0)).at, dueAt = timedDue(day, LocalTime.of(to, 0)).at)

    @Test
    fun deletingOneOccurrenceSkipsOnlyThatDay() {
        val master = daily()
        val change = deleteFromSeries(master, monday.plusDays(2), SeriesScope.ONE)
        val shown = days(change.save)
        assertEquals(6, shown.size)
        assertTrue(monday.plusDays(2) !in shown)
    }

    @Test
    fun movingOneOccurrenceLeavesTheRestInPlace() {
        val master = daily()
        val wednesday = monday.plusDays(2)
        val change = editSeries(master, wednesday, master.at(wednesday, 14, 15), SeriesScope.ONE, now = 1)
        val shown = days(change.save)
        assertEquals(listOf(LocalTime.of(14, 0)), shown[wednesday])
        assertEquals(listOf(LocalTime.of(9, 0)), shown[monday.plusDays(3)])
        assertEquals(master.id, change.save[1].seriesId)
        assertNull(change.save[1].repeatRule)
    }

    @Test
    fun changingFollowingSplitsTheSeries() {
        val master = daily()
        val thursday = monday.plusDays(3)
        val change = editSeries(master, thursday, master.at(thursday, 18, 19), SeriesScope.FOLLOWING, now = 1)
        val shown = days(change.save)
        assertEquals(listOf(LocalTime.of(9, 0)), shown[monday.plusDays(2)])
        assertEquals(listOf(LocalTime.of(18, 0)), shown[thursday])
        assertEquals(listOf(LocalTime.of(18, 0)), shown[monday.plusDays(6)])
    }

    @Test
    fun followingKeepsTheTotalOfACountedSeries() {
        val master = daily("FREQ=DAILY;COUNT=5")
        val change = editSeries(master, monday.plusDays(2), master.at(monday.plusDays(2), 18, 19), SeriesScope.FOLLOWING, now = 1)
        assertEquals(5, days(change.save).size)
    }

    @Test
    fun changingAllMovesTheWholeSeries() {
        val master = daily()
        val wednesday = monday.plusDays(2)
        // Wednesday's occurrence moved to 7:00 on Thursday: every one moves a day later, to 7:00.
        val change = editSeries(master, wednesday, master.at(wednesday.plusDays(1), 7, 8), SeriesScope.ALL, now = 1)
        val shown = days(change.save)
        assertTrue(monday !in shown)
        assertEquals(listOf(LocalTime.of(7, 0)), shown[monday.plusDays(1)])
        assertEquals(master.id, change.save.single().id)
    }

    @Test
    fun deletingFollowingEndsTheSeries() {
        val change = deleteFromSeries(daily(), monday.plusDays(3), SeriesScope.FOLLOWING)
        assertEquals(3, days(change.save).size)
        assertEquals(listOf(daily().id).size, 1)
        val fromStart = deleteFromSeries(daily(), monday, SeriesScope.FOLLOWING)
        assertEquals(1, fromStart.delete.size)
    }

    @Test
    fun snoozedReminderOfARepeatingEventFiresTodayNotTomorrow() {
        val after = timedDue(monday, LocalTime.of(9, 1)).at
        val snoozed = Reminder(taskId = "x", offsetMinutes = 0, snoozedUntil = after + 10 * 60_000L)
        assertEquals(after + 10 * 60_000L, reminderTrigger(daily(), snoozed, after = after))
        // Once the snooze has fired, the series goes on with tomorrow.
        assertEquals(timedDue(monday.plusDays(1), LocalTime.of(9, 0)).at, reminderTrigger(daily(), snoozed, after = after + 11 * 60_000L))
    }

    @Test
    fun reminderSkipsADeletedOccurrence() {
        val master = deleteFromSeries(daily(), monday.plusDays(1), SeriesScope.ONE).save.single()
        val reminder = Reminder(taskId = master.id, offsetMinutes = 0)
        // After Monday's reminder the next one is on Wednesday: Tuesday is skipped.
        val after = timedDue(monday, LocalTime.of(9, 30)).at
        assertEquals(timedDue(monday.plusDays(2), LocalTime.of(9, 0)).at, reminderTrigger(master, reminder, after = after))
    }
}
