package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Reminder
import com.claudecode.countdown.data.db.Task
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RemindersTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 1)
    private val min = 60_000L

    private fun timed(time: LocalTime) = timedDue(day, time, zone).let { Task(title = "t", dueAt = it.at, timeZone = it.timeZone) }

    @Test
    fun offsetsCountBackFromDueTime() {
        val task = timed(LocalTime.of(18, 0))
        assertEquals(task.dueAt, reminderTrigger(task, Reminder(taskId = task.id, offsetMinutes = 0)))
        assertEquals(task.dueAt!! - 30 * min, reminderTrigger(task, Reminder(taskId = task.id, offsetMinutes = 30)))
    }

    @Test
    fun allDayRemindsAtNine() {
        val due = allDayDue(day, zone)
        val task = Task(title = "t", dueAt = due.at, isAllDay = true, timeZone = due.timeZone)
        assertEquals(timedDue(day, LocalTime.of(9, 0), zone).at, reminderTrigger(task, Reminder(taskId = task.id, offsetMinutes = 0)))
        assertEquals(timedDue(day.minusDays(1), LocalTime.of(9, 0), zone).at, reminderTrigger(task, Reminder(taskId = task.id, offsetMinutes = 24 * 60)))
    }

    @Test
    fun laterSnoozeWinsButOldSnoozeIsIgnored() {
        val task = timed(LocalTime.of(18, 0))
        val due = task.dueAt!!
        assertEquals(due + 15 * min, reminderTrigger(task, Reminder(taskId = task.id, snoozedUntil = due + 15 * min)))
        // After a repeating task moved forward, a stale snooze must not hide the new trigger.
        assertEquals(due, reminderTrigger(task, Reminder(taskId = task.id, snoozedUntil = due - 60 * min)))
    }

    @Test
    fun windowSelection() {
        val a = timed(LocalTime.of(10, 0))
        val b = timed(LocalTime.of(12, 0))
        val tasks = listOf(a, b).associateBy { it.id }
        val reminders = listOf(Reminder(taskId = a.id), Reminder(taskId = b.id))
        val due = dueReminders(tasks, reminders, a.dueAt!! - 1, a.dueAt!! + 1)
        assertEquals(listOf(a.id), due.map { it.first.id })
        assertEquals(b.dueAt, nextTrigger(tasks, reminders, a.dueAt!!))
    }

    @Test
    fun labels() {
        assertEquals("В момент", reminderLabel(0, false))
        assertEquals("За 1 день", reminderLabel(1440, false))
        assertEquals("За 2 ч 15 мин", reminderLabel(135, false))
        assertEquals("За день (9:00)", reminderLabel(1440, true))
    }
}
