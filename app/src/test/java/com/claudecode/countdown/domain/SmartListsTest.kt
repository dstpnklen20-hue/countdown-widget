package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SmartListsTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 28)
    private val now = timedDue(today, LocalTime.of(12, 0), zone).at

    private fun task(title: String, day: LocalDate?, time: LocalTime? = null, done: Boolean = false, list: String = TaskList.INBOX_ID): Task {
        val due = day?.let { if (time == null) allDayDue(it, zone) else timedDue(it, time, zone) }
        return Task(
            title = title,
            listId = list,
            dueAt = due?.at,
            isAllDay = due?.isAllDay ?: false,
            timeZone = due?.timeZone,
            status = if (done) TaskStatus.DONE else TaskStatus.OPEN,
        )
    }

    private fun match(filter: TaskFilter, t: Task) = matches(filter, t, emptySet(), today, zone)

    @Test
    fun todayIncludesOverdueButNotFuture() {
        assertTrue(match(TaskFilter.Today, task("a", today)))
        assertTrue(match(TaskFilter.Today, task("overdue", today.minusDays(3))))
        assertFalse(match(TaskFilter.Today, task("done overdue", today.minusDays(3), done = true)))
        assertFalse(match(TaskFilter.Today, task("tomorrow", today.plusDays(1))))
        assertFalse(match(TaskFilter.Today, task("no date", null)))
    }

    @Test
    fun weekCoversSevenDays() {
        assertTrue(match(TaskFilter.Next7Days, task("d6", today.plusDays(6))))
        assertFalse(match(TaskFilter.Next7Days, task("d7", today.plusDays(7))))
        assertTrue(match(TaskFilter.Tomorrow, task("t", today.plusDays(1))))
    }

    @Test
    fun listAndTagFilters() {
        val t = task("x", null, list = "work")
        assertTrue(match(TaskFilter.ListFilter("work"), t))
        assertFalse(match(TaskFilter.Inbox, t))
        assertTrue(matches(TaskFilter.TagFilter("tag1"), t, setOf("tag1"), today, zone))
    }

    @Test
    fun countdownsShowOnlyInTheirOwnList() {
        val event = task("Отпуск", today).copy(displayMode = DisplayMode.COUNTDOWN)
        assertTrue(match(TaskFilter.Countdowns, event))
        for (f in listOf(TaskFilter.Inbox, TaskFilter.Today, TaskFilter.All, TaskFilter.Next7Days, TaskFilter.Completed)) {
            assertFalse(f.key, match(f, event))
        }
        assertFalse(match(TaskFilter.Countdowns, task("обычная", today)))
    }

    @Test
    fun timedTaskEarlierTodayIsOverdue() {
        assertTrue(task("morning", today, LocalTime.of(9, 0)).isOverdue(now, today, zone))
        assertFalse(task("evening", today, LocalTime.of(18, 0)).isOverdue(now, today, zone))
        assertFalse(task("all day", today).isOverdue(now, today, zone))
    }

    @Test
    fun groupsAreOrderedAndDoneGoesLast() {
        val tasks = listOf(
            task("later", today.plusDays(30)),
            task("none", null),
            task("done", today, done = true),
            task("wed", today.plusDays(2)),
            task("tomorrow", today.plusDays(1)),
            task("today", today),
            task("overdue", today.minusDays(1)),
        )
        val groups = groupTasks(TaskFilter.All, tasks, now, today, zone)
        assertEquals(
            listOf(GroupKind.OVERDUE, GroupKind.TODAY, GroupKind.TOMORROW, GroupKind.DAY, GroupKind.LATER, GroupKind.NO_DATE, GroupKind.DONE),
            groups.map { it.kind },
        )
    }

    @Test
    fun higherPrioritySortsFirstWithinDay() {
        val low = task("low", today).copy(priority = 1)
        val high = task("high", today).copy(priority = 3)
        val groups = groupTasks(TaskFilter.All, listOf(low, high), now, today, zone)
        assertEquals(listOf("high", "low"), groups.single().tasks.map { it.title })
    }

    @Test
    fun parseRoundTrips() {
        for (f in TaskFilter.SMART + TaskFilter.ListFilter("abc") + TaskFilter.TagFilter("t")) {
            assertEquals(f, TaskFilter.parse(f.key))
        }
    }
}
